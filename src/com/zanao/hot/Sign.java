package com.zanao.hot;

import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 赞奥签名算法（v1.3 实测版）—— 零 Android 依赖，可直接在桌面 JVM 上跑单测。
 *
 * 6 步：
 *  1) body = 参数按 key 升序拼 "k=v"，用 & 连接（跳过 key 名为 key 的项）
 *  2) concat = randnum__ + timestamp__
 *  3) K1 = codebookBase64(concat)  自定义码本、无 padding
 *  4) H1 = md5_hex_lower(K1)
 *  5) K2 = stdBase64( bytes((K1[i] + H1[i % 32]) & 0xFF) )  标准码表、带 padding
 *  6) sign = md5_hex_upper(body + "&secretkey=" + K2)
 *
 * token / alias 不参与签名。
 */
public final class Sign {

    private static final String CODEBOOK =
            "HySuiITldDszAQ9ZCx2pkePtE0qgUr_JYn1KNjO-afc4b7M658BFGLRVWXhmovw3";

    private static final String STD64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private Sign() {
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    /**
     * 自定义码本 base64。
     * 注意：文档 §3 说"无 padding"，但实测参考实现（sign_final5.py 第 12-14 行）
     * 对不足 3 字节的尾块用 '=' 补齐 —— 1 字节出 "XY=="，2 字节出 "XYZ="。
     * K1 必须带 padding，否则 K2 长度变短、最终签名全错。
     */
    public static String codebookBase64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int remain = data.length - i;
            int len = remain > 3 ? 3 : remain;
            int b0 = data[i] & 0xFF;
            int b1 = len > 1 ? (data[i + 1] & 0xFF) : 0;
            int b2 = len > 2 ? (data[i + 2] & 0xFF) : 0;
            int n = (b0 << 16) | (b1 << 8) | b2;
            sb.append(CODEBOOK.charAt((n >> 18) & 63));
            sb.append(CODEBOOK.charAt((n >> 12) & 63));
            if (len > 1) {
                sb.append(CODEBOOK.charAt((n >> 6) & 63));
            } else {
                sb.append('=');
            }
            if (len > 2) {
                sb.append(CODEBOOK.charAt(n & 63));
            } else {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    /** 标准 base64，带 padding */
    public static String stdBase64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int remain = data.length - i;
            int len = remain > 3 ? 3 : remain;
            int b0 = data[i] & 0xFF;
            int b1 = len > 1 ? (data[i + 1] & 0xFF) : 0;
            int b2 = len > 2 ? (data[i + 2] & 0xFF) : 0;
            int n = (b0 << 16) | (b1 << 8) | b2;
            sb.append(STD64.charAt((n >> 18) & 63));
            sb.append(STD64.charAt((n >> 12) & 63));
            if (len > 1) {
                sb.append(STD64.charAt((n >> 6) & 63));
            } else {
                sb.append('=');
            }
            if (len > 2) {
                sb.append(STD64.charAt(n & 63));
            } else {
                sb.append('=');
            }
        }
        return sb.toString();
    }

    public static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 unavailable", e);
        }
    }

    public static String md5Hex(String s) {
        return md5Hex(utf8(s));
    }

    /** 计算 X-Sc-Ah。params 必须已包含 randnum__ 与 timestamp__（TreeMap 保证 key 升序）。 */
    public static String make(TreeMap<String, String> params) {
        String rand = params.get("randnum__");
        String ts = params.get("timestamp__");
        if (rand == null) rand = "null";
        if (ts == null) ts = "null";

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("key".equals(e.getKey())) continue;
            String v = e.getValue();
            if (v == null) v = "null";
            if (body.length() > 0) body.append('&');
            body.append(e.getKey()).append('=').append(v);
        }

        String k1 = codebookBase64(utf8(rand + ts));
        String h1 = md5Hex(utf8(k1));
        byte[] k1b = utf8(k1);
        byte[] h1b = utf8(h1);
        byte[] k2raw = new byte[k1b.length];
        for (int i = 0; i < k1b.length; i++) {
            k2raw[i] = (byte) (((k1b[i] & 0xFF) + (h1b[i % 32] & 0xFF)) & 0xFF);
        }
        String k2 = stdBase64(k2raw);
        return md5Hex(utf8(body + "&secretkey=" + k2)).toUpperCase();
    }

    /** 调试用：返回中间值 K1 / K2，方便和 Python 侧对比 */
    public static String[] intermediates(TreeMap<String, String> params) {
        String rand = params.get("randnum__");
        String ts = params.get("timestamp__");
        if (rand == null) rand = "null";
        if (ts == null) ts = "null";
        String k1 = codebookBase64(utf8(rand + ts));
        String h1 = md5Hex(utf8(k1));
        byte[] k1b = utf8(k1);
        byte[] h1b = utf8(h1);
        byte[] k2raw = new byte[k1b.length];
        for (int i = 0; i < k1b.length; i++) {
            k2raw[i] = (byte) (((k1b[i] & 0xFF) + (h1b[i % 32] & 0xFF)) & 0xFF);
        }
        return new String[]{k1, stdBase64(k2raw)};
    }
}
