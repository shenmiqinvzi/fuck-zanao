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
 * 主界面 v5：三个页签 —— 实时热榜 / 全部帖 / 搜索。
 *
 * v5（去分期 + 掉榜追踪）：用户定位这个应用是"仓库"——能看到每条帖子的全部信息就行，
 * 不在意"某时刻整面榜长什么样"的历史回看。于是：
 *
 *  - 实时热榜：threads 里 hot_seen_ts=最新轮 的在榜帖（rank 1~10 + 标题 + 热度 + 浏览/评论），
 *    榜面照看；名次轨迹/热度曲线继续存在 rank_event 里，详情页不丢
 *  - 历史期数：删掉（hot_period / rank_snapshot 已停写并清空）
 *  - 全部帖：时间流（只显示上过热榜的帖子）
 *  - 搜索：本地 SQLite 标题/正文 LIKE，标题命中排前，正文命中给摘录行
 *  - 掉榜追踪：上过榜、掉榜未满 24h 的帖子每轮补抓评论，统计页显示「追踪中 N 帖」
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
    private static final int TAB_ALL = 1;
    private static final int TAB_SEARCH = 2;

    private Db db;
    private LinearLayout listBox;
    private TextView status;
    private ScrollView sv;
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private Handler ui = new Handler(Looper.getMainLooper());
    private long loadedBefore = 0;     // 已渲染到的最老 posted_at（全部帖页签用）
    private boolean loading = false;
    private int tab = TAB_HOT;
    private final Button[] tabButtons = new Button[3];
    /** 搜索页签：输入框与当前生效的关键词 */
    private EditText searchBox;
    private String searchQuery = "";
    private LinearLayout searchBarRef;
    /** 搜索输入防抖：300ms 内连续输入只查最后一次 */
    private final Runnable searchDebounce = new Runnable() {
        public void run() { doSearch(); }
    };

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
        tabButtons[TAB_ALL] = tabButton("全部帖", TAB_ALL);
        tabButtons[TAB_SEARCH] = tabButton("搜索", TAB_SEARCH);
        for (int i = 0; i < 3; i++) tabs.addView(tabButtons[i]);
        root.addView(tabs);

        // 搜索输入行（只在搜索页签可见）
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchBar.setBackgroundColor(Color.WHITE);
        searchBar.setVisibility(View.GONE);

        searchBox = new EditText(this);
        searchBox.setHint("搜标题 / 正文关键词");
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                ui.removeCallbacks(searchDebounce);
                ui.postDelayed(searchDebounce, 300);
            }
        });
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                ui.removeCallbacks(searchDebounce);
                doSearch();
                return true;
            }
        });
        searchBar.addView(searchBox);

        Button searchClear = new Button(this);
        searchClear.setText("×");
        searchClear.setTextSize(16);
        searchClear.setPadding(dp(10), 0, dp(10), 0);
        searchClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                searchBox.setText("");
            }
        });
        searchBar.addView(searchClear);
        root.addView(searchBar);
        searchBarRef = searchBar;

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

        Button imp = new Button(this);
        imp.setText("导入");
        imp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmImport(); }
        });
        bar.addView(imp);

        // 五个按钮要挤进一屏（720px 窄屏上「导入」会被挤出屏幕），统一压紧
        for (int i = 0; i < bar.getChildCount(); i++) {
            Button b = (Button) bar.getChildAt(i);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(3), b.getPaddingTop(), dp(3), b.getPaddingBottom());
            b.setTextSize(13);
        }

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
        if (searchBarRef != null) {
            searchBarRef.setVisibility(tab == TAB_SEARCH ? View.VISIBLE : View.GONE);
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
            int n = renderHotBoard();
            if (n == 0) status.setText("还没有热榜存档，点「刷新」抓一轮");
            else updateStatus();
        } else if (tab == TAB_SEARCH) {
            if (searchQuery.length() == 0) {
                status.setText("输入关键词，在已抓到的帖子标题和正文里找");
            } else {
                int n = renderSearch(searchQuery);
                if (n == 0) status.setText("「" + searchQuery + "」没有匹配的帖子");
                else status.setText("「" + searchQuery + "」匹配 " + n + " 帖");
            }
        } else {
            int n = renderFeed(0);
            if (n == 0) status.setText("本地还没有存档，点「刷新」抓一次");
            else updateStatus();
        }
        sv.scrollTo(0, 0);
    }

    /** 搜索页签的防抖入口：输入停 300ms 或点键盘搜索键才真正查询 */
    private void doSearch() {
        if (tab != TAB_SEARCH) return;
        String q = searchBox.getText().toString().trim();
        if (q.equals(searchQuery)) return;
        searchQuery = q;
        reload();
    }

    // ---------------------------------------------------------------- 搜索结果

    /** 搜索结果列表：行样式复用全部帖的时间流（renderFeed 同款列序） */
    private int renderSearch(String q) {
        Cursor c = db.searchThreads(q, 200);
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

                // 命中上下文：标题没中、正文中了时给一行正文摘录
                boolean titleHit = t != null && t.toLowerCase(Locale.US).contains(q.toLowerCase(Locale.US));
                if (!titleHit && content != null && content.length() > 0) {
                    String low = content.toLowerCase(Locale.US);
                    int idx = low.indexOf(q.toLowerCase(Locale.US));
                    if (idx >= 0) {
                        int from = Math.max(0, idx - 12);
                        String ctx = content.substring(from, Math.min(content.length(), idx + q.length() + 24));
                        TextView hit = new TextView(this);
                        hit.setText("… " + ctx.replace('\n', ' ').replace('\r', ' ').trim() + " …");
                        hit.setTextSize(12);
                        hit.setTextColor(0xFFE53935);
                        hit.setPadding(0, dp(3), 0, 0);
                        row.addView(hit);
                    }
                }

                TextView meta = new TextView(this);
                StringBuilder sb = new StringBuilder();
                sb.append(timeText(posted));
                if (cate != null && cate.length() > 0) sb.append(" · ").append(cate);
                if (nick != null && nick.length() > 0) sb.append(" · ").append(nick);
                sb.append(" · 评论 ").append(cc == null ? "0" : cc);
                sb.append(" · 赞 ").append(lc == null ? "0" : lc);
                sb.append(" · 浏览 ").append(briefViews(vc));
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
            }
        } finally {
            c.close();
        }
        return n;
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
     * 渲染最新热榜：rank 徽标 + 标题 + 热度文本 + 浏览/评论/赞。
     * v5 起数据源是 threads 里 hot_seen_ts=最新轮 的在榜帖（不再有期表）。
     * 返回渲染条数。点击行进入帖子详情。
     */
    private int renderHotBoard() {
        long ts = db.latestPeriodTs();
        if (ts <= 0) return 0;

        // 榜头信息
        TextView head = new TextView(this);
        head.setTextSize(12);
        head.setTextColor(0xFF888888);
        head.setPadding(dp(12), dp(8), dp(12), dp(6));
        head.setText(new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(ts))
                + " 抓取 · 当前在榜");
        listBox.addView(head);

        Cursor c = db.latestHotBoard();
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
        int tracking = db.countTracking(System.currentTimeMillis());
        long lastTs = db.metaLong("last_fetch_ts", 0);
        StringBuilder sb = new StringBuilder();
        sb.append(threads).append(" 帖 · ").append(comments).append(" 条评论");
        if (tracking > 0) sb.append(" · 追踪中 ").append(tracking).append(" 帖");
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
        int tracking = db.countTracking(System.currentTimeMillis());

        StringBuilder sb = new StringBuilder();
        sb.append("帖子 ").append(threads).append(" 条（一条帖只有一行）\n");
        sb.append("评论 ").append(comments).append(" 条（只增不删）\n");
        sb.append("评论旧版 ").append(chist).append(" 条（被删前的原话）\n");
        sb.append("正文旧版 ").append(dhist).append(" 条（改过的正文）\n");
        sb.append("名次事件 ").append(rankEvents).append(" 条（名次变化时记，轨迹/热度曲线数据源）\n");
        sb.append("追踪中 ").append(tracking).append(" 帖（掉榜未满 24 小时，每轮补抓评论）\n");
        sb.append("图片 ").append(imgRefs).append(" 个 URL → ")
                .append(ImgCache.size(this) / 1024).append(" KB 本地文件\n");
        sb.append("图片按内容去重，同一张图只存一份\n");

        // 崩溃自记录：App 里有全局 UncaughtExceptionHandler，崩过就会有内容。
        // 显示出来方便用户反馈「点了某个帖子闪退」这类问题。
        String crashLog = App.read(this);
        if (crashLog != null && crashLog.length() > 0) {
            sb.append("\n⚠️ 检测到 ").append(crashLog.split("=== ").length - 1)
                    .append(" 次崩溃记录（下面是最近一次）：\n");
            String last = crashLog.substring(crashLog.lastIndexOf("=== "));
            sb.append(last.length() > 900 ? last.substring(0, 900) + "\n…" : last);
        }

        long lastTs = db.metaLong("last_fetch_ts", 0);
        if (lastTs > 0) {
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            sb.append("\n上次抓取：").append(f.format(new Date(lastTs)));
            long m = (System.currentTimeMillis() - lastTs) / 60000L;
            sb.append("（").append(m).append(" 分钟前）");
        }

        sb.append("\n\n每轮动作：拉 /thread/hot 取 top 10 → 记名次事件 + 在榜回写");
        sb.append(" → 在榜帖正文/评论/图片入库 → 掉榜未满 24h 的帖子每轮补 5 个拉评论。");
        sb.append("热榜条目自带正文全文与图片列表，不依赖已降级的 /thread/info。");
        sb.append("热榜前 10 变化慢，15~30 分钟一轮足够。");

        new AlertDialog.Builder(this)
                .setTitle("存档统计")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    // ---------------------------------------------------------------- 导出

    private static final int REQ_EXPORT = 41;
    private static final int REQ_IMPORT = 42;

    private void exportArchive() {
        Exporter.pickTarget(this, REQ_EXPORT);
    }

    /** 导入是覆盖操作：先把话说清楚，再让用户选文件 */
    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle("导入存档")
                .setMessage("导入会【覆盖】当前的全部帖子、评论和图片，且不可撤销。\n\n"
                        + "换签名重装（或换手机）时用它把数据搬回来：先在旧版本里「导出」，"
                        + "装好新版后在这里「导入」同一个 zip。\n\n要继续吗？")
                .setPositiveButton("选文件", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Exporter.pickSource(MainActivity.this, REQ_IMPORT);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport(final android.net.Uri src) {
        status.setText("导入中…");
        // 先关连接：库文件被占用就没法替换（失败路径里也要重新打开）
        if (db != null) {
            db.close();
            db = null;
        }
        pool.execute(new Runnable() {
            public void run() {
                String msg;
                try {
                    msg = Exporter.read(MainActivity.this, src);
                } catch (final Exception e) {
                    msg = "导入失败：" + e.getMessage();
                }
                final String out = msg;
                ui.post(new Runnable() {
                    public void run() {
                        if (db == null) db = new Db(MainActivity.this);
                        tab = TAB_HOT;
                        reload();
                        status.setText(out);
                        Toast.makeText(MainActivity.this, out, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            doImport(data.getData());
            return;
        }
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

        // v1.3.4：零基础用户不知道 token 从哪来，给一条能点的图文步骤入口
        TextView tlink = new TextView(this);
        tlink.setText("👉 不知道 token 从哪来？点这里看 5 分钟抓包步骤");
        tlink.setTextSize(13);
        tlink.setTextColor(0xFF1976D2);
        tlink.setPadding(0, dp(6), 0, dp(6));
        tlink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showTokenHelp();
            }
        });
        box.addView(tlink);

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
        wHint.setText("\n说明：每轮拉 /thread/hot 的 top 10，记名次并更新在榜帖；"
                + "掉榜未满 24 小时的帖子每轮再补 5 个拉评论。"
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

    /** v1.3.4：零基础取 token 的图文步骤（手机抓包为主，电脑抓包兜底） */
    private void showTokenHelp() {
        String t = "【token 是什么】\n"
                + "你在赞奥 App 里的登录凭证，固定 60 个字符（字母数字，末尾可能是 =）。"
                + "本应用不存账号密码，只用这一串代表你，所以得自己抓一次。\n\n"
                + "【手机抓包（不用 root，约 5 分钟）】\n"
                + "1. 应用商店搜「Reqable」或「HttpCanary / 小黄鸟」，装上打开。\n"
                + "2. 点「开始抓包」→ 系统问是否允许创建 VPN 连接 → 点允许。\n"
                + "   （这是本机抓自己，不翻墙、不改变你正常上网。）\n"
                + "3. 回桌面打开「赞奥」App → 进校园集市 → 点一次热榜或下拉刷新。\n"
                + "4. 切回抓包工具 → 搜索框输 zanao → 找到 api.app.zanao.com 的请求，"
                + "点开任意一条。\n"
                + "5. 看「请求头 / Request Headers」→ 复制 X-Sc-Token 后面那一整串"
                + "（60 字符，别带空格和换行）。\n"
                + "6. 抓完把抓包关掉，省电。\n\n"
                + "【电脑抓（手机上装不上工具时）】\n"
                + "手机和电脑连同一个 WiFi → 手机 WiFi 设置里把代理改成手动："
                + "主机填电脑内网 IP，端口 8080 → 电脑上跑 mitmproxy → 手机打开赞奥 App 刷新热榜 → "
                + "电脑窗口里按 / 搜 zanao → 选中请求，右边 Headers 里复制 X-Sc-Token。"
                + "这个接口是 http 明文，不用装证书。\n\n"
                + "【拿到之后】\n"
                + "粘到上一个对话框的输入框 → 保存 → 回首页点刷新。"
                + "出现 10 条热榜就成功了；提示 errno 20 就是串不对（多打或少打了一个字符）。\n\n"
                + "【注意】\n"
                + "· token 等于你赞奥账号的钥匙，别发群里、别截图带出去；"
                + "失效了就重新抓一次。\n"
                + "· 别拿别人的 token 用，请求会计在人家账号上。\n"
                + "· 抓取间隔 15 分钟足够，别调太猛。";
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setLineSpacing(dp(3), 1f);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(16), dp(8), dp(16), dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("怎么拿到 token")
                .setView(sv)
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdown();
        if (db != null) db.close();
    }
}
