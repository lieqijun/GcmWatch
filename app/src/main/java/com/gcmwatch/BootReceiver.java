package com.gcmwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences sp = context.getSharedPreferences(WatchService.PREF, Context.MODE_PRIVATE);
        if (!sp.getBoolean("running", false)) {
            return;
        }
        try {
            context.startForegroundService(new Intent(context, WatchService.class));
            WatchJob.schedule(context);
            Diag.add(context, "开机自启：已拉起监听");
        } catch (Throwable t) {
            Diag.add(context, "开机自启失败: " + t);
        }
    }
}
