package com.zanao.hot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 抓取流水线：热榜 → 逐帖 info → 逐帖全量评论 → 落库 → 图片本地化
 *
 * 关键实测约束（都在真接口验证过）：
 *  - hot 必须 count=10 + type=3，否则掉字段或掉条数
 *  - info 不存在的帖返回 errno 0 + 空 detail，必须判空
 *  - comment/list 只传 id 就能拿全量，无分页
 *  - c_count 含已删楼，与 total 不等，两个都存
 *  - 图片必须带 @!common，否则 403
 */
public final class Fetcher {

    private Fetcher() {
    }

    /** 抓一轮。返回本期期号 */
    public static String runOnce(Context ctx, String token) throws Exception {
        Api api = new Api(token);
        Db db = new Db(ctx);
        int imgOk = 0;
        int imgFail = 0;
        try {
            long ts = System.currentTimeMillis();
            JSONArray hot = api.hot();
            if (hot.length() == 0) {
                throw new Api.IOException2("热榜空列表：token 失效，或 X-Sc-Version / alias 不对");
            }
            String period = db.savePeriod(ts, hot);
            for (int i = 0; i < hot.length(); i++) {
                JSONObject t = hot.optJSONObject(i);
                if (t == null) continue;
                String tid = t.optString("thread_id");
                if (tid.length() == 0) continue;

                // 帖子详情
                try {
                    JSONObject detail = api.info(tid);
                    if (detail != null) {
                        db.saveThread(detail, ts);
                        // 帖图本地化
                        JSONArray ip = detail.optJSONArray("img_paths");
                        if (ip != null) {
                            for (int k = 0; k < ip.length(); k++) {
                                String u = Api.imgUrl(ip.optString(k));
                                if (u == null) continue;
                                if (ImgCache.has(ctx, u) || ImgCache.fetch(ctx, u) != null) imgOk++;
                                else imgFail++;
                            }
                        }
                    }
                } catch (Exception ignore) {
                }

                // 评论（含楼中楼）
                try {
                    JSONObject cs = api.comments(tid);
                    db.saveComments(tid, cs, ts);
                    // 评论图本地化
                    JSONArray list = cs.optJSONArray("list");
                    if (list != null) {
                        for (int k = 0; k < list.length(); k++) {
                            JSONObject c = list.optJSONObject(k);
                            if (c == null) continue;
                            String[] us = Db.rawUrls(c).split(",");
                            for (String u : us) {
                                if (u.length() == 0) continue;
                                if (ImgCache.has(ctx, u) || ImgCache.fetch(ctx, u) != null) imgOk++;
                                else imgFail++;
                            }
                            JSONArray rl = c.optJSONArray("reply_list");
                            if (rl != null) {
                                for (int j = 0; j < rl.length(); j++) {
                                    JSONObject r = rl.optJSONObject(j);
                                    if (r == null) continue;
                                    String[] rus = Db.rawUrls(r).split(",");
                                    for (String u : rus) {
                                        if (u.length() == 0) continue;
                                        if (ImgCache.has(ctx, u) || ImgCache.fetch(ctx, u) != null) imgOk++;
                                        else imgFail++;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {
                }
            }
            db.setLastResult(ctx, ts, period, imgOk, imgFail);
            return period;
        } finally {
            db.close();
        }
    }
}
