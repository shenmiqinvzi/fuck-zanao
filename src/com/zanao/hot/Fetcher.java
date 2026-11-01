package com.zanao.hot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 抓取流水线 v3 —— 时间流优先，不再"按期快照"。
 *
 * 一轮分两段走：
 *
 *  A) 增量：从最新（end_time=当前秒）往回翻，直到某一页【既没有新帖、也没有评论数变化】为止。
 *     正常情况下 1~2 页就够（半小时大约 10~25 帖）。
 *
 *  B) 回填：从 meta 里的 backfill_cursor 继续往更早翻。只有 A 段翻满上限还没碰到干净页
 *     （长时间没开机、首次运行）时才需要它，否则 cursor=0 直接跳过。
 *
 * 两段都用同一个 processThreads()：新帖拉 info + 全量评论，老帖只在评论数涨了时重拉评论。
 *
 * 实测约束：
 *  - /thread/v2/list 已失效（服务端固定返回空列表），改用 /thread/search?end_time=
 *  - count 参数无效，永远 9~10 条
 *  - end_time 有效窗口约 47 小时：-47h 还有数据，-48h 起返回空列表（不报错）→ 必须自己卡窗口
 *  - /thread/search 偶发超时，翻页需重试（见 feedWithRetry）
 *  - 帖子一旦记过 thread_id，之后永久可拉正文+评论（掉榜不影响）
 *  - 评论只增不减，变化基本只有"此内容已被用户自行删除"
 */
public final class Fetcher {

    private Fetcher() {
    }

    /** 增量段最多翻几页（10 帖/页）。正常 1~2 页就停，这是防失控上限 */
    private static final int INCR_MAX_PAGES = 6;

    /** 回填段一轮最多花多少毫秒（按时间预算，而不是页数：每帖要拉 info + 评论 + 图，页数不好估） */
    private static final long BACKFILL_BUDGET_MS = 4L * 60 * 1000;

    /** 回填段页数硬上限，防死循环 */
    private static final int BACKFILL_MAX_PAGES = 200;

    private static final String K_BACKFILL = "backfill_cursor";

    /** 滚动复查游标：老帖评论慢慢往回刷，走到底置 0，下一轮从最新重来 */
    private static final String K_REFRESH = "refresh_cursor";

    /** 滚动复查段一轮最多花多少毫秒 */
    private static final long REFRESH_BUDGET_MS = 3L * 60 * 1000;

    /** 滚动复查段页数硬上限 */
    private static final int REFRESH_MAX_PAGES = 120;

    /**
     * 防并发：连点两次「刷新」会起两个 runOnce，两轮同时往同一个库写，
     * fetch_log 里能看到 ts 只差 3 秒的两条记录。用进程内闸门挡住第二轮。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 手动刷新：只跑增量段，秒级返回 */
    public static String runOnce(Context ctx, String token) throws Exception {
        return runOnce(ctx, token, false);
    }

    /** 定时任务：增量 + 回填 + 滚动复查，跑得慢但覆盖全 */
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
        int pages = 0;
        int newThreads = 0;
        int newComments = 0;
        int changedComments = 0;
        int rankChanged = 0;
        long oldest = 0;
        try {
            long ts = System.currentTimeMillis();
            long windowStart = ts / 1000L - Api.FEED_WINDOW_SEC;

            // ---------- 1) 热榜：只记名次变化 ----------
            try {
                JSONArray hot = api.hot();
                if (hot.length() > 0) rankChanged = db.saveRankEvents(ts, hot);
            } catch (Exception ignore) {
                // 热榜失败不影响时间流存档
            }

            // ---------- 2A) 增量：只有最新 10 条可拿，一轮就够 ----------
            // /thread/new/list?type=1 不可翻页（from_id 只能拿更新的，end_time 无效），
            // 所以这里只取一次；多翻几页只会拿到同一批 10 条，白白打请求。
            long frontier = Long.MAX_VALUE;
            JSONArray page = feedWithRetry(api, 0);
            if (page.length() > 0) {
                pages = 1;
                Db.FeedResult fr = db.saveFeedPage(ts, page);
                newThreads += fr.added.size();
                if (fr.minPostedAt != Long.MAX_VALUE && fr.minPostedAt < frontier) {
                    frontier = fr.minPostedAt;
                }
                int[] r = processThreads(ctx, api, db, ts, fr.added, fr.needDetail, fr.needComments);
                newComments += r[0];
                changedComments += r[1];
                imgOk += r[2];
                imgFail += r[3];
            }
            if (frontier != Long.MAX_VALUE) oldest = frontier;

            // 游标语义（历史遗留）：回填游标已不再使用，因为已无任何可向更早翻页的接口。
            // 保留 K_BACKFILL 的读写只为兼容旧库里的 meta 行，本轮不再播种也不再推进。

            // ---------- 2B/2C) 回填与滚动复查：2026-11-01 起停用 ----------
            // /thread/search、/thread/list、/thread/v2/list 全部被服务端降级成空列表，
            // 现在唯一的源是 /thread/new/list?type=1：永远只有最新 10 条，且 from_id 只能
            // 拿到更新的、拿不到更旧的 —— 也就是说没有任何往更早翻页的手段。
            // 因此回填段与滚动复查段无法再推进（每页都是同一批 10 条），必须停用，
            // 否则会白跑 200 页 × N 次请求。历史窗口内的帖子只能靠"提高轮询频率"来覆盖。
            long cursor = 0;
            int refreshPages = 0;

            // ---------- 3) 收尾 ----------
            // 顺手把历史遗留的原图换成缩略图（先下新图、再删旧图，失败则保留）
            long[] shrunk = ImgCache.shrink(ctx, 60L * 1000);
            String period = Db.periodOf(ts);
            db.logFetch(ts, pages, newThreads, newComments, changedComments,
                    imgOk, imgFail, oldest,
                    "rankChanged=" + rankChanged + " shrink=" + shrunk[0]
                            + " refreshPages=" + refreshPages);
            db.setLastResult(ctx, ts, period, imgOk, imgFail);
            return "翻 " + pages + " 页 · 新帖 " + newThreads + " · 新评论 " + newComments
                    + " · 改动评论 " + changedComments + " · 名次变化 " + rankChanged
                    + " · 图 " + imgOk + "/" + (imgOk + imgFail)
                    + " · 缩略化 " + shrunk[0] + " 张省 " + (shrunk[1] / 1024) + " KB"
                    + (refreshPages > 0 ? " · 复查 " + refreshPages + " 页" : "");
        } finally {
            db.close();
        }
    }

    /**
     * /thread/search 偶发 urlopen timed out，翻页时给三次机会。
     * 全失败就抛出，让上层把本轮记成失败（不要静默当成"没有新帖"）。
     */
    private static JSONArray feedWithRetry(Api api, long cursor) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return api.feed(cursor);
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(1200L * (i + 1));
                } catch (InterruptedException ignore) {
                }
            }
        }
        throw last == null ? new Exception("feed failed") : last;
    }

    /**
     * 处理一批帖：added 拉 info + 全量评论，needDetail 只补 info，
     * needComments 只补评论。返回 {新评论, 改动评论, 图ok, 图fail}
     */
    private static int[] processThreads(Context ctx, Api api, Db db, long ts,
                                        java.util.List<String> added,
                                        java.util.List<String> needDetail,
                                        java.util.List<String> needComments) {
        int newComments = 0;
        int changedComments = 0;
        int imgOk = 0;
        int imgFail = 0;

        for (String tid : added) {
            try {
                // /thread/info 已被服务端降级（detail 恒为空），但 new/list 的 item 自带
                // 完整字段，saveFeedPage 已把整条 JSON 落进 detail_json。
                // 所以先读库里的 detail_json：带 img_paths 就直接用，不再浪费一次 info 请求。
                JSONObject detail = db.thread(tid);
                if (detail == null || !detail.has("img_paths")) {
                    try {
                        JSONObject d2 = api.info(tid);
                        if (d2 != null) {
                            db.saveThreadDetail(ts, d2);
                            detail = d2;
                        }
                    } catch (Exception ignore) {
                    }
                }
                if (detail != null) {
                    int[] g = fetchThreadImages(ctx, detail);
                    imgOk += g[0];
                    imgFail += g[1];
                }
            } catch (Exception ignore) {
            }
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

        for (String tid : needDetail) {
            if (added.contains(tid)) continue;
            try {
                JSONObject detail = api.info(tid);
                if (detail != null) {
                    db.saveThreadDetail(ts, detail);
                    int[] g = fetchThreadImages(ctx, detail);
                    imgOk += g[0];
                    imgFail += g[1];
                }
            } catch (Exception ignore) {
            }
        }

        for (String tid : needComments) {
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
