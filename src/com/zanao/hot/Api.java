package com.zanao.hot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 赞奥 API 客户端（v1.3 实测版）
 *
 * 实测要点（全部在真接口上验证过）：
 *  - BASE 必须 http:// ，https 服务端不接 TLS（SSLEOFError）
 *  - 最小可用 header 是 8 个（Hb-V / Platform / Device / User-Agent 可缺）
 *  - X-Sc-Version 缺失 → errno 0 但 list 为空（静默失败，必须发）
 *  - alias 必须硬编码 zzu，大小写敏感（ZZU → errno 30 未知的社区）
 *  - /thread/hot 的 count 上限是 10，≥11 回落 3 条
 *  - /thread/hot 的 type 必须非空且 ≠1，否则只回 8 字段（缺 c_count/l_count/img_paths…）
 *  - /thread/info 只传 id 即可，from 可有可无
 *  - /comment/list 只传 id 即可，sign / with_hongbao 可省
 *  - 热榜只有 top 10，无分页
 *  - id 不存在 → errno 0 但 detail 是空对象，必须判空
 */
public final class Api {

    public static final String BASE = "http://api.app.zanao.com";
    public static final String CDN = "http://b1.cdn.zanao.com/";
    public static final String ALIAS = "zzu";
    public static final String VERSION = "3.1.0";
    public static final String NT_V = "310";

    /** 图片必须带这个后缀，裸 URL 一律 403 */
    public static final String IMG_SUFFIX = "@!common";

    /**
     * 缩略图后缀（实测可用）：400px 宽，体积约是 @!common 原图的 1/11。
     * 抽样 25 张：原图 19.4 MB → 缩略图 1.8 MB；单张 3427x4570 1.7 MB → 400x533 59 KB。
     * 只有 @!sm_w400 存在，@!sm_w600 / @!w400 / @!middle 等一律 404。
     */
    public static final String THUMB_SUFFIX = "@!sm_w400";

    private static final Random RND = new Random();

    public String token;

    public Api(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public static String newRandNum() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) sb.append((char) ('0' + RND.nextInt(10)));
        return sb.toString();
    }

    // ---------------------------------------------------------------- HTTP

    private JSONObject request(String path, LinkedHashMap<String, String> params) throws Exception {
        TreeMap<String, String> sign = new TreeMap<>();
        String rand = newRandNum();
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        sign.put("randnum__", rand);
        sign.put("timestamp__", ts);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sign.put(e.getKey(), e.getValue());
        }
        String ah = Sign.make(sign);

        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        String url = BASE + path + (qs.length() > 0 ? "?" + qs : "");

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("X-Sc-Nd", rand);
            c.setRequestProperty("X-Sc-Td", ts);
            c.setRequestProperty("X-Sc-Ah", ah);
            c.setRequestProperty("X-Sc-Nt-V", NT_V);
            c.setRequestProperty("X-Sc-Alias", ALIAS);
            c.setRequestProperty("X-Sc-Client", "app");
            c.setRequestProperty("X-Sc-Token", token);
            c.setRequestProperty("X-Sc-Version", VERSION);
            c.setRequestProperty("X-Sc-Platform", "Android");
            c.setRequestProperty("X-Sc-Device", "00000000-53ef-1f0d-ffff-ffffef05ac4a-345076e6d5406f4f");
            c.setRequestProperty("X-Sc-Hb-V", "210");
            c.setRequestProperty("User-Agent", "okhttp/4.10.0");

            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException2("HTTP " + code + " for " + path);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(bos.toString("UTF-8"));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 简单包装，便于上层区分"网络失败"与"业务错误" */
    public static class IOException2 extends Exception {
        IOException2(String m) { super(m); }
    }

    /**
     * errno → 人话（全部实测过，附处置建议）
     * 这些 errno 都是真接口验证出来的，文档 v1.2 的 errno 表缺 30 且对 10 的描述不全
     */
    public static String errnoText(int errno, String errmsg) {
        switch (errno) {
            case 3:
                return "接口路径不存在（errno 3）：检查 BASE 是否为 http://api.app.zanao.com";
            case 6:
                return "签名校验失败（errno 6）：X-Sc-Nd / X-Sc-Ah / X-Sc-Nt-V 三者任一缺失或算错";
            case 10:
                return "请求被拒（errno 10：" + errmsg + "）\n"
                        + "若是「用户本地网络异常」→ 手机时间不准，X-Sc-Td 必须是当前 unix 秒；\n"
                        + "若是「ID非法」→ thread_id 传空或 0";
            case 20:
                return "未登录（errno 20）：token 失效或为空，去设置页换一个新 token";
            case 30:
                return "未知的社区（errno 30）：X-Sc-Alias 必须精确是 zzu（大小写敏感）";
            default:
                return "errno " + errno + "：" + errmsg;
        }
    }

    /** data 可能是 object 也可能是 array（errno 非 0 时是空数组），统一判类型 */
    public static JSONObject dataObj(JSONObject root) {
        Object d = root.opt("data");
        if (d instanceof JSONObject) return (JSONObject) d;
        return null;
    }

    // ---------------------------------------------------------------- 接口

    /** 热榜 top 10。count 固定 10，type 固定 3（缺一就退化） */
    public JSONArray hot() throws Exception {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("count", "10");
        p.put("type", "3");
        JSONObject r = request("/thread/hot", p);
        if (r.optInt("errno", -1) != 0) {
            throw new IOException2(errnoText(r.optInt("errno"), r.optString("errmsg")));
        }
        JSONObject d = dataObj(r);
        if (d == null) return new JSONArray();
        JSONArray list = d.optJSONArray("list");
        return list == null ? new JSONArray() : list;
    }

    /** 帖子详情。返回 null 表示帖不存在（errno 0 但 detail 为空） */
    public JSONObject info(String threadId) throws Exception {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("id", threadId);
        JSONObject r = request("/thread/info", p);
        if (r.optInt("errno", -1) != 0) {
            throw new IOException2(errnoText(r.optInt("errno"), r.optString("errmsg")));
        }
        JSONObject d = dataObj(r);
        if (d == null) return null;
        JSONObject detail = d.optJSONObject("detail");
        if (detail == null || detail.length() == 0) return null;   // 关键：判空而不是靠 errno
        return detail;
    }

    /**
     * 时间流：按 p_time 严格降序返回该时间点【之前】的 9~10 条。
     *
     * 2026-11-01 实测要点（真接口验证过）：
     *  - /thread/v2/list 已彻底失效：固定返回 {"errno":0,"errmsg":"","data":{"list":[]}}，
     *    参数、版本号、GET/POST、路径变体全试过，一律空列表（服务端行为，不是签名/token 问题）
     *  - 替代接口是 /thread/search：keyword 参数完全无效，它就是个纯时间流
     *  - count 完全无效，传 1/5/20/50/100 都只回 9~10 条
     *  - 游标参数是 end_time（unix 秒）：返回 p_time < end_time 的帖子，所以第一页传当前秒、
     *    后续页传上一页的 min(p_time)。原 from_time 语义是"往更旧翻"，end_time 是"取该时刻之前"
     *  - 有效窗口 ≈ 47 小时：-47h 还有 9 条，-48h 起 total=0 返回空列表（不报错）
     *  - 只有 p_time（unix 秒字符串）是精确时间；post_time 是 '刚刚'/'19:33' 这类相对文本
     *  - item 字段 32 个，比 v2/list 少 is_anon/delete_status/delete_reason_text/check_reason_text
     *  - 没有 hot_rank（热榜是独立榜，与时间流首页 10 帖重叠 0 条）
     *  - 偶发 urlopen timed out，调用方需重试
     */
    public static final long FEED_WINDOW_SEC = 47L * 3600;   // 47h，48h 起服务端归零

    public JSONArray feed(long fromTime) throws Exception {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("count", "10");
        // end_time 缺省时服务器按当前时间返回最新一批；显式传当前秒语义更明确
        p.put("end_time", String.valueOf(fromTime > 0 ? fromTime : System.currentTimeMillis() / 1000L));
        JSONObject r = request("/thread/search", p);
        if (r.optInt("errno", -1) != 0) {
            throw new IOException2(errnoText(r.optInt("errno"), r.optString("errmsg")));
        }
        JSONObject d = dataObj(r);
        if (d == null) return new JSONArray();
        JSONArray list = d.optJSONArray("list");
        return list == null ? new JSONArray() : list;
    }

    /** 全部评论（一次拉完，无分页）。返回 {total, list} */
    public JSONObject comments(String threadId) throws Exception {
        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("id", threadId);
        JSONObject r = request("/comment/list", p);
        if (r.optInt("errno", -1) != 0) {
            throw new IOException2(errnoText(r.optInt("errno"), r.optString("errmsg")));
        }
        JSONObject d = dataObj(r);
        if (d == null) return new JSONObject();
        return d;
    }

    // ---------------------------------------------------------------- 图片

    /** 把 img_paths 里的相对路径拼成可下载的完整 URL（原图 @!common） */
    public static String imgUrl(String path) {
        if (path == null || path.length() == 0) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            // 已带后缀的不再拼（头像就是这种）
            return path;
        }
        return CDN + path + IMG_SUFFIX;
    }

    /**
     * 把任意图片 URL（裸 URL / 带 @!common / 带别的后缀）统一换成缩略图 URL。
     * 落盘与渲染都走这个，保证库里存的都是小图，省存储又省流量。
     */
    public static String thumbUrl(String url) {
        if (url == null || url.length() == 0) return null;
        String base = stripSuffix(url);
        if (base == null || base.length() == 0) return null;
        return base + THUMB_SUFFIX;
    }

    /** 去掉已有的 @!xxx 后缀；没有后缀原样返回 */
    public static String stripSuffix(String url) {
        if (url == null) return null;
        int i = url.indexOf("@!");
        return i < 0 ? url : url.substring(0, i);
    }

    public static byte[] download(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent", "okhttp/4.10.0");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException2("IMG HTTP " + code + " " + url);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
