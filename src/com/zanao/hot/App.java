package com.zanao.hot;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获。
 *
 * 为什么需要：这台机器没有 adb、也没法连真机，用户手机上万一崩了完全看不到 logcat。
 * 所以把未捕获异常写进应用私有目录 crash.log，设置页里能直接看，
 * 也能通过「导出」一并带走。
 */
public class App extends Application {

    public static final String CRASH_FILE = "crash.log";

    @Override
    public void onCreate() {
        super.onCreate();
        AppHolder.APP = this;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    record(e);
                } catch (Throwable ignore) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    static void record(Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            e.printStackTrace(pw);
            pw.flush();
            File f = new File(current().getFilesDir(), CRASH_FILE);
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(sw.toString().getBytes("UTF-8"));
            out.write('\n');
            out.close();
        } catch (Throwable ignore) {
        }
    }

    private static Application current() {
        return AppHolder.APP;
    }

    /** Application 实例持有者（避免到处传 Context） */
    static final class AppHolder {
        static Application APP;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
    }

    static String read(android.content.Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), CRASH_FILE);
            if (!f.exists()) return null;
            byte[] b = ImgCache.readAll(f);
            if (b == null) return null;
            String s = new String(b, "UTF-8");
            return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
        } catch (Throwable e) {
            return null;
        }
    }

    static void clear(android.content.Context ctx) {
        new File(ctx.getFilesDir(), CRASH_FILE).delete();
    }
}
