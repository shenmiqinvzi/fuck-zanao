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
 * 2026-11-01：/thread/search、/thread/list、/thread/v2/list 三个时间流接口
 * 全部被服务端降级成空列表，只剩 /thread/new/list?type=1 可用，而它永远
 * 只返回最新 10 条、不可翻页。因此"抓取间隔"直接决定了漏帖率：
 * 实测新帖速率约 1~3 条/20 秒，也就是每分钟 3~9 条；而窗口只有 10 条，
 * 换句话说间隔超过 1~2 分钟就必然有帖子从窗口里滑出去、永久抓不到。
 * 默认间隔因此从 30 分钟下调到 2 分钟，下限也从 15 分钟放宽到 1 分钟。
 */
public class AutoReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.zanao.hot.TICK";
    static final String PREF = "zanao";
    static final String KEY_INTERVAL = "interval_min";
    static final String KEY_ENABLED = "auto_enabled";
    static final int DEFAULT_INTERVAL = 2;
    static final int MIN_INTERVAL = 1;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 抓取放到后台线程：BroadcastReceiver 的 onReceive 只有 10 秒
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                    if (!sp.getBoolean(KEY_ENABLED, true)) return;
                    String token = sp.getString(MainActivity.KEY_TOKEN, "");
                    if (token.length() == 0) return;
                    Fetcher.runScheduled(app, token);
                } catch (Exception ignore) {
                }
            }
        }).start();
        schedule(ctx, intervalMin(ctx));
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
    }
}
