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
        } catch (Throwable ignored) {
        }
    }
}
