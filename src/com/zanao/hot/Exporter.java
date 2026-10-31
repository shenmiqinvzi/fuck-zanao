package com.zanao.hot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出存档为 zip（走 SAF，不需要任何存储权限）。
 *
 * 内容：
 *   zanao_hot.db   —— 全部期数、帖子详情、逐条评论
 *   img/<md5>      —— 已本地化的图片原图
 *   manifest.txt   —— 导出时间、期数、帖数、评论数、图片数
 *
 * 为什么要导出：平台下架后原站链接会失效，图也会从 CDN 消失。
 * 数据放在应用私有目录里，卸载就没了；导出到用户选的位置才算真·永久。
 */
public final class Exporter {

    private Exporter() {
    }

    static String defaultName() {
        return "zanao_hot_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".zip";
    }

    /** 弹出系统文件选择器 */
    static void pickTarget(Activity a, int reqCode) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, defaultName());
        a.startActivityForResult(i, reqCode);
    }

    /** 把库和图片写进用户选定的 Uri。返回说明文本 */
    static String write(Context ctx, Uri target) throws Exception {
        Db db = new Db(ctx);
        int periods = 0;
        int threads = 0;
        int comments = 0;
        try {
            android.database.Cursor c1 = db.getReadableDatabase().rawQuery(
                    "SELECT COUNT(DISTINCT period) FROM rank_snapshot", null);
            if (c1.moveToFirst()) periods = c1.getInt(0);
            c1.close();
            android.database.Cursor c2 = db.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM threads", null);
            if (c2.moveToFirst()) threads = c2.getInt(0);
            c2.close();
            android.database.Cursor c3 = db.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM comments", null);
            if (c3.moveToFirst()) comments = c3.getInt(0);
            c3.close();

            OutputStream os = ctx.getContentResolver().openOutputStream(target);
            if (os == null) throw new Api.IOException2("无法打开输出目标");
            ZipOutputStream zip = new ZipOutputStream(os);
            try {
                // 1) 数据库：先 checkpoint 保证 WAL 内容落进主文件
                // 注意：PRAGMA wal_checkpoint 会返回结果集，execSQL 会抛
                // "Queries can be performed using SQLiteDatabase query or rawQuery methods only"，
                // 必须走 rawQuery 并消费游标。
                android.database.Cursor ck = db.getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)", null);
                try {
                    ck.moveToFirst();
                } finally {
                    ck.close();
                }
                File dbf = ctx.getDatabasePath("zanao_hot.db");
                putFile(zip, "zanao_hot.db", dbf);

                // 2) 图片
                File imgDir = new File(ctx.getFilesDir(), "img");
                File[] imgs = imgDir.listFiles();
                int imgCount = 0;
                if (imgs != null) {
                    for (File f : imgs) {
                        if (f.length() == 0) continue;
                        putFile(zip, "img/" + f.getName(), f);
                        imgCount++;
                    }
                }

                // 3) 说明
                StringBuilder sb = new StringBuilder();
                sb.append("赞奥热榜 存档导出\n");
                sb.append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
                sb.append("期数: ").append(periods).append('\n');
                sb.append("帖子: ").append(threads).append('\n');
                sb.append("评论: ").append(comments).append('\n');
                sb.append("图片: ").append(imgCount).append('\n');
                sb.append('\n');
                sb.append("说明: 图片文件名是原 URL 的 md5；对照 threads.detail_json 与 comments.raw_json 里的\n");
                sb.append("img_paths / img_list 即可还原对应关系。\n");
                sb.append("原站地址: http://api.app.zanao.com  社区: zzu\n");
                zip.putNextEntry(new ZipEntry("manifest.txt"));
                zip.write(sb.toString().getBytes("UTF-8"));
                zip.closeEntry();
            } finally {
                zip.close();
            }
            return "已导出 " + periods + " 期 / " + threads + " 帖 / " + comments + " 条评论 / " + sizeText(ImgCache.size(ctx));
        } finally {
            db.close();
        }
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws Exception {
        if (f == null || !f.exists() || f.length() == 0) return;
        zip.putNextEntry(new ZipEntry(name));
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
        } finally {
            in.close();
        }
        zip.closeEntry();
    }

    static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
