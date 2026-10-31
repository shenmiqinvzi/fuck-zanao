package com.zanao.hot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面 v2：按发布时间倒序的帖子流（读本地库），滚到底加载更多。
 *
 * 与 v1 的差别：v1 首页显示"本期热榜 top 10"，v2 显示全部已存档帖子按时间排序，
 * 热榜名次降级成行首的角标（上过榜才有）。
 */
public class MainActivity extends Activity {

    static final String PREF = "zanao";
    static final String KEY_TOKEN = "token";

    private static final int PAGE = 50;

    private Db db;
    private LinearLayout listBox;
    private TextView status;
    private ScrollView sv;
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private Handler ui = new Handler(Looper.getMainLooper());
    private long loadedBefore = 0;     // 已渲染到的最老 posted_at
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        setContentView(buildUi());
        reload();
        if (getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(AutoReceiver.KEY_ENABLED, true)) {
            AutoReceiver.schedule(this, AutoReceiver.intervalMin(this));
        }
    }

    static String tokenOf(Activity a) {
        SharedPreferences sp = a.getSharedPreferences(PREF, MODE_PRIVATE);
        String t = sp.getString(KEY_TOKEN, "");
        if (t.length() == 0) {
            t = a.getString(R.string.default_token);
            sp.edit().putString(KEY_TOKEN, t).apply();
        }
        return t;
    }

    private String token() {
        return tokenOf(this);
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("赞奥热榜");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(14), dp(12), dp(14), dp(4));
        title.setBackgroundColor(0xFFF5F5F5);
        root.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(6));
        bar.setBackgroundColor(0xFFF5F5F5);

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        });
        bar.addView(refresh);

        Button stats = new Button(this);
        stats.setText("统计");
        stats.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showStats(); }
        });
        bar.addView(stats);

        Button set = new Button(this);
        set.setText("设置");
        set.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(); }
        });
        bar.addView(set);

        Button exp = new Button(this);
        exp.setText("导出");
        exp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exportArchive(); }
        });
        bar.addView(exp);

        root.addView(bar);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF666666);
        status.setPadding(dp(12), dp(4), dp(12), dp(4));
        root.addView(status);

        sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 滚到底自动加载更多
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                View child = sv.getChildAt(0);
                if (child == null) return;
                if (sy + sv.getHeight() >= child.getHeight() - dp(120)) loadMore();
            }
        });

        return root;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 列表

    private void reload() {
        listBox.removeAllViews();
        loadedBefore = 0;
        int n = renderPage(0);
        if (n == 0) {
            status.setText("本地还没有存档，点「刷新」抓一次");
            return;
        }
        updateStatus();
    }

    private void loadMore() {
        if (loading || loadedBefore <= 0) return;
        loading = true;
        int n = renderPage(loadedBefore);
        loading = false;
        if (n == 0) {
            // 没有更老的了
            loadedBefore = 0;
        }
        updateStatus();
    }

    /** 渲染一页，返回渲染条数 */
    private int renderPage(long before) {
        Cursor c = db.feedThreads(before, PAGE);
        int n = 0;
        try {
            while (c.moveToNext()) {
                n++;
                final String tid = c.getString(0);
                long posted = c.getLong(1);
                String t = c.getString(2);
                String cate = c.getString(3);
                String cc = c.getString(4);
                String lc = c.getString(5);
                int vc = c.getInt(6);
                int rank = c.getInt(7);
                String content = c.getString(8);
                String nick = c.getString(9);
                int hasDetail = c.getInt(10);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackgroundColor(Color.WHITE);

                LinearLayout headLine = new LinearLayout(this);
                headLine.setOrientation(LinearLayout.HORIZONTAL);

                if (rank > 0) {
                    TextView badge = new TextView(this);
                    badge.setText("榜" + rank);
                    badge.setTextSize(11);
                    badge.setTextColor(Color.WHITE);
                    badge.setBackgroundColor(0xFFFF6B4A);
                    badge.setPadding(dp(5), dp(1), dp(5), dp(1));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = dp(6);
                    lp.topMargin = dp(2);
                    headLine.addView(badge, lp);
                }

                TextView head = new TextView(this);
                String shown = t;
                if (shown == null || shown.length() == 0) {
                    shown = content == null ? "" : oneLine(content, 40);
                }
                if (shown.length() == 0) {
                    shown = hasDetail == 1 ? "（无标题 · 纯图帖）" : "（无标题）";
                }
                head.setText(shown);
                head.setTextSize(15);
                head.setTextColor(Color.BLACK);
                head.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                headLine.addView(head);
                row.addView(headLine);

                TextView meta = new TextView(this);
                StringBuilder sb = new StringBuilder();
                sb.append(timeText(posted));
                if (cate != null && cate.length() > 0) sb.append(" · ").append(cate);
                if (nick != null && nick.length() > 0) sb.append(" · ").append(nick);
                sb.append(" · 评论 ").append(cc == null ? "0" : cc);
                sb.append(" · 赞 ").append(lc == null ? "0" : lc);
                sb.append(" · 浏览 ").append(briefViews(vc));
                if (hasDetail == 0) sb.append(" · 未取正文");
                meta.setText(sb.toString());
                meta.setTextSize(12);
                meta.setTextColor(0xFF888888);
                row.addView(meta);

                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Intent i = new Intent(MainActivity.this, ThreadActivity.class);
                        i.putExtra("thread_id", tid);
                        startActivity(i);
                    }
                });
                listBox.addView(row);

                View sep = new View(this);
                sep.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                sep.setBackgroundColor(0xFFEEEEEE);
                listBox.addView(sep);

                if (posted > 0 && posted < loadedBefore) loadedBefore = posted;
                else if (posted > 0 && loadedBefore == 0) loadedBefore = posted;
            }
        } finally {
            c.close();
        }
        return n;
    }

    private static String oneLine(String s, int max) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    static String timeText(long postedAt) {
        if (postedAt <= 0) return "时间未知";
        long now = System.currentTimeMillis() / 1000L;
        long d = now - postedAt;
        if (d < 60) return "刚刚";
        if (d < 3600) return (d / 60) + " 分钟前";
        if (d < 86400L) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
            return "今天 " + f.format(new Date(postedAt * 1000L));
        }
        if (d < 2 * 86400L) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
            return "昨天 " + f.format(new Date(postedAt * 1000L));
        }
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        return f.format(new Date(postedAt * 1000L));
    }

    static String briefViews(int v) {
        if (v >= 10000) return String.format(Locale.US, "%.1fW", v / 10000.0);
        if (v >= 1000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        return String.valueOf(v);
    }

    private void updateStatus() {
        int threads = db.countThreads();
        int comments = db.countComments();
        int hist = db.countCommentHistory();
        long lastTs = db.metaLong("last_fetch_ts", 0);
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(threads).append(" 帖 · ").append(comments).append(" 条评论");
        if (hist > 0) sb.append(" · ").append(hist).append(" 条评论留了旧版");
        if (lastTs > 0) {
            long h = (System.currentTimeMillis() - lastTs) / 3600000L;
            sb.append(" · 上次抓取 ").append(h < 1 ? "不到 1" : String.valueOf(h)).append(" 小时前");
            if (h >= 46) sb.append("（超 47 小时会有缺口）");
        } else {
            sb.append(" · 还没抓过");
        }
        status.setText(sb.toString());
    }

    // ---------------------------------------------------------------- 抓取

    private void refresh() {
        status.setText("抓取中…");
        final String tk = token();
        pool.execute(new Runnable() {
            public void run() {
                try {
                    final String msg = Fetcher.runOnce(MainActivity.this, tk);
                    ui.post(new Runnable() {
                        public void run() {
                            reload();
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            status.setText("抓取失败：" + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    // ---------------------------------------------------------------- 统计

    private void showStats() {
        int threads = db.countThreads();
        int comments = db.countComments();
        int chist = db.countCommentHistory();
        int dhist = 0;
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM thread_detail_snap", null);
        try {
            if (c.moveToFirst()) dhist = c.getInt(0);
        } finally {
            c.close();
        }
        int rankEvents = 0;
        c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM rank_event", null);
        try {
            if (c.moveToFirst()) rankEvents = c.getInt(0);
        } finally {
            c.close();
        }
        int imgRefs = 0;
        c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM img_ref", null);
        try {
            if (c.moveToFirst()) imgRefs = c.getInt(0);
        } finally {
            c.close();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("帖子 ").append(threads).append(" 条（一条帖只有一行）\n");
        sb.append("评论 ").append(comments).append(" 条（只增不删）\n");
        sb.append("评论旧版 ").append(chist).append(" 条（被删前的原话）\n");
        sb.append("正文旧版 ").append(dhist).append(" 条（改过的正文）\n");
        sb.append("名次事件 ").append(rankEvents).append(" 条（只在名次变化时记）\n");
        sb.append("图片 ").append(imgRefs).append(" 个 URL → ")
                .append(ImgCache.size(this) / 1024).append(" KB 本地文件\n");
        sb.append("图片按内容去重，同一张图只存一份\n");

        long lastTs = db.metaLong("last_fetch_ts", 0);
        if (lastTs > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            sb.append("\n上次抓取：").append(f.format(new Date(lastTs)));
            long h = (System.currentTimeMillis() - lastTs) / 3600000L;
            sb.append("（").append(h).append(" 小时前）");
        }

        sb.append("\n\n抓取窗口：服务端只给最近 47.9 小时的时间流，");
        sb.append("超过 47 小时没抓，中间那段的帖子就补不回来了。");

        new AlertDialog.Builder(this)
                .setTitle("存档统计")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 导出

    private static final int REQ_EXPORT = 41;

    private void exportArchive() {
        Exporter.pickTarget(this, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_EXPORT && res == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri target = data.getData();
            status.setText("导出中…");
            pool.execute(new Runnable() {
                public void run() {
                    try {
                        final String msg = Exporter.write(MainActivity.this, target);
                        ui.post(new Runnable() {
                            public void run() {
                                status.setText(msg);
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                            }
                        });
                    } catch (final Exception e) {
                        ui.post(new Runnable() {
                            public void run() { status.setText("导出失败：" + e.getMessage()); }
                        });
                    }
                }
            });
        }
    }

    // ---------------------------------------------------------------- 设置

    private void showSettings() {
        final SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView hint = new TextView(this);
        hint.setText("token 失效报 errno 20「登录中...」；社区别名固定 zzu（大小写敏感，改错报 errno 30）。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF666666);
        box.addView(hint);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(sp.getString(KEY_TOKEN, token()));
        et.setHint("X-Sc-Token（60 字符 base64）");
        box.addView(et);

        TextView ivHint = new TextView(this);
        ivHint.setText("\n自动抓取间隔（分钟，最小 15）：");
        ivHint.setTextSize(12);
        ivHint.setTextColor(0xFF666666);
        box.addView(ivHint);

        final EditText iv = new EditText(this);
        iv.setInputType(InputType.TYPE_CLASS_NUMBER);
        iv.setText(String.valueOf(AutoReceiver.intervalMin(this)));
        box.addView(iv);

        TextView wHint = new TextView(this);
        wHint.setText("\n注意：服务端时间流窗口约 47.9 小时。间隔设太大、或手机长时间断网，"
                + "超过 47 小时那段的帖子就永久补不回来了。建议 30 分钟。");
        wHint.setTextSize(12);
        wHint.setTextColor(0xFFAA5500);
        box.addView(wHint);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = et.getText().toString().trim();
                        sp.edit().putString(KEY_TOKEN, t).apply();
                        int m = 30;
                        try {
                            m = Integer.parseInt(iv.getText().toString().trim());
                        } catch (Exception ignore) {
                        }
                        if (m < 15) m = 15;
                        sp.edit().putInt(AutoReceiver.KEY_INTERVAL, m)
                                .putBoolean(AutoReceiver.KEY_ENABLED, true).apply();
                        AutoReceiver.schedule(MainActivity.this, m);
                        Toast.makeText(MainActivity.this, "已保存，每 " + m + " 分钟抓一次",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭自动抓取", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        sp.edit().putBoolean(AutoReceiver.KEY_ENABLED, false).apply();
                        AutoReceiver.cancel(MainActivity.this);
                        Toast.makeText(MainActivity.this, "已关闭自动抓取", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdown();
        if (db != null) db.close();
    }
}
