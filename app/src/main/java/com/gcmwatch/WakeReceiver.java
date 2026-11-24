package com.gcmwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * root 看门狗用的唤醒入口。
 *
 * 为什么不用 `am start-foreground-service` 直接拉：Android 12 起对后台启动前台服务有拦截，
 * 而广播会先把进程拉起来（manifest 里注册的接收器收到显式广播必然启动进程），
 * 进程起来之后再启动前台服务就属于「应用自身进程内的启动」，限制更松。
 */
public class WakeReceiver extends BroadcastReceiver {

    static final String ACTION = "com.gcmwatch.WAKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences sp = context.getSharedPreferences(
                WatchService.PREF, Context.MODE_PRIVATE);
        if (!sp.getBoolean("running", false)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, WatchService.class));
        } catch (Throwable t) {
            // 后台启动被拦时，用普通 startService 兜一次
            try {
                context.startService(new Intent(context, WatchService.class));
            } catch (Throwable ignored) {
            }
        }
    }
}
