package com.zanao.hot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地存档库 v2 —— "只存一份 + 版本化" 模型
 *
 * 相对 v1（每期快照）的核心变化：
 *  1) 不再按期存榜。threads 以 thread_id 为主键，一条帖子只有一行。
 *  2) 正文变了才留旧版：thread_detail_snap（只在 content 的 md5 变化时写一行）。
 *  3) 评论内容变了才留旧版：comment_content_snap（被删时原话不再丢失）。
 *  4) 名次只在变化时记一行：rank_event（v1 是每期固定写 10 行）。
 *  5) 图片按内容 md5 落盘，url → 内容 的映射存 img_ref，同图不再存多份。
 *  6) 时间统一归一化成 unix 秒 posted_at（相对时间 '昨天16:38' 无法排序）。
 *
 * 实测依据（真接口验证过）：
 *  - 评论变化率约 1/290，且一旦变化基本就是"此内容已被用户自行删除"
 *  - 帖子正文抽样 19/20 完全未变
 *  - 名次四期里只变两次（2,2,2,1 / 5,5,4,2 / 6,6,5,3）
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "zanao_hot.db";
    private static final int VER = 3;

    public Db(Context c) {
        super(c, NAME, null, VER);
    }

    // ------------------------------------------------------------ 建表

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV2(db);
    }

    private void createV2(SQLiteDatabase db) {
        // 帖子主档：一条帖一行，永不去重丢失
        db.execSQL("CREATE TABLE IF NOT EXISTS threads (" +
                " thread_id TEXT PRIMARY KEY," +
                " posted_at INTEGER," +            // unix 秒，排序唯一依据
                " first_seen INTEGER NOT NULL," +
                " last_seen INTEGER NOT NULL," +
                " title TEXT," +
                " nickname TEXT," +                // 不是 user_name
                " headimgurl TEXT," +
                " cate_name TEXT," +
                " content TEXT," +
                " content_md5 TEXT," +             // 变化检测用
                " pt_time TEXT," +                 // '2026/10/30 16:38'
                " view_count INTEGER," +
                " like_num TEXT," +
                " mark_num TEXT," +
                " c_count TEXT," +                 // 评论数（含已删楼）
                " l_count TEXT," +                 // 点赞数（不是评论数！）
                " user_level TEXT," +
                " short_url TEXT," +
                " is_anon INTEGER," +
                " delete_reason_text TEXT," +      // 有内容才是真被删；delete_status='10' 是正常
                " check_reason_text TEXT," +
                " detail_json TEXT," +
                " has_detail INTEGER DEFAULT 0," +
                " comment_fetched_ts INTEGER," +
                " comment_total INTEGER," +
                " hot_rank INTEGER," +             // 当前/最近名次
                " hot_val TEXT," +
                " hot_seen_ts INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_t_posted ON threads(posted_at DESC)");

        // 正文历史版本：只在 content 变化时写一行（实测变化率 ~0）
        db.execSQL("CREATE TABLE IF NOT EXISTS thread_detail_snap (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " thread_id TEXT NOT NULL," +
                " ts INTEGER NOT NULL," +
                " content_md5 TEXT," +
                " title TEXT," +
                " content TEXT," +
                " detail_json TEXT," +
                " reason TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tds ON thread_detail_snap(thread_id, ts DESC)");

        // 评论主档：逐条一行，只增不删
        db.execSQL("CREATE TABLE IF NOT EXISTS comments (" +
                " comment_id TEXT PRIMARY KEY," +
                " thread_id TEXT NOT NULL," +
                " uid TEXT," +
                " root_comment_id TEXT," +         // '0' = 一级；否则指向一级 cid
                " reply_comment_id TEXT," +        // '0' = 一级；否则指向被回复者
                " is_layer INTEGER," +
                " nickname TEXT," +
                " headimgurl TEXT," +
                " content TEXT," +
                " content_md5 TEXT," +
                " post_time TEXT," +
                " post_time_text TEXT," +
                " like_num TEXT," +
                " is_deleted INTEGER," +
                " is_anon INTEGER," +
                " img_urls TEXT," +
                " first_seen INTEGER," +
                " last_seen INTEGER," +
                " raw_json TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_c_thread ON comments(thread_id)");

        // 评论内容历史：只在 content 变化时写旧值 —— 这是"防下架"的关键
        db.execSQL("CREATE TABLE IF NOT EXISTS comment_content_snap (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " comment_id TEXT NOT NULL," +
                " thread_id TEXT NOT NULL," +
                " ts INTEGER NOT NULL," +
                " content TEXT," +
                " is_deleted INTEGER," +
                " raw_json TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ccs ON comment_content_snap(comment_id, ts DESC)");

        // 名次事件：只在名次变化时写一行
        db.execSQL("CREATE TABLE IF NOT EXISTS rank_event (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " ts INTEGER NOT NULL," +
                " thread_id TEXT NOT NULL," +
                " rank INTEGER," +
                " hot_val TEXT," +
                " view_count INTEGER," +
                " c_count TEXT," +
                " l_count TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_re ON rank_event(thread_id, ts DESC)");

        // 图片：url → 内容 md5。文件按内容 md5 命名，同图只存一份
        db.execSQL("CREATE TABLE IF NOT EXISTS img_ref (" +
                " url TEXT PRIMARY KEY," +
                " content_md5 TEXT," +
                " bytes INTEGER," +
                " first_seen INTEGER)");

        // 抓取日志：一行一次，用于"上次抓取距今 X 小时"和缺口判断
        db.execSQL("CREATE TABLE IF NOT EXISTS fetch_log (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " ts INTEGER NOT NULL," +
                " pages INTEGER," +
                " new_threads INTEGER," +
                " new_comments INTEGER," +
                " changed_comments INTEGER," +
                " img_ok INTEGER," +
                " img_fail INTEGER," +
                " oldest_posted_at INTEGER," +
                " note TEXT)");

        db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) {
            migrateV1toV2(db);
        }
        if (o < 3) {
            migrateV2toV3(db);
        }
    }

    /**
     * v2 → v3：修 v2 的致命 bug —— saveFeedPage / saveThreadDetail 的 ContentValues
     * 漏写 thread_id，而 SQLite 的 TEXT PRIMARY KEY 允许 NULL，于是每帖每轮都会落
     * 两行孤儿（一行 feed 摘要、一行 detail），首页因此出现重复行 + 「未取正文」。
     *
     * detail_json 里有 thread_id，可以把 detail 孤儿救回来；纯 feed 孤儿没有任何
     * 稳定标识（title 可能为空），只能丢。
     */
    private void migrateV2toV3(SQLiteDatabase db) {
        java.util.ArrayList<Object[]> orphans = new java.util.ArrayList<Object[]>();
        Cursor c = db.rawQuery(
                "SELECT rowid, detail_json FROM threads" +
                        " WHERE thread_id IS NULL AND detail_json IS NOT NULL", null);
        try {
            while (c.moveToNext()) {
                orphans.add(new Object[]{Long.valueOf(c.getLong(0)), c.getString(1)});
            }
        } finally {
            c.close();
        }

        for (Object[] o : orphans) {
            String rowid = String.valueOf(o[0]);
            String j = (String) o[1];
            String tid = null;
            try {
                tid = new JSONObject(j).optString("thread_id");
            } catch (Exception ignore) {
            }
            if (tid == null || tid.length() == 0) continue;

            boolean exists = false;
            Cursor e = db.rawQuery("SELECT rowid FROM threads WHERE thread_id=?",
                    new String[]{tid});
            try {
                exists = e.moveToFirst();
            } finally {
                e.close();
            }
            if (exists) {
                // 正规行已经是最新，孤儿直接删
                db.delete("threads", "rowid=?", new String[]{rowid});
            } else {
                ContentValues v = new ContentValues();
                v.put("thread_id", tid);
                db.update("threads", v, "rowid=?", new String[]{rowid});
            }
        }

        // 剩下的都是没有 thread_id 的 feed 摘要行，无标识可还原
        db.execSQL("DELETE FROM threads WHERE thread_id IS NULL");
    }

    /** v1（每期快照）→ v2（一条帖一行 + 版本化）。尽量保留旧数据 */
    private void migrateV1toV2(SQLiteDatabase db) {
        // 0) 旧表已存在，createV2 的 IF NOT EXISTS 不会补列，这里先 ALTER 补齐
        ensureColumns(db);
        createV2(db);
        long ts = System.currentTimeMillis();

        // 1) 帖子：补 posted_at / content_md5 / c_count / l_count
        Cursor c = db.rawQuery("SELECT thread_id, first_seen, title, nickname, headimgurl," +
                " cate_name, content, pt_time, view_count, like_num, mark_num, user_level," +
                " short_url, is_anon, detail_json FROM threads", null);
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String content = c.isNull(6) ? "" : c.getString(6);
                ContentValues v = new ContentValues();
                v.put("posted_at", parsePtTime(c.isNull(7) ? "" : c.getString(7)));
                v.put("content_md5", md5Hex(content));
                v.put("has_detail", c.isNull(14) ? 0 : 1);
                v.put("last_seen", c.isNull(1) ? ts : c.getLong(1));
                v.put("first_seen", c.isNull(1) ? ts : c.getLong(1));
                db.update("threads", v, "thread_id=?", new String[]{id});
            }
        } catch (Exception ignore) {
        } finally {
            c.close();
        }

        // 2) 评论：补 content_md5 / first_seen / last_seen
        c = db.rawQuery("SELECT comment_id, content FROM comments", null);
        try {
            while (c.moveToNext()) {
                String id = c.getString(0);
                String content = c.isNull(1) ? "" : c.getString(1);
                ContentValues v = new ContentValues();
                v.put("content_md5", md5Hex(content));
                v.put("first_seen", ts);
                v.put("last_seen", ts);
                db.update("comments", v, "comment_id=?", new String[]{id});
            }
        } catch (Exception ignore) {
        } finally {
            c.close();
        }

        // 3) 旧 rank_snapshot → rank_event，同帖连续同名次合并成一条
        java.util.HashMap<String, Integer> last = new java.util.HashMap<String, Integer>();
        c = db.rawQuery("SELECT ts, thread_id, rank, hot_val, view_count, c_count, l_count" +
                " FROM rank_snapshot ORDER BY ts ASC", null);
        try {
            while (c.moveToNext()) {
                String tid = c.getString(1);
                Integer r = c.isNull(2) ? null : c.getInt(2);
                if (r == null) continue;
                Integer prev = last.get(tid);
                if (prev != null && prev.intValue() == r.intValue()) continue;
                last.put(tid, r);
                ContentValues v = new ContentValues();
                v.put("ts", c.getLong(0));
                v.put("thread_id", tid);
                v.put("rank", r);
                v.put("hot_val", c.isNull(3) ? null : c.getString(3));
                v.put("view_count", c.isNull(4) ? 0 : c.getInt(4));
                v.put("c_count", c.isNull(5) ? null : c.getString(5));
                v.put("l_count", c.isNull(6) ? null : c.getString(6));
                db.insert("rank_event", null, v);
                ContentValues tv = new ContentValues();
                tv.put("hot_rank", r);
                tv.put("hot_seen_ts", c.getLong(0));
                db.update("threads", tv, "thread_id=?", new String[]{tid});
            }
        } catch (Exception ignore) {
        } finally {
            c.close();
        }

        // 4) 丢掉旧表（每期快照的冗余部分）
        db.execSQL("DROP TABLE IF EXISTS rank_snapshot");
        db.execSQL("DROP TABLE IF EXISTS comment_snap");
    }

    /** 给已存在的 v1 表补上 v2 新增的列。列已存在 / 表不存在都吞掉 */
    private void ensureColumns(SQLiteDatabase db) {
        String[][] add = new String[][]{
                {"threads", "posted_at", "INTEGER"},
                {"threads", "content_md5", "TEXT"},
                {"threads", "c_count", "TEXT"},
                {"threads", "l_count", "TEXT"},
                {"threads", "delete_reason_text", "TEXT"},
                {"threads", "check_reason_text", "TEXT"},
                {"threads", "has_detail", "INTEGER DEFAULT 0"},
                {"threads", "comment_fetched_ts", "INTEGER"},
                {"threads", "comment_total", "INTEGER"},
                {"threads", "hot_rank", "INTEGER"},
                {"threads", "hot_val", "TEXT"},
                {"threads", "hot_seen_ts", "INTEGER"},
                {"comments", "content_md5", "TEXT"},
                {"comments", "first_seen", "INTEGER"},
                {"comments", "last_seen", "INTEGER"},
        };
        for (String[] a : add) {
            try {
                db.execSQL("ALTER TABLE " + a[0] + " ADD COLUMN " + a[1] + " " + a[2]);
            } catch (Exception ignore) {
            }
        }
    }

    // ------------------------------------------------------------ 工具

    static String md5Hex(String s) {
        if (s == null) s = "";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** pt_time '2026/10/30 16:38' → unix 秒；解析失败返回 0 */
    static long parsePtTime(String pt) {
        if (pt == null || pt.length() < 16) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US);
            f.setLenient(true);
            return f.parse(pt.trim()).getTime() / 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    static int toInt(String s, int def) {
        if (s == null || s.length() == 0) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------ meta

    public String meta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k});
        try {
            if (c.moveToFirst()) {
                String v = c.getString(0);
                return v == null ? def : v;
            }
        } finally {
            c.close();
        }
        return def;
    }

    public long metaLong(String k, long def) {
        String v = meta(k, null);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ------------------------------------------------------------ 写入：榜单

    /**
     * 记录热榜名次。只在名次变化时写一行 rank_event。
     * 返回本期变化条数。
     */
    public int saveRankEvents(long ts, JSONArray hot) {
        int changed = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < hot.length(); i++) {
                JSONObject t = hot.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("thread_id");
                if (tid.length() == 0) continue;
                int rank = t.optInt("hot_rank");
                String hv = t.optString("hot_val");

                int prevRank = -1;
                Cursor c = db.rawQuery(
                        "SELECT rank FROM rank_event WHERE thread_id=? ORDER BY id DESC LIMIT 1",
                        new String[]{tid});
                try {
                    if (c.moveToFirst()) prevRank = c.getInt(0);
                } finally {
                    c.close();
                }
                if (prevRank != rank) {
                    ContentValues v = new ContentValues();
                    v.put("ts", ts);
                    v.put("thread_id", tid);
                    v.put("rank", rank);
                    v.put("hot_val", hv);
                    v.put("view_count", t.optInt("view_count"));
                    v.put("c_count", t.optString("c_count"));
                    v.put("l_count", t.optString("l_count"));
                    db.insert("rank_event", null, v);
                    changed++;
                }
                // 帖子上顺带更新当前名次（列表角标用）
                ContentValues tv = new ContentValues();
                tv.put("hot_rank", rank);
                tv.put("hot_val", hv);
                tv.put("hot_seen_ts", ts);
                db.update("threads", tv, "thread_id=?", new String[]{tid});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return changed;
    }

    // ------------------------------------------------------------ 写入：帖子

    /** 一页时间流的结果 */
    public static class FeedResult {
        public final List<String> added = new ArrayList<String>();          // 全新帖：要拉详情+评论
        public final List<String> needComments = new ArrayList<String>();   // 旧帖但评论数涨了
        public final List<String> needDetail = new ArrayList<String>();     // 旧帖但上次 info 没拉成
        public int updated = 0;
        public int contentChanged = 0;
        public int count = 0;
        public long minPostedAt = Long.MAX_VALUE;
        public long maxPostedAt = 0;
    }

    /**
     * 落一页 /thread/search（时间流）的结果。
     * 关键：同一个 thread_id 只保留一行，不再按期复制。
     */
    public FeedResult saveFeedPage(long ts, JSONArray list) {
        FeedResult r = new FeedResult();
        if (list == null) return r;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject t = list.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("thread_id");
                if (tid.length() == 0) continue;
                r.count++;
                long pt = 0;
                try {
                    pt = Long.parseLong(t.optString("p_time").trim());
                } catch (Exception ignore) {
                }
                if (pt > 0 && pt < r.minPostedAt) r.minPostedAt = pt;
                if (pt > r.maxPostedAt) r.maxPostedAt = pt;

                String title = t.optString("title");
                String content = t.optString("content");
                String cCount = t.optString("c_count");
                String lCount = t.optString("l_count");

                // 读旧行
                String oldContent = null;
                String oldMd5 = null;
                String oldTitle = null;
                String oldDetail = null;
                String oldCCount = null;
                int oldHasDetail = 0;
                boolean exists = false;
                Cursor c = db.rawQuery("SELECT content, content_md5, title, detail_json, c_count," +
                        " has_detail FROM threads WHERE thread_id=?", new String[]{tid});
                try {
                    if (c.moveToFirst()) {
                        exists = true;
                        oldContent = c.isNull(0) ? null : c.getString(0);
                        oldMd5 = c.isNull(1) ? null : c.getString(1);
                        oldTitle = c.isNull(2) ? null : c.getString(2);
                        oldDetail = c.isNull(3) ? null : c.getString(3);
                        oldCCount = c.isNull(4) ? null : c.getString(4);
                        oldHasDetail = c.getInt(5);
                    }
                } finally {
                    c.close();
                }

                ContentValues v = new ContentValues();
                v.put("thread_id", tid);
                v.put("last_seen", ts);
                // 纯图帖的 title 在源数据里就是空串：别用空串冲掉 info 拉到的标题
                if (title.length() > 0 || !exists) v.put("title", title);
                v.put("nickname", t.optString("nickname"));
                v.put("headimgurl", t.optString("headimgurl"));
                v.put("cate_name", t.optString("cate_name"));
                v.put("view_count", t.optInt("view_count"));
                v.put("c_count", cCount);
                v.put("l_count", lCount);
                v.put("user_level", t.optString("user_level"));
                // /thread/search 的 item 不带 is_anon（v2/list 才带）：字段缺失时别把旧值冲成 0
                if (t.has("is_anon")) {
                    v.put("is_anon", t.optBoolean("is_anon") ? 1 : 0);
                }
                if (pt > 0) v.put("posted_at", pt);

                // 正文：只在非空且确实变了时才写，并先留旧版
                if (content != null && content.length() > 0) {
                    String newMd5 = md5Hex(content);
                    if (exists && oldMd5 != null && !newMd5.equals(oldMd5)
                            && oldContent != null && oldContent.length() > 0) {
                        ContentValues sv = new ContentValues();
                        sv.put("thread_id", tid);
                        sv.put("ts", ts);
                        sv.put("content_md5", oldMd5);
                        sv.put("title", oldTitle);
                        sv.put("content", oldContent);
                        sv.put("detail_json", oldDetail);
                        sv.put("reason", "feed");
                        db.insert("thread_detail_snap", null, sv);
                        r.contentChanged++;
                    }
                    v.put("content", content);
                    v.put("content_md5", newMd5);
                }

                if (!exists) {
                    v.put("first_seen", ts);
                    db.insert("threads", null, v);
                    r.added.add(tid);
                } else {
                    db.update("threads", v, "thread_id=?", new String[]{tid});
                    r.updated++;
                    // 评论数涨了（或之前没拉过）才需要重拉评论
                    int oldC = toInt(oldCCount, -1);
                    int newC = toInt(cCount, -1);
                    if (oldC < 0 || (newC >= 0 && newC != oldC)) r.needComments.add(tid);
                    // 老帖但从没成功拉到正文 → 补一次 info
                    if (oldHasDetail == 0) r.needDetail.add(tid);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return r;
    }

    /** 落帖子详情（/thread/info）。正文变了就留旧版 */
    public boolean saveThreadDetail(long ts, JSONObject d) {
        if (d == null) return false;
        String id = d.optString("thread_id");
        if (id.length() == 0) return false;
        SQLiteDatabase db = getWritableDatabase();

        String oldContent = null;
        String oldMd5 = null;
        String oldTitle = null;
        String oldDetail = null;
        boolean exists = false;
        Cursor c = db.rawQuery("SELECT content, content_md5, title, detail_json FROM threads" +
                " WHERE thread_id=?", new String[]{id});
        try {
            if (c.moveToFirst()) {
                exists = true;
                oldContent = c.isNull(0) ? null : c.getString(0);
                oldMd5 = c.isNull(1) ? null : c.getString(1);
                oldTitle = c.isNull(2) ? null : c.getString(2);
                oldDetail = c.isNull(3) ? null : c.getString(3);
            }
        } finally {
            c.close();
        }

        String content = d.optString("content");
        String newMd5 = md5Hex(content);
        boolean changed = false;
        if (exists && oldMd5 != null && !newMd5.equals(oldMd5)
                && oldContent != null && oldContent.length() > 0
                && content.length() > 0) {
            ContentValues sv = new ContentValues();
            sv.put("thread_id", id);
            sv.put("ts", ts);
            sv.put("content_md5", oldMd5);
            sv.put("title", oldTitle);
            sv.put("content", oldContent);
            sv.put("detail_json", oldDetail);
            sv.put("reason", "info");
            db.insert("thread_detail_snap", null, sv);
            changed = true;
        }

        ContentValues v = new ContentValues();
        v.put("thread_id", id);
        v.put("last_seen", ts);
        v.put("title", d.optString("title"));
        v.put("nickname", d.optString("nickname"));
        v.put("headimgurl", d.optString("headimgurl"));
        v.put("cate_name", d.optString("cate_name"));
        v.put("content", content);
        v.put("content_md5", newMd5);
        v.put("pt_time", d.optString("pt_time"));
        v.put("view_count", d.optInt("view_count"));
        v.put("like_num", d.optString("like_num"));
        v.put("mark_num", d.optString("mark_num"));
        v.put("user_level", d.optString("user_level"));
        v.put("short_url", d.optString("short_url"));
        v.put("is_anon", d.optBoolean("is_anon") ? 1 : 0);
        v.put("delete_reason_text", d.optString("delete_reason_text"));
        v.put("check_reason_text", d.optString("check_reason_text"));
        v.put("detail_json", d.toString());
        v.put("has_detail", 1);
        long pt = parsePtTime(d.optString("pt_time"));
        if (pt > 0) v.put("posted_at", pt);

        if (!exists) {
            v.put("first_seen", ts);
            db.insert("threads", null, v);
        } else {
            db.update("threads", v, "thread_id=?", new String[]{id});
        }
        return changed;
    }

    // ------------------------------------------------------------ 写入：评论

    /** 评论落库结果 */
    public static class CommentResult {
        public int added = 0;
        public int changed = 0;
        public int total = 0;
    }

    /**
     * 落一帖的全部评论（含楼中楼）。
     * 关键：content 变了就先把旧值写进 comment_content_snap，再覆盖主表。
     * 这样"此内容已被用户自行删除"不会把原话顶掉。
     */
    public CommentResult saveComments(String threadId, JSONObject data, long ts) {
        CommentResult r = new CommentResult();
        if (data == null) return r;
        JSONArray list = data.optJSONArray("list");
        int total = data.optInt("total");
        r.total = total;
        if (list == null) list = new JSONArray();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                String cid = c.optString("comment_id");
                if (cid.length() == 0) continue;
                if (upsertComment(db, threadId, c, cid, ts, r)) r.added++;
                JSONArray replies = c.optJSONArray("reply_list");
                if (replies != null) {
                    for (int j = 0; j < replies.length(); j++) {
                        JSONObject ro = replies.optJSONObject(j);
                        if (ro == null) continue;
                        String rid = ro.optString("comment_id");
                        if (rid.length() == 0) continue;
                        if (upsertComment(db, threadId, ro, rid, ts, r)) r.added++;
                    }
                }
            }
            ContentValues tv = new ContentValues();
            tv.put("comment_fetched_ts", ts);
            tv.put("comment_total", total);
            db.update("threads", tv, "thread_id=?", new String[]{threadId});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return r;
    }

    /** 返回 true 表示是新评论 */
    private boolean upsertComment(SQLiteDatabase db, String threadId, JSONObject c,
                                  String cid, long ts, CommentResult r) {
        String content = c.optString("content");
        String newMd5 = md5Hex(content);
        int isDel = c.optBoolean("is_deleted") ? 1 : 0;

        String oldContent = null;
        String oldMd5 = null;
        boolean exists = false;
        Cursor cur = db.rawQuery("SELECT content, content_md5 FROM comments WHERE comment_id=?",
                new String[]{cid});
        try {
            if (cur.moveToFirst()) {
                exists = true;
                oldContent = cur.isNull(0) ? null : cur.getString(0);
                oldMd5 = cur.isNull(1) ? null : cur.getString(1);
            }
        } finally {
            cur.close();
        }

        // 内容变了 → 先留旧版
        if (exists && oldMd5 != null && !newMd5.equals(oldMd5)
                && oldContent != null && oldContent.length() > 0) {
            ContentValues sv = new ContentValues();
            sv.put("comment_id", cid);
            sv.put("thread_id", threadId);
            sv.put("ts", ts);
            sv.put("content", oldContent);
            sv.put("is_deleted", 0);
            sv.put("raw_json", (String) null);
            db.insert("comment_content_snap", null, sv);
            r.changed++;
        }

        ContentValues v = new ContentValues();
        v.put("comment_id", cid);
        v.put("thread_id", threadId);
        v.put("uid", c.optString("uid"));
        v.put("root_comment_id", c.optString("root_comment_id"));
        v.put("reply_comment_id", c.optString("reply_comment_id"));
        v.put("is_layer", c.optBoolean("is_layer") ? 1 : 0);
        v.put("nickname", c.optString("nickname"));
        v.put("headimgurl", c.optString("headimgurl"));
        v.put("content", content);
        v.put("content_md5", newMd5);
        v.put("post_time", c.optString("post_time"));
        v.put("post_time_text", c.optString("post_time_text"));
        v.put("like_num", c.optString("like_num"));
        v.put("is_deleted", isDel);
        v.put("is_anon", c.optBoolean("is_anon") ? 1 : 0);
        v.put("img_urls", rawUrls(c));
        v.put("raw_json", c.toString());
        v.put("last_seen", ts);

        if (!exists) {
            v.put("first_seen", ts);
            db.insert("comments", null, v);
            return true;
        }
        db.update("comments", v, "comment_id=?", new String[]{cid});
        return false;
    }

    /** 从 img_list 取现成的 raw_url（已带 @!common）；退化为 img_paths 手拼 */
    static String rawUrls(JSONObject c) {
        StringBuilder sb = new StringBuilder();
        JSONArray il = c.optJSONArray("img_list");
        if (il != null) {
            for (int i = 0; i < il.length(); i++) {
                JSONObject o = il.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("raw_url");
                if (u.length() == 0) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(u);
            }
        }
        if (sb.length() == 0) {
            JSONArray ip = c.optJSONArray("img_paths");
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

    // ------------------------------------------------------------ 抓取日志

    public void logFetch(long ts, int pages, int newThreads, int newComments,
                         int changedComments, int imgOk, int imgFail, long oldest, String note) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("pages", pages);
        v.put("new_threads", newThreads);
        v.put("new_comments", newComments);
        v.put("changed_comments", changedComments);
        v.put("img_ok", imgOk);
        v.put("img_fail", imgFail);
        v.put("oldest_posted_at", oldest);
        v.put("note", note);
        getWritableDatabase().insert("fetch_log", null, v);
        setMeta("last_fetch_ts", String.valueOf(ts));
    }

    public void setLastResult(Context ctx, long ts, String period, int imgOk, int imgFail) {
        android.content.SharedPreferences sp =
                ctx.getSharedPreferences("zanao", Context.MODE_PRIVATE);
        sp.edit()
                .putLong("last_ts", ts)
                .putString("last_period", period)
                .putInt("last_img_ok", imgOk)
                .putInt("last_img_fail", imgFail)
                .apply();
    }

    // ------------------------------------------------------------ 读取

    public int countThreads() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM threads", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    public int countComments() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM comments", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    public int countCommentHistory() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM comment_content_snap", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    /** 首页：按发布时间倒序。beforePostedAt=0 表示第一页 */
    public Cursor feedThreads(long beforePostedAt, int limit) {
        if (beforePostedAt <= 0) {
            return getReadableDatabase().rawQuery(
                    "SELECT thread_id, posted_at, title, cate_name, c_count, l_count," +
                            " view_count, hot_rank, content, nickname, has_detail" +
                            " FROM threads ORDER BY posted_at DESC, thread_id DESC LIMIT ?",
                    new String[]{String.valueOf(limit)});
        }
        return getReadableDatabase().rawQuery(
                "SELECT thread_id, posted_at, title, cate_name, c_count, l_count," +
                        " view_count, hot_rank, content, nickname, has_detail" +
                        " FROM threads WHERE posted_at < ? " +
                        "ORDER BY posted_at DESC, thread_id DESC LIMIT ?",
                new String[]{String.valueOf(beforePostedAt), String.valueOf(limit)});
    }

    public JSONObject thread(String id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT detail_json FROM threads WHERE thread_id=?", new String[]{id});
        try {
            if (c.moveToFirst()) {
                String j = c.getString(0);
                if (j != null) return new JSONObject(j);
            }
        } catch (Exception ignore) {
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * 帖子完整视图：以 threads 表列为准（v2 里这些列是最新值），
     * 再把 detail_json 里的额外字段并进来（img_paths / short_url / pt_time 等只在 detail 里有）。
     */
    public JSONObject threadFull(String id) {
        JSONObject out = new JSONObject();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT thread_id, posted_at, first_seen, last_seen, title, nickname, headimgurl," +
                        " cate_name, content, pt_time, view_count, like_num, mark_num, c_count," +
                        " l_count, user_level, short_url, is_anon, delete_reason_text," +
                        " check_reason_text, detail_json, has_detail, comment_total, hot_rank, hot_val" +
                        " FROM threads WHERE thread_id=?", new String[]{id});
        try {
            if (!c.moveToFirst()) return null;
            try {
                out.put("thread_id", c.getString(0));
                out.put("posted_at", c.getLong(1));
                out.put("first_seen", c.getLong(2));
                out.put("last_seen", c.getLong(3));
                out.put("title", c.isNull(4) ? "" : c.getString(4));
                out.put("nickname", c.isNull(5) ? "" : c.getString(5));
                out.put("headimgurl", c.isNull(6) ? "" : c.getString(6));
                out.put("cate_name", c.isNull(7) ? "" : c.getString(7));
                out.put("content", c.isNull(8) ? "" : c.getString(8));
                out.put("pt_time", c.isNull(9) ? "" : c.getString(9));
                out.put("view_count", c.getLong(10));
                out.put("like_num", c.isNull(11) ? "" : c.getString(11));
                out.put("mark_num", c.isNull(12) ? "" : c.getString(12));
                out.put("c_count", c.isNull(13) ? "" : c.getString(13));
                out.put("l_count", c.isNull(14) ? "" : c.getString(14));
                out.put("user_level", c.isNull(15) ? "" : c.getString(15));
                out.put("short_url", c.isNull(16) ? "" : c.getString(16));
                out.put("is_anon", c.getInt(17) == 1);
                out.put("delete_reason_text", c.isNull(18) ? "" : c.getString(18));
                out.put("check_reason_text", c.isNull(19) ? "" : c.getString(19));
                out.put("has_detail", c.getInt(21));
                out.put("comment_total", c.getLong(22));
                out.put("hot_rank", c.getInt(23));
                out.put("hot_val", c.isNull(24) ? "" : c.getString(24));
                String dj = c.isNull(20) ? null : c.getString(20);
                if (dj != null) {
                    try {
                        JSONObject d = new JSONObject(dj);
                        java.util.Iterator<String> it = d.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (out.has(k)) continue;
                            out.put(k, d.opt(k));
                        }
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                return null;
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 帖子正文历史版本（变了才有） */
    public Cursor detailHistory(String id) {
        return getReadableDatabase().rawQuery(
                "SELECT ts, title, content FROM thread_detail_snap" +
                        " WHERE thread_id=? ORDER BY ts DESC", new String[]{id});
    }

    public Cursor commentsOf(String id) {
        return getReadableDatabase().rawQuery(
                "SELECT comment_id, nickname, content, post_time_text, like_num, is_deleted, " +
                        "root_comment_id, reply_comment_id, headimgurl, img_urls, content_md5 " +
                        "FROM comments WHERE thread_id=? " +
                        "ORDER BY CAST(CASE WHEN root_comment_id='0' THEN comment_id ELSE root_comment_id END AS INTEGER) ASC, " +
                        "is_layer ASC, CAST(comment_id AS INTEGER) ASC",
                new String[]{id});
    }

    /** 某条评论的正文历史（被删前的原话在这里） */
    public Cursor commentHistory(String commentId) {
        return getReadableDatabase().rawQuery(
                "SELECT ts, content FROM comment_content_snap" +
                        " WHERE comment_id=? ORDER BY ts DESC", new String[]{commentId});
    }

    /** 某帖名次轨迹 */
    public Cursor rankHistory(String id) {
        return getReadableDatabase().rawQuery(
                "SELECT ts, rank, hot_val FROM rank_event" +
                        " WHERE thread_id=? ORDER BY ts ASC", new String[]{id});
    }

    public static String periodOf(long ts) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ts);
        return String.format(Locale.US, "%04d%02d%02d-%02d%02d",
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE));
    }
}
