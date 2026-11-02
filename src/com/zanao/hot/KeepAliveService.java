package com.zanao.hot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * 前台保活服务（v5 新增）。
 *
 * 为什么需要：v4 的定时抓取只靠 AlarmManager 链式重排，在原生 Android 上没问题
 * （模拟器实测端到端正常），但国产 ROM（MIUI/EMUI/ColorOS 等）会：
 *   1) 杀掉后台进程 → 进程一死，alarm 全灭，BootReceiver 自启动又被 ROM 拦截；
 *   2) 忽略 ELAPSED_REALTIME_WAKEUP 的非精确 alarm（批量对齐、延迟触发）。
 * 前台服务带常驻通知是 ROM 唯一不敢随便杀的形态：配「电池优化白名单」后，
 * 进程优先级提到前台级，alarm 也不再被批量对齐。
 *
 * 服务职责（进程活着时的兜底）：
 *   - Handler 延迟循环：每 interval 分钟自己跑一轮 Fetcher（alarm 被吞也照抓）；
 *   - 去重：跑之前置 AutoReceiver.sFetchBusy，避免和 alarm 轮次同时抓两份；
 *   - 看门狗：每 5 分钟检查「上次成功时间 + interval + 5 分钟」还没抓过，就补抓；
 *   - 进程内 alarm 兜底重排（防链式 alarm 断链）。
 *
 * 进程被杀后的兜底交给 AutoReceiver.onReceive 里 startForegroundService 重新拉起
 * （alarm 触发时 ROM 临时放行 10 秒白名单足够启动服务）。
 */
public class KeepAliveService extends Service {

    static final String CH_ID = "zanao_keepalive";
    /** 服务是否活着（设置页显示用；主线程写、UI 线程读，可见性靠 volatile 保证） */
    public static volatile boolean sRunning = false;
    /** 服务发起抓取时使用的附加键（startService extra，true=这次来自服务循环） */
    public static final String EXTRA_NOW = "now";

    private HandlerThread worker;
    private Handler handler;
    private final Runnable tick = new Runnable() {
        public void run() {
            tryTick();
            handler.postDelayed(this, nextDelayMs());
        }
    };
    /** 看门狗：服务空闲时每 5 分钟核对一次，该抓没抓就补 */
    private final Runnable watchdog = new Runnable() {
        public void run() {
            try {
                Context c = KeepAliveService.this;
                if (!AutoReceiver.enabled(c)) return;
                long iv = AutoReceiver.intervalMin(c) * 60L * 1000L;
                long last = AutoReceiver.lastFetchAt(c);
                long now = System.currentTimeMillis();
                if (last > 0 && now - last > iv + 5L * 60L * 1000L) {
                    // 明显超期没抓成（alarm 被吞/重试全败），直接补一轮
                    if (AutoReceiver.beginFetch()) {
                        runFetch();
                    }
                }
            } catch (Throwable ignore) {
            }
            handler.postDelayed(this, 5L * 60L * 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        startForegroundCompat();
        worker = new HandlerThread("zanao-keepalive");
        worker.start();
        handler = new Handler(worker.getLooper());
        handler.postDelayed(tick, nextDelayMs());
        handler.postDelayed(watchdog, 5L * 60L * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY：进程被杀后系统尽量重建服务（重建时 intent 为 null）
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (worker != null) {
            worker.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 距下一次抓取的延迟：对齐到「上次成功抓取 + 间隔」 */
    private long nextDelayMs() {
        try {
            Context c = this;
            long iv = AutoReceiver.intervalMin(c) * 60L * 1000L;
            long last = AutoReceiver.lastFetchAt(c);
            long now = System.currentTimeMillis();
            if (last <= 0) {
                // 从没成功抓过（新装/升级后首启）：等 20 秒就先跑一轮，别干等一个间隔
                return 20L * 1000L;
            }
            long d = (last + iv) - now;
            if (d < 30L * 1000L) d = 30L * 1000L;   // 刚抓完，至少歇 30 秒
            if (d > iv) d = iv;                      // 别超过一个间隔
            return d;
        } catch (Throwable t) {
            return 15L * 60L * 1000L;
        }
    }

    /** 尝试抓一轮：拿到执行权（去重）才真跑 */
    private void tryTick() {
        try {
            if (!AutoReceiver.enabled(this)) return;
            if (!AutoReceiver.beginFetch()) return;
            runFetch();
        } catch (Throwable ignore) {
        }
    }

    private void runFetch() {
        try {
            String token = MainActivity.tokenOf(this);
            if (MainActivity.tokenOk(token)) {
                Fetcher.runScheduled(this, token);
                AutoReceiver.markFetched(this);
            }
        } catch (Throwable ignore) {
        } finally {
            AutoReceiver.endFetch();
            // 服务内跑完顺手重排 alarm（防链断）
            AutoReceiver.schedule(this, AutoReceiver.intervalMin(this));
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "热榜定时抓取",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("常驻通知用于保持定时抓取存活，不发声不振动");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CH_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("赞奥热榜")
                    .setContentText("定时抓取运行中 · " + AutoReceiver.intervalMin(this) + " 分钟一轮")
                    .setOngoing(true)
                    .setContentIntent(mainIntent())
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("赞奥热榜")
                    .setContentText("定时抓取运行中 · " + AutoReceiver.intervalMin(this) + " 分钟一轮")
                    .setOngoing(true)
                    .setContentIntent(mainIntent())
                    .build();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2001, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(2001, n);
            }
        } catch (Throwable t) {
            // 个别 ROM 对 dataSync 类型挑剔，退回无类型
            try {
                startForeground(2001, n);
            } catch (Throwable ignore) {
            }
        }
    }

    private PendingIntent mainIntent() {
        Intent i = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 2002, i, flags);
    }

    // ------------------------------------------------------------ 对外入口

    /** 应用启动 / 开机 / alarm 触发时调用：确保前台服务活着 */
    public static void ensure(Context ctx) {
        try {
            Context c = ctx.getApplicationContext();
            SharedPreferences sp = c.getSharedPreferences(AutoReceiver.PREF, Context.MODE_PRIVATE);
            if (!sp.getBoolean(AutoReceiver.KEY_ENABLED, true)) return;
            Intent i = new Intent(c, KeepAliveService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    c.startForegroundService(i);
                } else {
                    c.startService(i);
                }
            } catch (Throwable t) {
                // 个别 ROM 后台启动服务受限，忽略（alarm 链仍在）
            }
        } catch (Throwable ignore) {
        }
    }
}
