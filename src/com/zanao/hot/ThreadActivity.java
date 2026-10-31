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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 帖子存档页：正文 + 图 + 全量评论（含楼中楼、已删楼标记）
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

        // 顶部返回条（NoActionBar 主题下自己画，否则没有返回入口）
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
        JSONObject d = db.thread(tid);
        if (d == null) {
            TextView tv = new TextView(this);
            tv.setText("本地没有这个帖的详情存档");
            tv.setPadding(dp(16), dp(16), dp(16), dp(16));
            box.addView(tv);
            return;
        }

        // ---- 标题
        TextView title = new TextView(this);
        title.setText(d.optString("title"));
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
        meta.setText(nick + " · " + cat + " · " + pt);
        meta.setTextSize(12);
        meta.setTextColor(0xFF888888);
        meta.setPadding(dp(14), 0, dp(14), dp(10));
        box.addView(meta);

        // ---- 正文
        TextView content = new TextView(this);
        content.setText(d.optString("content"));
        content.setTextSize(15);
        content.setTextColor(0xFF222222);
        content.setPadding(dp(14), 0, dp(14), dp(10));
        box.addView(content);

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
        stats.setText("浏览 " + d.optInt("view_count") + " · 赞 " + d.optString("like_num")
                + " · 收藏 " + d.optString("mark_num")
                + " · " + (d.optString("delete_status").equals("10") ? "状态正常" : "状态异常"));
        stats.setTextSize(12);
        stats.setTextColor(0xFF888888);
        stats.setPadding(dp(14), dp(6), dp(14), dp(6));
        box.addView(stats);

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

        // ---- 分隔
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
        try {
            total = c.getCount();
            while (c.moveToNext()) {
                boolean isDel = c.getInt(5) == 1;
                if (isDel) deleted++;
                addComment(c, isDel);
            }
        } finally {
            c.close();
        }
        cTitle.setText("评论 " + total + " 条" + (deleted > 0 ? "（其中 " + deleted + " 条已删）" : ""));
    }

    private void addSep() {
        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        sep.setBackgroundColor(0xFFF0F0F0);
        box.addView(sep);
    }

    private void addComment(Cursor c, boolean isDeleted) {
        String cid = c.getString(0);
        String root = c.getString(6);
        String nick = c.getString(1);
        String text = c.getString(2);
        String time = c.getString(3);
        String like = c.getString(4);
        String avatar = c.getString(8);
        String imgs = c.getString(9);

        boolean isLayer = root != null && !"0".equals(root);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(isLayer ? 34 : 14), dp(8), dp(14), dp(8));

        // 头像 + 昵称行（headimgurl 已自带 @!sm_w100_h100，不用再拼）
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

    private void loadInto(final ImageView iv, final String url) {
        // 优先本地缓存（永久存档的关键：帖子下架后 CDN 图也会失效）
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
                    // 顺手落盘
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
