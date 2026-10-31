package com.zanao.hot;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 图片本地化缓存 —— 这是"永久存档"的关键：
 * 热帖会被平台下架，CDN 上的图也会一起失效，所以抓到就必须落盘。
 *
 * 落盘位置：应用私有目录 img/，文件名 = URL 的 md5。
 * 读取优先本地，没有才走网络（并顺手存下来）。
 */
public final class ImgCache {

    private ImgCache() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static String nameOf(String url) {
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

    public static File fileOf(Context c, String url) {
        return new File(dir(c), nameOf(url));
    }

    public static boolean has(Context c, String url) {
        File f = fileOf(c, url);
        return f.exists() && f.length() > 0;
    }

    /** 本地有就直接返回；没有则下载并落盘。失败返回 null */
    public static File fetch(Context c, String url) {
        File f = fileOf(c, url);
        if (f.exists() && f.length() > 0) return f;
        HttpURLConnection conn = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "okhttp/4.10.0");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            InputStream in = conn.getInputStream();
            File tmp = new File(f.getAbsolutePath() + ".part");
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            in.close();
            if (tmp.length() == 0) {
                tmp.delete();
                return null;
            }
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) {
                tmp.delete();
                return null;
            }
            return f;
        } catch (Exception e) {
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignore) { }
            if (conn != null) conn.disconnect();
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
