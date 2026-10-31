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
 * 定时抓取：用 AlarmManager（不引 androidx，纯框架 API）
 *
 * 为什么不用 WorkManager：WorkManager 属于 androidx，需要额外 aar 依赖，
 * 而本项目零依赖手搓 APK。AlarmManager.setInexactRepeating 的最小间隔是 15 分钟，
 * 与文档 §7 的"最小 15m"一致；这里用 setExactAndAllowWhileIdle 链式重排，
 * 精度更好且不受 Doze 影响。
 */
public class AutoReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.zanao.hot.TICK";
    static final String PREF = "zanao";
    static final String KEY_INTERVAL = "interval_min";
    static final String KEY_ENABLED = "auto_enabled";
    static final int DEFAULT_INTERVAL = 30;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        // 抓取放到后台线程，BroadcastReceiver 的 onReceive 只有 10 秒
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
        return sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
    }

    /** 排下一次。间隔低于 15 分钟按 15 分钟算（AlarmManager 硬下限） */
    static void schedule(Context ctx, int minutes) {
        if (minutes < 15) minutes = 15;
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
