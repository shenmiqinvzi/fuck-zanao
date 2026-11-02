package com.zanao.hot;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

/**
 * 定时抓取：用 AlarmManager（不引 androidx，纯框架 API）。
 *
 * 为什么不用 WorkManager：WorkManager 属于 androidx，需要额外 aar 依赖，
 * 而本项目零依赖手搓 APK。AlarmManager.setAndAllowWhileIdle 链式重排，
 * 精度更好且不受 Doze 影响。
 *
 * 2026-11-01 晚：抓取主源已改回 /thread/hot（热榜优先）。热榜 top 10 变化很慢
 * （几分钟到几小时才动一次），不需要高频轮询，所以默认间隔回到 15 分钟、
 * 下限 5 分钟。这一版之前为了追新帖流曾把间隔压到 2 分钟/最小 1 分钟，
 * 现在没有那个必要了。
 *
 * v5（2026-11-02）：真机 ROM 杀后台导致"只能手动抓"。修复：
 *   - 新增 KeepAliveService 前台服务常驻（Handler 循环 + 看门狗）；
 *   - onReceive 顺手把前台服务拉活（进程被杀后 alarm 触发时恢复服务）；
 *   - 抓取去重：alarm 轮与服务轮共用 sFetchBusy，防止同轮抓两份。
 */
public class AutoReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.zanao.hot.TICK";
    static final String PREF = "zanao";
    static final String KEY_INTERVAL = "interval_min";
    static final String KEY_ENABLED = "auto_enabled";
    static final int DEFAULT_INTERVAL = 15;
    static final int MIN_INTERVAL = 5;

    /** 抓取执行权（Alarm 轮 / 服务轮 / 看门狗 共用，防重入抓两份） */
    static final java.util.concurrent.atomic.AtomicBoolean sFetchBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 抓取放到后台线程：BroadcastReceiver 的 onReceive 只有 10 秒
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (!enabled(app)) return;
                    if (!beginFetch()) return;   // 服务轮正在抓就让它抓
                    try {
                        String token = MainActivity.tokenOf(app);
                        if (MainActivity.tokenOk(token)) {
                            Fetcher.runScheduled(app, token);
                            markFetched(app);
                        }
                    } finally {
                        endFetch();
                    }
                } catch (Exception ignore) {
                }
            }
        }).start();
        schedule(ctx, intervalMin(ctx));
        // v5：alarm 触发 = 进程还活着，顺势把前台服务扶稳（被杀后自愈的入口）
        KeepAliveService.ensure(ctx);
    }

    static boolean enabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ENABLED, true);
    }

    /** 尝试拿抓取执行权；false = 已有别的轮次在抓 */
    static boolean beginFetch() {
        return sFetchBusy.compareAndSet(false, true);
    }

    static void endFetch() {
        sFetchBusy.set(false);
    }

    /** 记录"本轮抓取完成"时间（服务循环对齐 + 看门狗都用它） */
    static void markFetched(Context ctx) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putLong("last_fetch_ok_at", System.currentTimeMillis()).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 上次成功抓取时间；0 = 还没抓过 */
    static long lastFetchAt(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getLong("last_fetch_ok_at", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    static int intervalMin(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        int m = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
        if (m < MIN_INTERVAL) m = MIN_INTERVAL;
        return m;
    }

    /** 排下一次。间隔低于 MIN_INTERVAL 按 MIN_INTERVAL 算 */
    static void schedule(Context ctx, int minutes) {
        if (minutes < MIN_INTERVAL) minutes = MIN_INTERVAL;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AutoReceiver.class);
        i.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i, flags);

        long trigger = SystemClock.elapsedRealtime() + minutes * 60L * 1000L;
        if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        }
    }

    static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, AutoReceiver.class);
        i.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1001, i, flags);
        am.cancel(pi);
        // v5：关自动抓取时把前台服务也停了
        try {
            ctx.getApplicationContext().stopService(
                    new Intent(ctx.getApplicationContext(), KeepAliveService.class));
        } catch (Throwable ignore) {
        }
    }
}
