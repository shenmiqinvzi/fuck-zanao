package com.zanao.hot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：重新排上定时抓取 + 拉起前台保活服务（v5） */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            AutoReceiver.schedule(ctx, AutoReceiver.intervalMin(ctx));
            // v5：开机也把前台服务拉起来（BOOT_COMPLETED 广播期内允许 startForegroundService）
            KeepAliveService.ensure(ctx);
        }
    }
}
