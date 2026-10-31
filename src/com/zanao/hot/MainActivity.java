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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：本期热榜 top 10（读本地库）+ 手动刷新 + 历史期数 + 设置
 */
public class MainActivity extends Activity {

    static final String PREF = "zanao";
    static final String KEY_TOKEN = "token";

    private Db db;
    private LinearLayout listBox;
    private TextView status;
    private ExecutorService pool = Executors.newFixedThreadPool(2);
    private Handler ui = new Handler(Looper.getMainLooper());
    private String shownPeriod = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);
        setContentView(buildUi());
        loadLatest();
        // 首次进入时排上定时任务（幂等）
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

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(6));
        bar.setBackgroundColor(0xFFF5F5F5);

        // 标题单独一行：早先放在按钮同一行用 weight=1 抢剩余宽度，
        // 四个按钮 wrap_content 几乎占满，标题被压成 0 宽看不见。
        TextView title = new TextView(this);
        title.setText("赞奥热榜");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(14), dp(12), dp(14), dp(4));
        title.setBackgroundColor(0xFFF5F5F5);
        root.addView(title);

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refresh(); }
        });
        bar.addView(refresh);

        Button hist = new Button(this);
        hist.setText("历史");
        hist.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showHistory(); }
        });
        bar.addView(hist);

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

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void loadLatest() {
        Cursor c = db.latestPeriod();
        try {
            if (c.moveToFirst()) shownPeriod = c.getString(0);
        } finally {
            c.close();
        }
        if (shownPeriod == null) {
            status.setText("本地还没有存档，点「刷新」抓一次");
            listBox.removeAllViews();
            return;
        }
        renderPeriod(shownPeriod);
    }

    private void renderPeriod(String period) {
        shownPeriod = period;
        listBox.removeAllViews();
        Cursor c = db.periodRows(period);
        int n = 0;
        try {
            while (c.moveToNext()) {
                n++;
                final String tid = c.getString(1);
                int rank = c.getInt(0);
                String t = c.getString(2);
                String hv = c.getString(3);
                String cc = c.getString(4);
                String lc = c.getString(5);
                int vc = c.getInt(6);
                String cate = c.getString(7);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                row.setBackgroundColor(Color.WHITE);

                TextView head = new TextView(this);
                head.setText(rank + ". " + t);
                head.setTextSize(15);
                head.setTextColor(Color.BLACK);
                row.addView(head);

                TextView meta = new TextView(this);
                meta.setText(cate + " · 热度 " + hv + " · " + briefViews(vc)
                        + " · 评论 " + cc + " · 赞 " + lc);
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
                sep.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                sep.setBackgroundColor(0xFFEEEEEE);
                listBox.addView(sep);
            }
        } finally {
            c.close();
        }
        status.setText("本期 " + period + " · 在榜 " + n + " 帖（热榜上限 10）");
    }

    static String briefViews(int v) {
        if (v >= 10000) return String.format("%.1fW", v / 10000.0);
        if (v >= 1000) return String.format("%.1fK", v / 1000.0);
        return String.valueOf(v);
    }

    // ---------------------------------------------------------------- 抓取

    private void refresh() {
        status.setText("抓取中…");
        final String tk = token();
        pool.execute(new Runnable() {
            public void run() {
                try {
                    final String p = Fetcher.runOnce(MainActivity.this, tk);
                    ui.post(new Runnable() {
                        public void run() {
                            renderPeriod(p);
                            Toast.makeText(MainActivity.this, "已存档 " + p, Toast.LENGTH_SHORT).show();
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

    private void showHistory() {
        Cursor c = db.allPeriods();
        final String[] labels;
        final String[] periods;
        try {
            int n = c.getCount();
            if (n == 0) {
                Toast.makeText(this, "还没有历史期数", Toast.LENGTH_SHORT).show();
                return;
            }
            labels = new String[n];
            periods = new String[n];
            for (int i = 0; i < n; i++) {
                c.moveToNext();
                periods[i] = c.getString(0);
                labels[i] = periods[i] + "  （" + c.getInt(1) + " 帖）";
            }
        } finally {
            c.close();
        }
        new AlertDialog.Builder(this)
                .setTitle("历史期数")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        renderPeriod(periods[which]);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

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
                        sp.edit().putInt(AutoReceiver.KEY_INTERVAL, m).putBoolean(AutoReceiver.KEY_ENABLED, true).apply();
                        AutoReceiver.schedule(MainActivity.this, m);
                        Toast.makeText(MainActivity.this, "已保存，每 " + m + " 分钟抓一次", Toast.LENGTH_SHORT).show();
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
