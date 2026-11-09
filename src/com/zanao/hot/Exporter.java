package com.zanao.hot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 导出存档为 zip（走 SAF，不需要任何存储权限）。
 *
 * 内容：
 *   zanao_hot.db   —— 全部帖子详情、逐条评论、名次轨迹
 *   img/<md5>      —— 已本地化的图片原图
 *   manifest.txt   —— 导出时间、帖数、评论数、图片数
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

    /** 弹出系统文件选择器，选一个存档 zip 回来 */
    static void pickSource(Activity a, int reqCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        a.startActivityForResult(i, reqCode);
    }

    /**
     * 从存档 zip 还原数据库与图片（覆盖当前数据）。
     *
     * 调用前必须先把已有 Db 连接关掉并置空，否则旧库文件被占用、改名失败。
     * 导入的库可能是老版本的：Db 打开时会自动迁移到当前 VER。
     */
    static String read(Context ctx, Uri src) throws Exception {
        File dbf = ctx.getDatabasePath("zanao_hot.db");
        File dir = dbf.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmpDb = new File(dir, "zanao_hot.db.import");
        File imgDir = new File(ctx.getFilesDir(), "img");
        if (!imgDir.exists()) imgDir.mkdirs();

        InputStream in = ctx.getContentResolver().openInputStream(src);
        if (in == null) throw new Api.IOException2("无法打开存档");
        int imgCount = 0;
        boolean hasDb = false;
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in));
        try {
            byte[] buf = new byte[8192];
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (!e.isDirectory()) {
                    if (name.equals("zanao_hot.db")) {
                        FileOutputStream fo = new FileOutputStream(tmpDb);
                        try {
                            int n;
                            while ((n = zip.read(buf)) > 0) fo.write(buf, 0, n);
                        } finally {
                            fo.close();
                        }
                        hasDb = true;
                    } else if (name.startsWith("img/")) {
                        String base = name.substring(4);
                        // 只取 img/ 下的单层文件名，防止存档里塞 ../ 越界写出
                        if (base.length() > 0 && base.indexOf('/') < 0 && base.indexOf('\\') < 0) {
                            File out = new File(imgDir, base);
                            FileOutputStream fo = new FileOutputStream(out);
                            try {
                                int n;
                                while ((n = zip.read(buf)) > 0) fo.write(buf, 0, n);
                            } finally {
                                fo.close();
                            }
                            imgCount++;
                        }
                    }
                }
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        if (!hasDb) throw new Api.IOException2("存档里没有 zanao_hot.db，不是本应用的导出包");

        // 覆盖：旧库连同 wal/shm 一起清掉，再把导入文件改名到位
        for (String s : new String[]{"-wal", "-shm"}) {
            File f = new File(dir, dbf.getName() + s);
            if (f.exists()) f.delete();
        }
        if (dbf.exists() && !dbf.delete()) throw new Api.IOException2("旧数据库删除失败");
        if (!tmpDb.renameTo(dbf)) throw new Api.IOException2("数据库落位失败");

        Db db = new Db(ctx);
        try {
            int threads = count(db, "SELECT COUNT(*) FROM threads");
            int comments = count(db, "SELECT COUNT(*) FROM comments");
            return "已导入 " + threads + " 帖 / " + comments + " 条评论 / " + imgCount + " 张图片";
        } finally {
            db.close();
        }
    }

    /** 把库和图片写进用户选定的 Uri。返回说明文本 */
    static String write(Context ctx, Uri target) throws Exception {
        Db db = new Db(ctx);
        int threads = 0;
        int comments = 0;
        int bodyHist = 0;
        int commentHist = 0;
        int rankEvents = 0;
        try {
            threads = count(db, "SELECT COUNT(*) FROM threads");
            comments = count(db, "SELECT COUNT(*) FROM comments");
            bodyHist = count(db, "SELECT COUNT(*) FROM thread_detail_snap");
            commentHist = count(db, "SELECT COUNT(*) FROM comment_content_snap");
            rankEvents = count(db, "SELECT COUNT(*) FROM rank_event");

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
                sb.append("帖子: ").append(threads).append('\n');
                sb.append("评论: ").append(comments).append('\n');
                sb.append("正文历史版本: ").append(bodyHist).append('\n');
                sb.append("评论原话留存: ").append(commentHist).append('\n');
                sb.append("名次变化记录: ").append(rankEvents).append('\n');
                sb.append("图片: ").append(imgCount).append('\n');
                sb.append('\n');
                sb.append("说明: 图片文件名是图片内容的 md5（不是 URL 的 md5），同一张图挂不同 URL 只存一份。\n");
                sb.append("对照 img_ref 表（url -> content_md5）即可还原 URL 与文件的对应关系。\n");
                sb.append("thread_detail_snap 存帖子正文被编辑前的旧版本；\n");
                sb.append("comment_content_snap 存评论被删/被改前的原话。\n");
                sb.append("原站地址: http://api.app.zanao.com  社区: zzu\n");
                zip.putNextEntry(new ZipEntry("manifest.txt"));
                zip.write(sb.toString().getBytes("UTF-8"));
                zip.closeEntry();
            } finally {
                zip.close();
            }
            return "已导出 " + threads + " 帖 / " + comments + " 条评论 / " + bodyHist + " 版正文 / "
                    + commentHist + " 条原话 / " + sizeText(ImgCache.size(ctx));
        } finally {
            db.close();
        }
    }

    private static int count(Db db, String sql) {
        android.database.Cursor c = db.getReadableDatabase().rawQuery(sql, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
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
