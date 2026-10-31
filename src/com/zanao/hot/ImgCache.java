package com.zanao.hot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 图片本地化缓存 —— "永久存档"的关键：热帖被下架后 CDN 图也会失效，抓到必须落盘。
 *
 * v2 变化：文件名从 md5(url) 改为 md5(图片字节内容)。
 * 理由（实测）：同一张图会挂在不同 URL 上（如加不同 @! 后缀、不同帖复用同图），
 * 按 URL 命名会存多份。实测 52 个文件 2.78 MB，按内容去重后只剩 1.17 MB。
 * url → 内容 md5 的映射存在数据库 img_ref 表，导出时据此还原目录结构。
 *
 * v3 变化：落盘一律换成缩略图 @!sm_w400。原图平均 200 KB、最大 1.7 MB（3427x4570），
 * 缩略图只有 1/11 大小且 400px 宽足够列表和详情页显示。已落盘的原图由 shrink()
 * 逐步替换 —— 先下好缩略图再删原图，绝不在没拿到新图之前删旧图。
 */
public final class ImgCache {

    /** 超过这个字节数的落盘图视为"原图"，维护时换成缩略图 */
    static final long BIG_THRESHOLD = 60 * 1024;

    private ImgCache() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    /** 兼容旧版：按 url 的 md5 命名的历史文件仍可读 */
    static String urlMd5(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : h) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    /** 按 url 查 img_ref 得到内容 md5；查不到返回 null */
    static String contentMd5Of(Context c, String url) {
        try {
            Db db = new Db(c);
            Cursor cur = db.getReadableDatabase().rawQuery(
                    "SELECT content_md5 FROM img_ref WHERE url=?", new String[]{url});
            try {
                if (cur.moveToFirst()) return cur.getString(0);
            } finally {
                cur.close();
                db.close();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** 已落盘的文件（按内容 md5 命名）；没有则退化为旧版 url-md5 命名 */
    public static File fileOf(Context c, String url) {
        String cmd5 = contentMd5Of(c, url);
        if (cmd5 != null) {
            File f = new File(dir(c), cmd5);
            if (f.exists() && f.length() > 0) return f;
        }
        File legacy = new File(dir(c), urlMd5(url));
        if (legacy.exists() && legacy.length() > 0) return legacy;
        return new File(dir(c), cmd5 != null ? cmd5 : urlMd5(url));
    }

    public static boolean has(Context c, String url) {
        File f = fileOf(c, url);
        return f.exists() && f.length() > 0;
    }

    /** 本地有就直接返回；没有则下载、按内容 md5 落盘、写 img_ref。失败返回 null */
    public static File fetch(Context c, String url) {
        File f = fileOf(c, url);
        if (f.exists() && f.length() > 0) return f;
        return downloadAndStore(c, url);
    }

    /**
     * 无视本地已有文件，强制下载（落盘一律用缩略图）并写 img_ref。
     * shrink() 必须走这个 —— 走 fetch() 会先命中旧的大图然后直接返回，永远换不成缩略图。
     */
    static File downloadAndStore(Context c, String url) {
        // 落盘一律换缩略图：库里存的是小图，省空间也省流量
        String dl = Api.thumbUrl(url);
        if (dl == null) dl = url;
        HttpURLConnection conn = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(dl).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "okhttp/4.10.0");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;

            String cmd5 = md5(data);
            File target = new File(dir(c), cmd5);
            if (!target.exists() || target.length() == 0) {
                File tmp = new File(target.getAbsolutePath() + ".part");
                out = new FileOutputStream(tmp);
                out.write(data);
                out.flush();
                out.close();
                out = null;
                if (target.exists()) target.delete();
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    return null;
                }
            }
            recordRef(c, url, cmd5, data.length);
            return target;
        } catch (Exception e) {
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignore) { }
            if (conn != null) conn.disconnect();
        }
    }

    /** 写 url → 内容 md5 映射（同一张图挂多个 url 时，内容只存一份） */
    static void recordRef(Context c, String url, String cmd5, long bytes) {
        try {
            Db db = new Db(c);
            SQLiteDatabase w = db.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("url", url);
            v.put("content_md5", cmd5);
            v.put("bytes", bytes);
            v.put("first_seen", System.currentTimeMillis());
            w.insertWithOnConflict("img_ref", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.close();
        } catch (Exception ignore) {
        }
    }

    /** 预下载一批 URL（抓取时调用，保证离线也能看图） */
    public static int prefetch(Context c, String[] urls) {
        int ok = 0;
        for (String u : urls) {
            if (u == null || u.length() == 0) continue;
            if (has(c, u)) continue;
            if (fetch(c, u) != null) ok++;
        }
        return ok;
    }

    /**
     * 把已落盘的大图（原图 @!common）逐张换成缩略图，返回 {换成功张数, 释放字节数}。
     *
     * 安全顺序：先下载缩略图落盘 → 确认新文件存在且有内容 → 再把 img_ref 指向新内容 md5
     * → 最后才删旧的大文件。任何一步失败都保留原图，绝不出现"图没了"。
     * 已下架的帖图片下载会失败，此时旧图原样保留 —— 这正是永久存档要的效果。
     *
     * budgetMs 控制单次维护时长，避免一次跑太久。传 0 表示不限时（导出前全量整理用）。
     */
    public static long[] shrink(Context c, long budgetMs) {
        long done = 0;
        long freed = 0;
        long deadline = budgetMs > 0 ? System.currentTimeMillis() + budgetMs : Long.MAX_VALUE;
        Db db = null;
        try {
            db = new Db(c);
            Cursor cur = db.getReadableDatabase().rawQuery(
                    "SELECT url, content_md5, bytes FROM img_ref WHERE bytes > ? ORDER BY bytes DESC",
                    new String[]{String.valueOf(BIG_THRESHOLD)});
            java.util.List<String[]> todo = new java.util.ArrayList<String[]>();
            try {
                while (cur.moveToNext()) {
                    todo.add(new String[]{cur.getString(0), cur.getString(1), String.valueOf(cur.getLong(2))});
                }
            } finally {
                cur.close();
            }
            for (String[] row : todo) {
                if (System.currentTimeMillis() > deadline) break;
                String url = row[0];
                String oldMd5 = row[1];
                File oldFile = new File(dir(c), oldMd5);
                long oldLen = oldFile.exists() ? oldFile.length() : 0;

                // 强制下载缩略图（不能走 fetch：fetch 会先命中旧大图直接返回）
                File neu = downloadAndStore(c, url);
                if (neu == null) continue;                       // 下载失败：保留原图
                if (!neu.exists() || neu.length() == 0) continue; // 新文件不完整：保留原图
                if (neu.getName().equals(oldMd5)) continue;       // 内容没变，无事可做
                done++;
                if (oldLen > 0) {
                    oldFile.delete();
                    freed += oldLen;
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignore) { }
        }
        // 顺手清孤儿文件：既不是任何 img_ref 的内容 md5，也不是任何已知 url 的 url-md5。
        // 后者要留着 —— fileOf() 的退化分支靠它读 v1 时代的老图。
        long[] gc = gcOrphans(c);
        return new long[]{done, freed + gc[1]};
    }

    /**
     * 删掉没有任何 img_ref 引用的图片文件（换缩略图后旧原图会变成孤儿）。
     * 保留「urlMd5(任何 img_ref.url)」命名的文件，那是 v1 遗留的可读老图。
     * 返回 {删掉个数, 释放字节数}
     */
    public static long[] gcOrphans(Context c) {
        long n = 0, bytes = 0;
        java.util.Set<String> keep = new java.util.HashSet<String>();
        Db db = null;
        try {
            db = new Db(c);
            Cursor cur = db.getReadableDatabase().rawQuery("SELECT url, content_md5 FROM img_ref", null);
            try {
                while (cur.moveToNext()) {
                    String cmd5 = cur.getString(1);
                    if (cmd5 != null) keep.add(cmd5);
                    String url = cur.getString(0);
                    if (url != null) keep.add(urlMd5(url));
                }
            } finally {
                cur.close();
            }
        } catch (Exception ignore) {
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignore) { }
        }
        File[] fs = dir(c).listFiles();
        if (fs == null) return new long[]{0, 0};
        for (File f : fs) {
            String name = f.getName();
            if (name.endsWith(".part")) {           // 下载中断的临时文件，直接删
                bytes += f.length();
                f.delete();
                n++;
                continue;
            }
            if (keep.contains(name)) continue;
            bytes += f.length();
            f.delete();
            n++;
        }
        return new long[]{n, bytes};
    }

    /** 清缓存，返回释放的字节数 */
    public static long clear(Context c) {
        File d = dir(c);
        long total = 0;
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                total += f.length();
                f.delete();
            }
        }
        try {
            Db db = new Db(c);
            db.getWritableDatabase().delete("img_ref", null, null);
            db.close();
        } catch (Exception ignore) {
        }
        return total;
    }

    public static long size(Context c) {
        long total = 0;
        File[] fs = dir(c).listFiles();
        if (fs != null) {
            for (File f : fs) total += f.length();
        }
        return total;
    }

    static byte[] readAll(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
