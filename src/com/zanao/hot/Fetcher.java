package com.zanao.hot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 抓取流水线 v2 —— 时间流优先，不再"按期快照"。
 *
 * 一轮分两段走：
 *
 *  A) 增量：从最新（from_time=0）往回翻，直到某一页【既没有新帖、也没有评论数变化】为止。
 *     正常情况下 1~2 页就够（半小时大约 10~25 帖）。
 *
 *  B) 回填：从 meta 里的 backfill_cursor 继续往更早翻。只有 A 段翻满上限还没碰到干净页
 *     （长时间没开机、首次运行）时才需要它，否则 cursor=0 直接跳过。
 *
 * 两段都用同一个 processThreads()：新帖拉 info + 全量评论，老帖只在评论数涨了时重拉评论。
 *
 * 实测约束：
 *  - count 参数无效，永远 10 条
 *  - from_time 超过约 47.9 小时会【静默】回落成最新 10 条，不报错 → 必须自己卡窗口
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

    /** 抓一轮。返回一段人话总结 */
    public static String runOnce(Context ctx, String token) throws Exception {
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

            // ---------- 2A) 增量：从最新往回，碰到干净页就停 ----------
            long fromTime = 0;
            long frontier = Long.MAX_VALUE;
            boolean capped = true;
            for (int p = 0; p < INCR_MAX_PAGES; p++) {
                JSONArray page = api.feed(fromTime);
                if (page.length() == 0) {
                    capped = false;
                    break;
                }
                pages++;

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

                if (fr.minPostedAt == Long.MAX_VALUE) {
                    capped = false;
                    break;
                }
                fromTime = fr.minPostedAt;

                // 干净页 = 没有新帖、也没有评论数变化 → 增量结束
                if (fr.added.size() == 0 && fr.needComments.size() == 0) {
                    capped = false;
                    break;
                }
                if (fromTime <= windowStart) {
                    capped = false;
                    break;
                }
            }
            if (frontier != Long.MAX_VALUE) oldest = frontier;

            // 游标只能往回（更早）走：已有游标且比 frontier 更早时保持不动，
            // 否则每轮都会把游标重置到最新，回填段原地打转永远推不动。
            // 首次没有游标时也要播种，否则增量段一旦干净收尾，回填永远不启动。
            if (frontier != Long.MAX_VALUE) {
                long cur = db.metaLong(K_BACKFILL, 0);
                if (cur <= 0 || frontier < cur) {
                    db.setMeta(K_BACKFILL, String.valueOf(frontier));
                }
            }

            // ---------- 2B) 回填：从上次断点继续往更早，按时间预算 ----------
            long cursor = db.metaLong(K_BACKFILL, 0);
            long backfillDeadline = System.currentTimeMillis() + BACKFILL_BUDGET_MS;
            if (cursor > 0 && cursor > windowStart) {
                for (int p = 0; p < BACKFILL_MAX_PAGES; p++) {
                    if (System.currentTimeMillis() > backfillDeadline) break;
                    JSONArray page = api.feed(cursor);
                    if (page.length() == 0) {
                        cursor = 0;
                        break;
                    }
                    pages++;

                    Db.FeedResult fr = db.saveFeedPage(ts, page);
                    newThreads += fr.added.size();
                    if (fr.minPostedAt != Long.MAX_VALUE && fr.minPostedAt < oldest) {
                        oldest = fr.minPostedAt;
                    }
                    int[] r = processThreads(ctx, api, db, ts, fr.added, fr.needDetail, fr.needComments);
                    newComments += r[0];
                    changedComments += r[1];
                    imgOk += r[2];
                    imgFail += r[3];

                    if (fr.minPostedAt == Long.MAX_VALUE) {
                        cursor = 0;
                        break;
                    }
                    cursor = fr.minPostedAt;
                    if (cursor <= windowStart) {
                        cursor = 0;   // 到窗口边界，回填完成
                        break;
                    }
                }
                db.setMeta(K_BACKFILL, String.valueOf(cursor));
            }

            // ---------- 3) 收尾 ----------
            // 顺手把历史遗留的原图换成缩略图（先下新图、再删旧图，失败则保留）
            long[] shrunk = ImgCache.shrink(ctx, 60L * 1000);
            String period = Db.periodOf(ts);
            db.logFetch(ts, pages, newThreads, newComments, changedComments,
                    imgOk, imgFail, oldest,
                    "rankChanged=" + rankChanged + " shrink=" + shrunk[0]);
            db.setLastResult(ctx, ts, period, imgOk, imgFail);
            return "翻 " + pages + " 页 · 新帖 " + newThreads + " · 新评论 " + newComments
                    + " · 改动评论 " + changedComments + " · 名次变化 " + rankChanged
                    + " · 图 " + imgOk + "/" + (imgOk + imgFail)
                    + " · 缩略化 " + shrunk[0] + " 张省 " + (shrunk[1] / 1024) + " KB";
        } finally {
            db.close();
        }
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
                JSONObject detail = api.info(tid);
                if (detail != null) {
                    db.saveThreadDetail(ts, detail);
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
