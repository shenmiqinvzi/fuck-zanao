package com.zanao.hot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 本地存档库（§5 数据模型，按实测字段修正过）
 *
 * 修正点（相对文档 v1.2）：
 *  - 没有 comment_count / like_count，真名 c_count / l_count
 *  - c_count 含已删楼，与 /comment/list 的 total 不等，两个都存
 *  - 评论表加 is_deleted / uid / root_comment_id / reply_comment_id
 *  - 图片存 img_list 的 raw_url（已带 @!common），不用手拼
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "zanao_hot.db";
    private static final int VER = 1;

    public Db(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 每期榜单快照：一期一行，记录 rank 1..10
        db.execSQL("CREATE TABLE rank_snapshot (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " ts INTEGER NOT NULL," +
                " period TEXT NOT NULL," +          // 期号，如 20261031-1530
                " thread_id TEXT NOT NULL," +
                " rank INTEGER NOT NULL," +
                " hot_val TEXT," +
                " view_count INTEGER," +
                " c_count TEXT," +
                " l_count TEXT," +
                " title TEXT," +
                " cate TEXT)");
        db.execSQL("CREATE INDEX idx_snap_period ON rank_snapshot(period, rank)");

        // 帖子主档：只增不减
        db.execSQL("CREATE TABLE threads (" +
                " thread_id TEXT PRIMARY KEY," +
                " first_seen INTEGER NOT NULL," +
                " last_seen INTEGER NOT NULL," +
                " title TEXT," +
                " nickname TEXT," +              // 不是 user_name
                " headimgurl TEXT," +
                " cate_name TEXT," +
                " content TEXT," +
                " pt_time TEXT," +               // str '2026/10/30 16:38'
                " post_time TEXT," +             // 相对时间 str '昨天16:38'
                " view_count INTEGER," +
                " like_num TEXT," +
                " mark_num TEXT," +
                " user_level TEXT," +
                " short_url TEXT," +
                " is_anon INTEGER," +
                " delete_status TEXT," +         // "10" 是正常值！
                " status TEXT," +
                " report_status TEXT," +
                " img_paths TEXT," +             // JSON array 原文
                " detail_json TEXT)");

        // 评论主档：逐条一行（相对电脑端"每帖一行 JSON"是升级）
        db.execSQL("CREATE TABLE comments (" +
                " comment_id TEXT PRIMARY KEY," +
                " thread_id TEXT NOT NULL," +
                " uid TEXT," +
                " root_comment_id TEXT," +       // '0' = 一级；否则指向一级 cid
                " reply_comment_id TEXT," +      // '0' = 一级；否则指向被回复者
                " is_layer INTEGER," +
                " nickname TEXT," +
                " headimgurl TEXT," +
                " content TEXT," +
                " post_time TEXT," +
                " post_time_text TEXT," +
                " like_num TEXT," +
                " is_deleted INTEGER," +         // 永久存档必须标已删楼
                " is_anon INTEGER," +
                " img_urls TEXT," +              // img_list 的 raw_url 拼好逗号分隔
                " raw_json TEXT)");
        db.execSQL("CREATE INDEX idx_c_thread ON comments(thread_id)");

        // 评论增量快照：记录每次抓取时看到的 id 全集，能发现"评论被删"
        db.execSQL("CREATE TABLE comment_snap (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " ts INTEGER NOT NULL," +
                " thread_id TEXT NOT NULL," +
                " total INTEGER," +
                " ids TEXT," +
                " new_ids TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
    }

    // ------------------------------------------------------------ 写入

    /** 返回本期期号，供 UI 用 */
    public String savePeriod(long ts, org.json.JSONArray hot) {
        String period = periodOf(ts);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("rank_snapshot", "period=?", new String[]{period});
            for (int i = 0; i < hot.length(); i++) {
                org.json.JSONObject t = hot.optJSONObject(i);
                if (t == null) continue;
                ContentValues v = new ContentValues();
                v.put("ts", ts);
                v.put("period", period);
                v.put("thread_id", t.optString("thread_id"));
                v.put("rank", t.optInt("hot_rank"));
                v.put("hot_val", t.optString("hot_val"));
                v.put("view_count", t.optInt("view_count"));
                v.put("c_count", t.optString("c_count"));
                v.put("l_count", t.optString("l_count"));
                v.put("title", t.optString("title"));
                v.put("cate", t.optString("cate_name"));
                db.insert("rank_snapshot", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return period;
    }

    public void saveThread(org.json.JSONObject d, long ts) {
        if (d == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        String id = d.optString("thread_id");
        v.put("thread_id", id);
        v.put("last_seen", ts);
        v.put("title", d.optString("title"));
        v.put("nickname", d.optString("nickname"));
        v.put("headimgurl", d.optString("headimgurl"));
        v.put("cate_name", d.optString("cate_name"));
        v.put("content", d.optString("content"));
        v.put("pt_time", d.optString("pt_time"));
        v.put("post_time", d.optString("post_time"));
        v.put("view_count", d.optInt("view_count"));
        v.put("like_num", d.optString("like_num"));
        v.put("mark_num", d.optString("mark_num"));
        v.put("user_level", d.optString("user_level"));
        v.put("short_url", d.optString("short_url"));
        v.put("is_anon", d.optBoolean("is_anon") ? 1 : 0);
        v.put("delete_status", d.optString("delete_status"));
        v.put("status", d.optString("status"));
        v.put("report_status", d.optString("report_status"));
        v.put("img_paths", String.valueOf(d.optJSONArray("img_paths")));
        v.put("detail_json", d.toString());
        int updated = db.update("threads", v, "thread_id=?", new String[]{id});
        if (updated == 0) {
            v.put("first_seen", ts);
            db.insert("threads", null, v);
        }
    }

    /** 写评论（含楼中楼），返回新增条数 */
    public int saveComments(String threadId, org.json.JSONObject data, long ts) {
        org.json.JSONArray list = data.optJSONArray("list");
        int total = data.optInt("total");
        if (list == null) list = new org.json.JSONArray();
        SQLiteDatabase db = getWritableDatabase();
        StringBuilder ids = new StringBuilder();
        StringBuilder newIds = new StringBuilder();
        int added = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String cid = c.optString("comment_id");
                if (cid.length() == 0) continue;   // 空 id 会撞主键，跳过
                if (ids.length() > 0) ids.append(',');
                ids.append(cid);
                if (upsertComment(db, threadId, c, cid)) {
                    added++;
                    if (newIds.length() > 0) newIds.append(',');
                    newIds.append(cid);
                }
                org.json.JSONArray replies = c.optJSONArray("reply_list");
                if (replies != null) {
                    for (int j = 0; j < replies.length(); j++) {
                        org.json.JSONObject r = replies.optJSONObject(j);
                        if (r == null) continue;
                        String rid = r.optString("comment_id");
                        if (rid.length() == 0) continue;
                        if (ids.length() > 0) ids.append(',');
                        ids.append(rid);
                        if (upsertComment(db, threadId, r, rid)) {
                            added++;
                            if (newIds.length() > 0) newIds.append(',');
                            newIds.append(rid);
                        }
                    }
                }
            }
            ContentValues sv = new ContentValues();
            sv.put("ts", ts);
            sv.put("thread_id", threadId);
            sv.put("total", total);
            sv.put("ids", ids.toString());
            sv.put("new_ids", newIds.toString());
            db.insert("comment_snap", null, sv);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    private boolean upsertComment(SQLiteDatabase db, String threadId, org.json.JSONObject c, String cid) {
        ContentValues v = new ContentValues();
        v.put("comment_id", cid);
        v.put("thread_id", threadId);
        v.put("uid", c.optString("uid"));
        v.put("root_comment_id", c.optString("root_comment_id"));
        v.put("reply_comment_id", c.optString("reply_comment_id"));
        v.put("is_layer", c.optBoolean("is_layer") ? 1 : 0);
        v.put("nickname", c.optString("nickname"));
        v.put("headimgurl", c.optString("headimgurl"));
        v.put("content", c.optString("content"));
        v.put("post_time", c.optString("post_time"));
        v.put("post_time_text", c.optString("post_time_text"));
        v.put("like_num", c.optString("like_num"));
        v.put("is_deleted", c.optBoolean("is_deleted") ? 1 : 0);
        v.put("is_anon", c.optBoolean("is_anon") ? 1 : 0);
        v.put("img_urls", rawUrls(c));
        v.put("raw_json", c.toString());
        int updated = db.update("comments", v, "comment_id=?", new String[]{cid});
        if (updated == 0) {
            db.insert("comments", null, v);
            return true;
        }
        return false;
    }

    /** 从 img_list 取现成的 raw_url（已带 @!common）；退化为 img_paths 手拼 */
    static String rawUrls(org.json.JSONObject c) {
        StringBuilder sb = new StringBuilder();
        org.json.JSONArray il = c.optJSONArray("img_list");
        if (il != null) {
            for (int i = 0; i < il.length(); i++) {
                org.json.JSONObject o = il.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("raw_url");
                if (u.length() == 0) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(u);
            }
        }
        if (sb.length() == 0) {
            org.json.JSONArray ip = c.optJSONArray("img_paths");
            if (ip != null) {
                for (int i = 0; i < ip.length(); i++) {
                    String u = Api.imgUrl(ip.optString(i));
                    if (u == null) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(u);
                }
            }
        }
        return sb.toString();
    }

    static String periodOf(long ts) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ts);
        return String.format("%04d%02d%02d-%02d%02d",
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE));
    }

    // ------------------------------------------------------------ 读取

    /** 记录最近一次抓取结果，供首页状态栏显示 */
    public void setLastResult(android.content.Context ctx, long ts, String period, int imgOk, int imgFail) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("zanao", android.content.Context.MODE_PRIVATE);
        sp.edit()
                .putLong("last_ts", ts)
                .putString("last_period", period)
                .putInt("last_img_ok", imgOk)
                .putInt("last_img_fail", imgFail)
                .apply();
    }

    public Cursor latestPeriod() {
        return getReadableDatabase().rawQuery(
                "SELECT period FROM rank_snapshot ORDER BY ts DESC LIMIT 1", null);
    }

    public Cursor periodRows(String period) {
        return getReadableDatabase().rawQuery(
                "SELECT rank, thread_id, title, hot_val, c_count, l_count, view_count, cate " +
                        "FROM rank_snapshot WHERE period=? ORDER BY rank ASC", new String[]{period});
    }

    public Cursor allPeriods() {
        return getReadableDatabase().rawQuery(
                "SELECT period, COUNT(*) AS n, MAX(ts) AS ts FROM rank_snapshot " +
                        "GROUP BY period ORDER BY ts DESC", null);
    }

    public org.json.JSONObject thread(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT detail_json FROM threads WHERE thread_id=?", new String[]{id});        try {
            if (c.moveToFirst()) {
                String j = c.getString(0);
                if (j != null) return new org.json.JSONObject(j);
            }
        } catch (Exception ignore) {
        } finally {
            c.close();
        }
        return null;
    }

    public Cursor commentsOf(String id) {
        return getReadableDatabase().rawQuery(
                "SELECT comment_id, nickname, content, post_time_text, like_num, is_deleted, " +
                        "root_comment_id, reply_comment_id, headimgurl, img_urls " +
                        "FROM comments WHERE thread_id=? " +
                        // 一级评论 root='0'，用自身 cid 排序；楼中楼用 root 挂到一级下面
                        "ORDER BY CAST(CASE WHEN root_comment_id='0' THEN comment_id ELSE root_comment_id END AS INTEGER) ASC, " +
                        "is_layer ASC, CAST(comment_id AS INTEGER) ASC",
                new String[]{id});
    }
}
