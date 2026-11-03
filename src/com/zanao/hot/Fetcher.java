package com.zanao.hot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 抓取流水线 v5 —— 热榜优先 + 掉榜 24h 追踪。
 *
 * 一轮的动作：
 *
 *  1) 拉 /thread/hot（top 10）。热榜条目自带 content 全文、img_paths、c_count、l_count、
 *     view_count、hot_text、hot_rank —— 本身就是完整详情，不需要再打 /thread/info
 *     （那个接口 2026-11-01 起已降级成 detail:[]）。
 *  2) 记名次：rank_event 只在名次变化时写一行（详情页名次轨迹用）；
 *     在榜帖回写 threads.hot_rank/hot_seen_ts/last_in_list_ts。
 *     v4 的期头 hot_period 与每帖一行 rank_snapshot 已停写（v5 迁移时清空）。
 *  3) 在榜帖：正文落 threads、评论全量重拉、帖图与评论图下载（缩略化）。
 *  4) v5 新增：掉榜追踪——上过榜、掉榜未满 24h、本轮不在榜的帖子，
 *     掉榜最老的优先补 5 个，逐个拉评论+更新计数。回榜自动并回主流程，
 *     24h 没回就停止追踪，数据停在最后状态。
 *
 * 实测约束（2026-11-01 复核）：
 *  - /thread/hot?count=10 稳定回 10 条；count 超过 10 会回落 3 条，所以固定 count=10
 *  - 热榜 top10 变化慢（几分钟到几小时才动一次）→ 15~30 分钟一轮足够，不会漏帖
 *  - /thread/info、/thread/search、/thread/v2/list、/thread/list 全部已降级（空）
 *  - /thread/new/list?type=1 仍活但只有最新 10 条、不可翻页 —— 本版不再拿它当主源
 *  - 评论只增不减，变化基本只有"此内容已被用户自行删除"
 */
public final class Fetcher {

    private Fetcher() {
    }

    /** 热榜接口失败时的重试次数（偶发超时） */
    private static final int HOT_RETRY = 3;

    /**
     * 防并发：连点两次「刷新」会起两个 runOnce，两轮同时往同一个库写。
     * 用进程内闸门挡住第二轮。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 手动刷新 / 定时任务：v4 里两条路径做同一件事（一轮热榜就够了） */
    public static String runOnce(Context ctx, String token) throws Exception {
        return runOnce(ctx, token, false);
    }

    public static String runScheduled(Context ctx, String token) throws Exception {
        return runOnce(ctx, token, true);
    }

    private static String runOnce(Context ctx, String token, boolean deep) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("上一轮抓取还没结束，等它跑完再点");
        }
        try {
            return runOnceInner(ctx, token, deep);
        } finally {
            RUNNING.set(false);
        }
    }

    private static String runOnceInner(Context ctx, String token, boolean deep) throws Exception {
        Api api = new Api(token);
        Db db = new Db(ctx);
        int imgOk = 0;
        int imgFail = 0;
        int newThreads = 0;
        int newComments = 0;
        int changedComments = 0;
        int rankChanged = 0;
        long oldest = 0;
        try {
            long ts = System.currentTimeMillis();

            // ---------- 1) 热榜：唯一主源 ----------
            JSONArray hot = hotWithRetry(api);
            if (hot.length() == 0) {
                throw new Exception("热榜返回空列表（接口可能又变了）");
            }

            // ---------- 2) 名次事件 + 在榜回写 ----------
            db.saveHotPeriod(ts, hot, deep ? "定时" : "手动");
            rankChanged = db.countRankEventsAt(ts);

            // ---------- 3) 在榜帖：正文 + 评论 + 图 ----------
            // hot item 自带 img_paths/c_count，saveFeedPage 会判成 rich 直接落 detail_json，
            // 所以这里不需要 api.info()。
            Db.FeedResult fr = db.saveFeedPage(ts, hot);
            newThreads += fr.added.size();
            if (fr.minPostedAt != Long.MAX_VALUE) oldest = fr.minPostedAt;

            // 在榜帖的评论每轮都重拉（评论是"只增不减"的，重拉只补新的）
            java.util.List<String> wantComments = new java.util.ArrayList<String>();
            for (int i = 0; i < hot.length(); i++) {
                JSONObject t = hot.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("thread_id");
                if (tid.length() > 0) wantComments.add(tid);
            }
            int[] r = processThreads(ctx, api, db, ts, fr.added, wantComments);
            newComments += r[0];
            changedComments += r[1];
            imgOk += r[2];
            imgFail += r[3];

            // ---------- 4) 掉榜追踪（v5）：补抓掉榜未满 24h 的帖子 ----------
            java.util.Set<String> inList = new java.util.HashSet<String>();
            for (int i = 0; i < hot.length(); i++) {
                JSONObject t = hot.optJSONObject(i);
                if (t != null) inList.add(t.optString("thread_id"));
            }
            java.util.List<String> track = db.trackCandidates(System.currentTimeMillis(), inList);
            int tracked = 0;
            for (String tid : track) {
                try {
                    // 评论照常重拉（新评论插入、被删留旧版，全走现有逻辑）
                    JSONObject cs = api.comments(tid);
                    Db.CommentResult cr = db.saveComments(tid, cs, ts);
                    newComments += cr.added;
                    changedComments += cr.changed;
                    int[] imgs = fetchCommentImages(ctx, cs);
                    imgOk += imgs[0];
                    imgFail += imgs[1];
                    tracked++;
                } catch (Exception ignore) {
                }
            }

            // ---------- 5) 收尾 ----------
            long[] shrunk = ImgCache.shrink(ctx, 60L * 1000);
            String period = Db.periodOf(ts);
            db.logFetch(ts, 1, newThreads, newComments, changedComments,
                    imgOk, imgFail, oldest,
                    "inList=" + inList.size() + " rankChanged=" + rankChanged
                            + " tracked=" + tracked
                            + " shrink=" + shrunk[0]);
            db.setLastResult(ctx, ts, period, imgOk, imgFail);
            return "热榜 " + inList.size() + " 条 · 新帖 " + newThreads + " · 新评论 " + newComments
                    + " · 改动评论 " + changedComments + " · 名次变化 " + rankChanged
                    + " · 追踪 " + tracked + " 帖"
                    + " · 图 " + imgOk + "/" + (imgOk + imgFail)
                    + " · 缩略化 " + shrunk[0] + " 张省 " + (shrunk[1] / 1024) + " KB";
        } finally {
            db.close();
        }
    }

    /** 热榜偶发 urlopen timed out，给三次机会；全失败就抛出（别静默当成"热榜空了"） */
    private static JSONArray hotWithRetry(Api api) throws Exception {
        Exception last = null;
        for (int i = 0; i < HOT_RETRY; i++) {
            try {
                JSONArray hot = api.hot();
                if (hot.length() > 0) return hot;
                last = new Exception("热榜空列表");
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(1200L * (i + 1));
            } catch (InterruptedException ignore) {
            }
        }
        throw last == null ? new Exception("热榜抓取失败") : last;
    }

    /**
     * 处理在榜帖：added 补图，wantComments 全量重拉评论。
     * 返回 {新评论, 改动评论, 图ok, 图fail}
     */
    private static int[] processThreads(Context ctx, Api api, Db db, long ts,
                                        java.util.List<String> added,
                                        java.util.List<String> wantComments) {
        int newComments = 0;
        int changedComments = 0;
        int imgOk = 0;
        int imgFail = 0;

        for (String tid : added) {
            try {
                JSONObject detail = db.thread(tid);
                if (detail != null && detail.has("img_paths")) {
                    int[] g = fetchThreadImages(ctx, detail);
                    imgOk += g[0];
                    imgFail += g[1];
                } else {
                    // 理论上不会走到这里（hot item 必带 img_paths）
                    JSONObject d2 = api.info(tid);
                    if (d2 != null) {
                        db.saveThreadDetail(ts, d2);
                        int[] g = fetchThreadImages(ctx, d2);
                        imgOk += g[0];
                        imgFail += g[1];
                    }
                }
            } catch (Exception ignore) {
            }
        }

        for (String tid : wantComments) {
            try {
                JSONObject cs = api.comments(tid);
                Db.CommentResult cr = db.saveComments(tid, cs, ts);
                newComments += cr.added;
                changedComments += cr.changed;
                int[] imgs = fetchCommentImages(ctx, cs);
                imgOk += imgs[0];
                imgFail += imgs[1];
            } catch (Exception ignore) {
            }
        }
        return new int[]{newComments, changedComments, imgOk, imgFail};
    }

    /** 帖子正文里的图。返回 {ok, fail} */
    private static int[] fetchThreadImages(Context ctx, JSONObject detail) {
        int ok = 0;
        int fail = 0;
        JSONArray ip = detail.optJSONArray("img_paths");
        if (ip == null) return new int[]{0, 0};
        for (int k = 0; k < ip.length(); k++) {
            String u = Api.imgUrl(ip.optString(k));
            if (u == null) continue;
            if (ImgCache.has(ctx, u) || ImgCache.fetch(ctx, u) != null) ok++;
            else fail++;
        }
        return new int[]{ok, fail};
    }

    /** 拉一帖评论里的图（一级 + 楼中楼）。返回 {ok, fail} */
    private static int[] fetchCommentImages(Context ctx, JSONObject cs) {
        int ok = 0;
        int fail = 0;
        JSONArray list = cs.optJSONArray("list");
        if (list == null) return new int[]{0, 0};
        for (int k = 0; k < list.length(); k++) {
            JSONObject c = list.optJSONObject(k);
            if (c == null) continue;
            String[] us = Db.rawUrls(c).split(",");
            for (String u : us) {
                if (u.length() == 0) continue;
                if (ImgCache.has(ctx, u) || ImgCache.fetch(ctx, u) != null) ok++;
                else fail++;
            }
            JSONArray rl = c.optJSONArray("reply_list");
            if (rl != null) {
                for (int j = 0; j < rl.length(); j++) {
                    JSONObject r = rl.optJSONObject(j);
                    if (r == null) continue;
                    String[] rus = Db.rawUrls(r).split(",");
                    for (String u : rus) {
                        if (u.length() == 0) continue;
                        if (ImgCache.has(ctx, u) || ImgCache.fetch(ctx, u) != null) ok++;
                        else fail++;
                    }
                }
            }
        }
        return new int[]{ok, fail};
    }
}
