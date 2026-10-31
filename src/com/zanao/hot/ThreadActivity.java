package com.zanao.hot;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 帖子存档页 v2：正文（含改过的旧版）+ 图 + 全量评论（含楼中楼、已删楼的原话）
 */
public class ThreadActivity extends Activity {

    private Db db;
    private String tid;
    private LinearLayout box;
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        tid = getIntent().getStringExtra("thread_id");

        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        sv.addView(box);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.WHITE);
        shell.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFFF5F5F5);
        bar.setPadding(dp(8), dp(10), dp(14), dp(10));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(15);
        back.setTextColor(0xFF1976D2);
        back.setPadding(dp(8), dp(4), dp(10), dp(4));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView barTitle = new TextView(this);
        barTitle.setText("帖子存档");
        barTitle.setTextSize(15);
        barTitle.setTextColor(Color.BLACK);
        barTitle.setPadding(dp(6), dp(4), 0, dp(4));
        bar.addView(barTitle);

        shell.addView(bar);
        shell.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(shell);

        render();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void render() {
        box.removeAllViews();
        JSONObject d = db.threadFull(tid);
        if (d == null) {
            TextView tv = new TextView(this);
            tv.setText("本地没有这个帖的存档");
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            box.addView(tv);
            return;
        }

        // ---- 标题
        TextView title = new TextView(this);
        String t = d.optString("title");
        title.setText(t.length() == 0 ? "（无标题）" : t);
        title.setTextSize(19);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(14), dp(14), dp(14), dp(6));
        box.addView(title);

        // ---- 作者行
        TextView meta = new TextView(this);
        String nick = d.optString("nickname");
        if (d.optBoolean("is_anon")) nick = nick + "（匿名）";
        String cat = d.optString("cate_name");
        String pt = d.optString("pt_time");
        StringBuilder mb = new StringBuilder();
        if (nick.length() > 0) mb.append(nick);
        if (cat.length() > 0) mb.append(mb.length() > 0 ? " · " : "").append(cat);
        if (pt.length() > 0) mb.append(mb.length() > 0 ? " · " : "").append(pt);
        int rank = d.optInt("hot_rank", 0);
        if (rank > 0) mb.append(mb.length() > 0 ? " · " : "").append("曾上榜第 ").append(rank).append(" 名");
        meta.setText(mb.toString());
        meta.setTextSize(12);
        meta.setTextColor(0xFF888888);
        meta.setPadding(dp(14), 0, dp(14), dp(10));
        box.addView(meta);

        // ---- 正文
        TextView content = new TextView(this);
        String body = d.optString("content");
        content.setText(body.length() == 0 ? "（正文为空，可能原帖没写或已被清空）" : body);
        content.setTextSize(15);
        content.setTextColor(body.length() == 0 ? 0xFF999999 : 0xFF222222);
        content.setPadding(dp(14), 0, dp(14), dp(10));
        box.addView(content);

        // ---- 正文被改过的旧版本
        Cursor dh = db.detailHistory(tid);
        int dhCount = 0;
        try {
            dhCount = dh.getCount();
            if (dhCount > 0) {
                TextView h = new TextView(this);
                h.setText("正文改过 " + dhCount + " 次，旧版如下：");
                h.setTextSize(12);
                h.setTextColor(0xFFAA5500);
                h.setPadding(dp(14), dp(4), dp(14), dp(4));
                box.addView(h);
                int shown = 0;
                while (dh.moveToNext() && shown < 10) {
                    shown++;
                    long ts = dh.getLong(0);
                    String ot = dh.getString(1);
                    String oc = dh.getString(2);
                    TextView hv = new TextView(this);
                    hv.setText("【" + fmtTs(ts) + "】" + ot + "\n" + oc);
                    hv.setTextSize(13);
                    hv.setTextColor(0xFF777777);
                    hv.setPadding(dp(14), dp(2), dp(14), dp(6));
                    hv.setBackgroundColor(0xFFFAFAFA);
                    box.addView(hv);
                }
            }
        } finally {
            dh.close();
        }

        // ---- 帖子图（img_paths 是相对路径，要拼 @!common）
        JSONArray ip = d.optJSONArray("img_paths");
        if (ip != null && ip.length() > 0) {
            for (int i = 0; i < ip.length(); i++) {
                String url = Api.imgUrl(ip.optString(i));
                if (url == null) continue;
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
                lp.setMargins(dp(14), dp(4), dp(14), dp(4));
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(iv);
                loadInto(iv, url);
            }
        }

        // ---- 数据行
        TextView stats = new TextView(this);
        StringBuilder s = new StringBuilder();
        s.append("浏览 ").append(d.optLong("view_count"));
        s.append(" · 赞 ").append(d.optString("like_num", "0"));
        s.append(" · 收藏 ").append(d.optString("mark_num", "0"));
        s.append(" · 评论 ").append(d.optString("c_count", "0"));
        String drt = d.optString("delete_reason_text");
        String crt = d.optString("check_reason_text");
        if (drt.length() > 0 || crt.length() > 0) {
            s.append(" · 状态：").append(drt.length() > 0 ? drt : crt);
        }
        stats.setText(s.toString());
        stats.setTextSize(12);
        stats.setTextColor(0xFF888888);
        stats.setPadding(dp(14), dp(6), dp(14), dp(6));
        box.addView(stats);

        // ---- 名次轨迹
        Cursor rh = db.rankHistory(tid);
        try {
            int n = rh.getCount();
            if (n > 1) {
                StringBuilder rb = new StringBuilder("名次轨迹：");
                int shown = 0;
                while (rh.moveToNext() && shown < 12) {
                    if (shown > 0) rb.append(" → ");
                    rb.append(rh.getInt(1)).append("（").append(shortTs(rh.getLong(0))).append("）");
                    shown++;
                }
                TextView rv = new TextView(this);
                rv.setText(rb.toString());
                rv.setTextSize(12);
                rv.setTextColor(0xFF888888);
                rv.setPadding(dp(14), 0, dp(14), dp(6));
                box.addView(rv);
            }
        } finally {
            rh.close();
        }

        // ---- 原站链接
        TextView link = new TextView(this);
        link.setText("在浏览器打开原帖");
        link.setTextSize(13);
        link.setTextColor(0xFF1976D2);
        link.setPadding(dp(14), dp(6), dp(14), dp(14));
        final String shortUrl = d.optString("short_url");
        link.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (shortUrl.length() == 0) return;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(shortUrl)));
                } catch (Exception ignore) {
                }
            }
        });
        box.addView(link);

        addSep();

        // ---- 评论
        TextView cTitle = new TextView(this);
        cTitle.setTextSize(14);
        cTitle.setTextColor(Color.BLACK);
        cTitle.setPadding(dp(14), dp(10), dp(14), dp(6));
        box.addView(cTitle);

        Cursor c = db.commentsOf(tid);
        int total = 0;
        int deleted = 0;
        int withHist = 0;
        try {
            total = c.getCount();
            while (c.moveToNext()) {
                boolean isDel = c.getInt(5) == 1;
                if (isDel) deleted++;
                String cid = c.getString(0);
                if (hasHistory(cid)) withHist++;
                addComment(c, isDel);
            }
        } finally {
            c.close();
        }
        StringBuilder cb = new StringBuilder("评论 ").append(total).append(" 条");
        if (deleted > 0) cb.append("（其中 ").append(deleted).append(" 条已删）");
        if (withHist > 0) cb.append(" · ").append(withHist).append(" 条留有原话");
        cTitle.setText(cb.toString());
    }

    /** 该评论有没有留下旧版内容 */
    private boolean hasHistory(String commentId) {
        Cursor h = db.commentHistory(commentId);
        try {
            return h.getCount() > 0;
        } finally {
            h.close();
        }
    }

    private void addSep() {
        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        sep.setBackgroundColor(0xFFF0F0F0);
        box.addView(sep);
    }

    private void addComment(Cursor c, boolean isDeleted) {
        String cid = c.getString(0);
        String nick = c.getString(1);
        String text = c.getString(2);
        String time = c.getString(3);
        String like = c.getString(4);
        String root = c.getString(6);
        String avatar = c.getString(8);
        String imgs = c.getString(9);

        boolean isLayer = root != null && !"0".equals(root);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(isLayer ? 34 : 14), dp(8), dp(14), dp(8));

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        if (avatar != null && avatar.length() > 4) {
            ImageView av = new ImageView(this);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(22), dp(22));
            alp.setMargins(0, 0, dp(6), 0);
            av.setLayoutParams(alp);
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            headRow.addView(av);
            loadInto(av, avatar);
        }

        TextView head = new TextView(this);
        head.setText(nick + "  " + time + (like != null && !"0".equals(like) ? "  ♥" + like : ""));
        head.setTextSize(12);
        head.setTextColor(0xFF999999);
        headRow.addView(head);
        row.addView(headRow);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(14);
        body.setTextColor(isDeleted ? 0xFFAAAAAA : 0xFF222222);
        row.addView(body);

        // 已删的评论：把留档的原话显示出来（v2 的核心价值）
        if (isDeleted) {
            Cursor h = db.commentHistory(cid);
            try {
                int n = h.getCount();
                int shown = 0;
                while (h.moveToNext() && shown < 3) {
                    shown++;
                    long ts = h.getLong(0);
                    String old = h.getString(1);
                    if (old == null || old.length() == 0) continue;
                    if (old.equals(text)) continue;
                    TextView hv = new TextView(this);
                    hv.setText("原话（" + fmtTs(ts) + "）：" + old);
                    hv.setTextSize(13);
                    hv.setTextColor(0xFF7755AA);
                    hv.setBackgroundColor(0xFFF5F0FF);
                    hv.setPadding(dp(8), dp(4), dp(8), dp(4));
                    row.addView(hv);
                }
            } finally {
                h.close();
            }
        }

        if (imgs != null && imgs.length() > 0) {
            for (String u : imgs.split(",")) {
                if (u.length() == 0) continue;
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
                lp.setMargins(0, dp(4), 0, dp(4));
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(iv);
                loadInto(iv, u);
            }
        }

        box.addView(row);

        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        sep.setBackgroundColor(0xFFF2F2F2);
        box.addView(sep);
    }

    private static String fmtTs(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        return f.format(new Date(ts));
    }

    private static String shortTs(long ts) {
        SimpleDateFormat f = new SimpleDateFormat("dd日HH:mm", Locale.US);
        return f.format(new Date(ts));
    }

    private void loadInto(final ImageView iv, final String url) {
        if (ImgCache.has(this, url)) {
            byte[] b = ImgCache.readAll(ImgCache.fileOf(this, url));
            if (b != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                if (bmp != null) {
                    iv.setImageBitmap(bmp);
                    return;
                }
            }
        }
        pool.execute(new Runnable() {
            public void run() {
                try {
                    final byte[] bytes = Api.download(url);
                    if (bytes == null || bytes.length == 0) return;
                    ImgCache.fetch(ThreadActivity.this, url);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp == null) return;
                    ui.post(new Runnable() {
                        public void run() { iv.setImageBitmap(bmp); }
                    });
                } catch (Exception e) {
                    // 图片失败不影响正文
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdown();
        if (db != null) db.close();
    }
}
