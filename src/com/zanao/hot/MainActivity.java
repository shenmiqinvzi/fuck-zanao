package com.zanao.hot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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
 * 主界面 v4：三个页签 —— 实时热榜 / 历史期数 / 全部帖。
 *
 * v4 是对 v2 的纠偏：v2 把首页做成"按时间倒序的全部帖子流"，热榜名次只降级成行首角标，
 * 于是"某时刻热榜长什么样"根本看不出来。v4 把热榜提回主角：
 *
 *  - 实时热榜：读最新一期 rank_snapshot，rank 1~10 + 标题 + 热度文本 + 浏览/评论
 *  - 历史期数：hot_period 倒序列出每一轮抓取，点进去看那一期的完整榜面
 *  - 全部帖：保留原来的时间流（附带热榜角标），用来翻老帖
 */
public class MainActivity extends Activity {

    static final String PREF = "zanao";
    static final String KEY_TOKEN = "token";
    /** 用户是否在设置页手动改过 token（改过且合法才优先于 APK 内置值） */
    static final String KEY_TOKEN_MANUAL = "token_manual";
    /** 内置 token 的合法长度：服务端只认 60 字符 base64，多一个字符就 errno 20 */
    static final int TOKEN_LEN = 60;

    private static final int PAGE = 50;

    /** 页签 */
    private static final int TAB_HOT = 0;
    private static final int TAB_PERIODS = 1;
    private static final int TAB_ALL = 2;

    private Db db;
    private LinearLayout listBox;
    private TextView status;
    private ScrollView sv;
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private Handler ui = new Handler(Looper.getMainLooper());
    private long loadedBefore = 0;     // 已渲染到的最老 posted_at（全部帖页签用）
    private boolean loading = false;
    private int tab = TAB_HOT;
    /** 期数页签里点开某期后，这里记着那一期的 ts；0 表示正在看期数列表 */
    private long viewPeriodTs = 0;
    private final Button[] tabButtons = new Button[3];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        setContentView(buildUi());
        reload();
        if (getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(AutoReceiver.KEY_ENABLED, true)) {
            AutoReceiver.schedule(this, AutoReceiver.intervalMin(this));
            // v5：启动前台保活服务（ROM 杀后台的对策）
            KeepAliveService.ensure(this);
            // v5：没进电池白名单就弹一次引导（每次打开 App 最多提醒一次）
            maybeBatteryGuide();
        }
    }

    /** 电池优化白名单引导：不在白名单里时弹提示，点「去设置」跳系统授权框 */
    private void maybeBatteryGuide() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String pkg = getPackageName();
            if (pm == null || pm.isIgnoringBatteryOptimizations(pkg)) return;
            SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (now - sp.getLong("battery_guide_at", 0L) < 6L * 60L * 60L * 1000L) return;
            sp.edit().putLong("battery_guide_at", now).apply();
            new AlertDialog.Builder(this)
                    .setTitle("开启定时抓取需要两步")
                    .setMessage("手机厂商会清理后台应用，导致定时抓取失效。\n\n"
                            + "第 1 步：点「去授权」→ 选择「允许，不询问」"
                            + "（本应用只在后台抓取热榜，不耗电）。\n"
                            + "第 2 步：在系统设置里允许本应用「自启动」和「后台运行」"
                            + "（小米：神隐模式/省电策略→无限制；华为：启动管理→手动管理全开）。")
                    .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            try {
                                @SuppressWarnings("InlinedApi")
                                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Throwable t) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                                } catch (Throwable ignore2) {
                                }
                            }
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } catch (Throwable ignore) {
        }
    }

    /** 长度 60 且全是 base64 字符才算合法 token（服务端只认 60 字符，多一个字符就 errno 20） */
    static boolean tokenOk(String t) {
        if (t == null || t.length() != TOKEN_LEN) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean b64 = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
            if (!b64) return false;
        }
        return true;
    }

    /**
     * token 取值优先级：
     *  1) 用户在设置页手填且合法的值（token_manual=true 且 tokenOk）
     *  2) APK 内置值 R.string.default_token（合法就用它，并回写 prefs）
     *  3) prefs 里的旧值（上面两者都不可用时兜底）
     *
     * 历史 bug：旧实现是"prefs 非空就直接返回"，于是旧安装包写进去的 61 字符坏 token
     * 会永久压住新包内置的正确值，表现为"换了新 APK 还是 errno 20"。
     */
    static String tokenOf(Context a) {
        SharedPreferences sp = a.getSharedPreferences(PREF, MODE_PRIVATE);

        String builtin = a.getString(R.string.default_token);
        if (builtin == null) builtin = "";
        builtin = builtin.trim();

        String cur = sp.getString(KEY_TOKEN, "");
        if (cur == null) cur = "";
        cur = cur.trim();

        if (sp.getBoolean(KEY_TOKEN_MANUAL, false) && tokenOk(cur)) return cur;
        if (tokenOk(builtin)) {
            if (!builtin.equals(cur)) sp.edit().putString(KEY_TOKEN, builtin).apply();
            return builtin;
        }
        return cur;
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

        // 页签行
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(8), 0, dp(8), 0);
        tabs.setBackgroundColor(0xFFF5F5F5);

        tabButtons[TAB_HOT] = tabButton("实时热榜", TAB_HOT);
        tabButtons[TAB_PERIODS] = tabButton("历史期数", TAB_PERIODS);
        tabButtons[TAB_ALL] = tabButton("全部帖", TAB_ALL);
        for (int i = 0; i < 3; i++) tabs.addView(tabButtons[i]);
        root.addView(tabs);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(4));
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

        // 滚到底自动加载更多（只有「全部帖」页签需要）
        sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                if (tab != TAB_ALL) return;
                View child = sv.getChildAt(0);
                if (child == null) return;
                if (sy + sv.getHeight() >= child.getHeight() - dp(120)) loadMore();
            }
        });

        return root;
    }

    private Button tabButton(String text, final int which) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundColor(0xFFF5F5F5);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tab = which;
                if (which == TAB_PERIODS) viewPeriodTs = 0;
                reload();
            }
        });
        return b;
    }

    private void paintTabs() {
        for (int i = 0; i < 3; i++) {
            Button b = tabButtons[i];
            if (b == null) continue;
            b.setTextColor(i == tab ? 0xFFE53935 : 0xFF666666);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 列表分发

    private void reload() {
        paintTabs();
        listBox.removeAllViews();
        loadedBefore = 0;
        if (tab == TAB_HOT) {
            int n = renderHotBoard(db.latestPeriodTs());
            if (n == 0) status.setText("还没有热榜存档，点「刷新」抓一轮");
            else updateStatus();
        } else if (tab == TAB_PERIODS) {
            if (viewPeriodTs > 0) {
                int n = renderHotBoard(viewPeriodTs);
                if (n == 0) status.setText("这一期没有榜面记录");
                else updateStatus();
            } else {
                int n = renderPeriods();
                if (n == 0) status.setText("还没有任何一期，点「刷新」抓一轮");
                else updateStatus();
            }
        } else {
            int n = renderFeed(0);
            if (n == 0) status.setText("本地还没有存档，点「刷新」抓一次");
            else updateStatus();
        }
        sv.scrollTo(0, 0);
    }

    private void loadMore() {
        if (loading || loadedBefore <= 0) return;
        loading = true;
        int n = renderFeed(loadedBefore);
        loading = false;
        if (n == 0) loadedBefore = 0;
        updateStatus();
    }

    // ---------------------------------------------------------------- 热榜榜面

    /**
     * 渲染一期榜面：rank 徽标 + 标题 + 热度文本 + 浏览/评论/赞。
     * 返回渲染条数。点击行进入帖子详情。
     */
    private int renderHotBoard(long ts) {
        if (ts <= 0) return 0;

        // 期头信息
        TextView head = new TextView(this);
        head.setTextSize(12);
        head.setTextColor(0xFF888888);
        head.setPadding(dp(12), dp(8), dp(12), dp(6));
        head.setText("第 " + Db.periodOf(ts) + " 期 · "
                + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts))
                + " 抓取" + (tab == TAB_PERIODS ? "（历史回看）" : "（最新）"));
        listBox.addView(head);

        Cursor c = db.boardOf(ts);
        int n = 0;
        try {
            while (c.moveToNext()) {
                n++;
                int rank = c.getInt(0);
                final String tid = c.getString(1);
                String title = c.getString(2);
                String hotVal = c.getString(3);
                String hotText = c.getString(4);
                int views = c.getInt(5);
                String cc = c.getString(6);
                String lc = c.getString(7);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(10), dp(10), dp(12), dp(10));
                row.setBackgroundColor(Color.WHITE);

                // 名次徽标
                TextView badge = new TextView(this);
                badge.setText(String.valueOf(rank));
                badge.setTextSize(rank <= 3 ? 16 : 14);
                badge.setTextColor(Color.WHITE);
                badge.setBackgroundColor(rank <= 3 ? 0xFFFF6B4A : 0xFFBDBDBD);
                badge.setPadding(dp(8), dp(3), dp(8), dp(3));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.rightMargin = dp(10);
                blp.topMargin = dp(1);
                row.addView(badge, blp);

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView t1 = new TextView(this);
                String shown = title;
                if (shown == null || shown.length() == 0) shown = "（无标题）";
                t1.setText(shown);
                t1.setTextSize(15);
                t1.setTextColor(Color.BLACK);
                col.addView(t1);

                TextView t2 = new TextView(this);
                StringBuilder sb = new StringBuilder();
                sb.append("热度 ").append(hotVal == null || hotVal.length() == 0
                        ? "?" : hotVal);
                sb.append(" · 浏览 ").append(hotText == null || hotText.length() == 0
                        ? briefViews(views) : hotText);
                sb.append(" · 评论 ").append(cc == null ? "0" : cc);
                sb.append(" · 赞 ").append(lc == null ? "0" : lc);
                t2.setText(sb.toString());
                t2.setTextSize(12);
                t2.setTextColor(0xFF888888);
                col.addView(t2);

                row.addView(col);

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
            }
        } finally {
            c.close();
        }

        if (tab == TAB_PERIODS && viewPeriodTs > 0) {
            Button back = new Button(this);
            back.setText("← 返回期数列表");
            back.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    viewPeriodTs = 0;
                    reload();
                }
            });
            listBox.addView(back);
        }
        return n;
    }

    // ---------------------------------------------------------------- 期数列表

    private int renderPeriods() {
        Cursor c = db.periods(false);
        int n = 0;
        try {
            while (c.moveToNext()) {
                n++;
                final long ts = c.getLong(0);
                int inList = c.getInt(1);
                String note = c.getString(2);
                int rows = c.getInt(4);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackgroundColor(Color.WHITE);

                TextView t1 = new TextView(this);
                t1.setText("第 " + Db.periodOf(ts) + " 期");
                t1.setTextSize(15);
                t1.setTextColor(Color.BLACK);
                row.addView(t1);

                TextView t2 = new TextView(this);
                StringBuilder sb = new StringBuilder();
                sb.append(new SimpleDateFormat("MM-dd HH:mm", Locale.US)
                        .format(new Date(ts)));
                sb.append(" · 在榜 ").append(inList).append(" 条");
                if (rows != inList) sb.append(" · 榜面 ").append(rows).append(" 行");
                if (note != null && note.length() > 0) sb.append(" · ").append(note);
                t2.setText(sb.toString());
                t2.setTextSize(12);
                t2.setTextColor(0xFF888888);
                row.addView(t2);

                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        viewPeriodTs = ts;
                        reload();
                    }
                });
                listBox.addView(row);

                View sep = new View(this);
                sep.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                sep.setBackgroundColor(0xFFEEEEEE);
                listBox.addView(sep);
            }
        } finally {
            c.close();
        }

        int backfill = db.countBackfillPeriods();
        if (backfill > 0) {
            TextView tail = new TextView(this);
            tail.setTextSize(11);
            tail.setTextColor(0xFFAAAAAA);
            tail.setPadding(dp(12), dp(10), dp(12), dp(14));
            tail.setText("另有 " + backfill + " 期是 v3 名次事件回灌的残缺期（只记了变化过的帖，"
                    + "不是完整榜面），已隐藏。v4 起每轮都会存完整 top 10。");
            listBox.addView(tail);
        }
        return n;
    }

    // ---------------------------------------------------------------- 全部帖（时间流）

    private int renderFeed(long before) {
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
        int periods = db.countPeriods() - db.countBackfillPeriods();
        long lastTs = db.metaLong("last_fetch_ts", 0);
        StringBuilder sb = new StringBuilder();
        sb.append(periods).append(" 期 · ").append(threads).append(" 帖 · ")
                .append(comments).append(" 条评论");
        if (hist > 0) sb.append(" · ").append(hist).append(" 条评论留了旧版");
        if (lastTs > 0) {
            long m = (System.currentTimeMillis() - lastTs) / 60000L;
            sb.append(" · 上次抓取 ").append(m < 60 ? (m + " 分钟前") : ((m / 60) + " 小时前"));
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
        int periods = db.countPeriods() - db.countBackfillPeriods();
        int backfill = db.countBackfillPeriods();
        int snaps = db.countSnapshots();

        StringBuilder sb = new StringBuilder();
        sb.append("热榜期数 ").append(periods).append(" 期（每轮一行，含整期榜面 JSON）\n");
        if (backfill > 0) {
            sb.append("另有 ").append(backfill).append(" 期是从 v3 名次事件回灌的残缺期（只有 2~3 条，不是完整榜面）\n");
        }
        sb.append("期快照 ").append(snaps).append(" 行（每期每帖一行）\n");
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
            long m = (System.currentTimeMillis() - lastTs) / 60000L;
            sb.append("（").append(m).append(" 分钟前）");
        }

        sb.append("\n\n每轮动作：拉 /thread/hot 取 top 10 → 写一期快照（期头 + 每帖一行）");
        sb.append(" → 在榜帖正文/评论/图片入库。热榜条目自带正文全文与图片列表，");
        sb.append("所以不依赖已降级的 /thread/info。热榜前 10 变化慢，15~30 分钟一轮足够。");

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
        hint.setText("token 必须是 60 字符 base64（长度差一个就报 errno 20「登录中...」）。"
                + "APK 内置 token 优先；只有在这里手填且长度合法才会覆盖它。"
                + "社区别名固定 zzu（大小写敏感，改错报 errno 30）。");
        hint.setTextSize(12);
        hint.setTextColor(0xFF666666);
        box.addView(hint);

        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(token());
        et.setHint("X-Sc-Token（60 字符 base64）");
        box.addView(et);

        TextView ivHint = new TextView(this);
        ivHint.setText("\n自动抓取间隔（分钟，最小 " + AutoReceiver.MIN_INTERVAL + "）：");
        ivHint.setTextSize(12);
        ivHint.setTextColor(0xFF666666);
        box.addView(ivHint);

        final EditText iv = new EditText(this);
        iv.setInputType(InputType.TYPE_CLASS_NUMBER);
        iv.setText(String.valueOf(AutoReceiver.intervalMin(this)));
        box.addView(iv);

        TextView wHint = new TextView(this);
        wHint.setText("\n说明：每轮只拉 /thread/hot 的 top 10 并存成一期快照。"
                + "热榜前 10 变化很慢（几分钟到几小时才动一次），15~30 分钟一轮就够，"
                + "不会像新帖流那样漏数据。建议 15 分钟（最小 " + AutoReceiver.MIN_INTERVAL + " 分钟）。");
        wHint.setTextSize(12);
        wHint.setTextColor(0xFFAA5500);
        box.addView(wHint);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String t = et.getText().toString().trim();
                        String builtin = getString(R.string.default_token);
                        if (builtin == null) builtin = "";
                        boolean manual = !t.equals(builtin.trim());
                        if (manual && !tokenOk(t)) {
                            Toast.makeText(MainActivity.this,
                                    "token 必须是 60 字符 base64（现在 " + t.length()
                                            + " 字符），没保存", Toast.LENGTH_LONG).show();
                            return;
                        }
                        sp.edit().putString(KEY_TOKEN, t)
                                .putBoolean(KEY_TOKEN_MANUAL, manual).apply();
                        int m = AutoReceiver.DEFAULT_INTERVAL;
                        try {
                            m = Integer.parseInt(iv.getText().toString().trim());
                        } catch (Exception ignore) {
                        }
                        if (m < AutoReceiver.MIN_INTERVAL) m = AutoReceiver.MIN_INTERVAL;
                        sp.edit().putInt(AutoReceiver.KEY_INTERVAL, m)
                                .putBoolean(AutoReceiver.KEY_ENABLED, true).apply();
                        AutoReceiver.schedule(MainActivity.this, m);
                        // v5：保存后顺手拉起前台服务并刷新通知上的间隔
                        KeepAliveService.ensure(MainActivity.this);
                        Toast.makeText(MainActivity.this, "已保存，每 " + m + " 分钟抓一轮",
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
                .setNeutralButton("保活设置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        showKeepAliveDialog();
                    }
                })
                .show();
    }

    /** v5：保活设置引导（电池白名单 + 自启动 + 常驻通知状态一览） */
    private void showKeepAliveDialog() {
        StringBuilder sb = new StringBuilder();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean white = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        sb.append("① 电池优化白名单：").append(white ? "✓ 已允许" : "✗ 未允许（定时抓取可能被系统杀掉）")
          .append("\n② 前台保活服务：").append(KeepAliveService.sRunning ? "✓ 运行中" : "✗ 未运行（重新打开本应用可恢复）")
          .append("\n③ 自启动权限：请在系统设置里手动开启（各品牌入口不同）\n\n")
          .append("小米：设置→应用设置→赞奥热榜→省电策略→无限制 + 允许自启动\n")
          .append("华为/荣耀：设置→应用→赞奥热榜→耗电管理→手动管理（三开关全开）\n")
          .append("OPPO/一加：设置→电池→更多→赞奥热榜→允许后台运行 + 自启动\n")
          .append("vivo：设置→电池→后台耗电管理→赞奥热榜→允许后台高耗电\n")
          .append("三星：设置→电池→后台使用限制→把赞奥热榜移出休眠应用\n")
          .append("原生/模拟器：无需额外设置");

        new AlertDialog.Builder(this)
                .setTitle("保活设置")
                .setMessage(sb.toString())
                .setPositiveButton(white ? "查看自启动设置" : "去授权电池白名单", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            PowerManager pm2 = (PowerManager) getSystemService(Context.POWER_SERVICE);
                            if (pm2 != null && pm2.isIgnoringBatteryOptimizations(getPackageName())) {
                                // 已在白名单：跳应用详情页（各品牌自启动入口都在里面）
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            } else {
                                @SuppressWarnings("InlinedApi")
                                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            }
                        } catch (Throwable t) {
                            try {
                                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                            } catch (Throwable ignore2) {
                            }
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdown();
        if (db != null) db.close();
    }
}
