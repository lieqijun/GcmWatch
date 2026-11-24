package com.gcmwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class WatchService extends Service {

    static final String PREF = "gcmwatch";
    static final String CH_FG = "fg";
    static final String CH_MSG = "msg";
    static final int FG_ID = 1001;
    static final long POLL_MS = 12000L;
    static final String DEFAULT_WATCH = "com.tencent.mm";

    private Handler handler;
    private SharedPreferences sp;
    private Runnable loop;
    private volatile boolean running;
    private String lastFgText = "";

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(PREF, MODE_PRIVATE);
        createChannels();
        handler = new Handler(Looper.getMainLooper());
        // 轮到后台线程跑：poll() 里面要 fork su、拷文件、开数据库，不能占主线程
        loop = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            poll();
                        } catch (Throwable e) {
                            setStatus("出错: " + e.getMessage());
                        }
                        handler.postDelayed(WatchService.this.loop, POLL_MS);
                    }
                }, "gcm-poll");
                t.setDaemon(true);
                t.start();
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sp.edit().putBoolean("running", true).apply();
        startAsForeground();
        if (!running) {
            running = true;
            handler.post(loop);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null && loop != null) {
            handler.removeCallbacks(loop);
        }
        sp.edit().putBoolean("running", false).putString("status", "已停止").apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 轮询 ----------------

    private void poll() {
        String stamp = GcmDb.stamp();
        if (stamp.length() == 0) {
            setStatus("读不到 microG 数据库：su 被拒绝，或 microG 未安装 / 路径不同");
            return;
        }

        boolean initialized = sp.getBoolean("init", false);
        if (initialized && stamp.equals(sp.getString("stamp", ""))) {
            return; // 没有任何写入，跳过
        }

        List<GcmDb.Row> rows;
        try {
            rows = GcmDb.read(this);
        } catch (Exception e) {
            setStatus("读取失败: " + e.getMessage());
            return;
        }

        Set<String> watching = getWatching(this);
        SharedPreferences.Editor ed = sp.edit();
        int notified = 0;

        for (GcmDb.Row row : rows) {
            long oldCount = sp.getLong("c:" + row.pkg, -1);
            if (initialized && watching.contains(row.pkg) && oldCount >= 0 && row.count > oldCount) {
                long delta = row.count - oldCount;
                notifyMessage(row.pkg, delta, row.count);
                notified++;
            }
            // 只向上更新，避免 WAL 回退导致基线被拉低后重复通知
            if (row.count > oldCount) {
                ed.putLong("c:" + row.pkg, row.count);
            }
            ed.putLong("t:" + row.pkg, row.timestamp);
        }

        ed.putBoolean("init", true);
        ed.putString("stamp", stamp);
        ed.apply();

        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String base = "监听中 · " + rows.size() + " 个应用 · 检查于 " + time;
        if (!initialized) {
            base = "基线已建立（" + rows.size() + " 个应用）· 之后新增的推送才会通知";
        } else if (notified > 0) {
            base = "刚收到 " + notified + " 个应用的新消息 · " + time;
        }
        setStatus(base);
    }

    // ---------------- 通知 ----------------

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel fg = new NotificationChannel(CH_FG, "后台监听", NotificationManager.IMPORTANCE_MIN);
        fg.setDescription("常驻通知，表示监听服务在运行");
        fg.setShowBadge(false);
        nm.createNotificationChannel(fg);

        NotificationChannel msg = new NotificationChannel(CH_MSG, "新消息提醒", NotificationManager.IMPORTANCE_HIGH);
        msg.setDescription("microG 收到目标应用的新推送时提醒");
        msg.enableVibration(true);
        msg.setShowBadge(true);
        nm.createNotificationChannel(msg);
    }

    private void startAsForeground() {
        Notification n = new Notification.Builder(this, CH_FG)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("GCM 消息监听")
                .setContentText(sp.getString("status", "启动中…"))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, n);
        }
    }

    private void setStatus(String text) {
        sp.edit().putString("status", text).apply();
        if (text.equals(lastFgText)) {
            return;
        }
        lastFgText = text;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        Notification n = new Notification.Builder(this, CH_FG)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("GCM 消息监听")
                .setContentText(text)
                .setOngoing(true)
                .build();
        try {
            nm.notify(FG_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private void notifyMessage(String pkg, long delta, long total) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        String label = appLabel(this, pkg);
        String text = delta > 1 ? ("新增 " + delta + " 条消息") : "有 1 条新消息";

        PendingIntent pi = null;
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pi = PendingIntent.getActivity(this, pkg.hashCode(), launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        Notification.Builder b = new Notification.Builder(this, CH_MSG)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(label + "：" + text)
                .setContentText("累计 " + total + " 条 · " + pkg)
                .setStyle(new Notification.BigTextStyle().bigText(text + "（累计 " + total + " 条）"))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (pi != null) {
            b.setContentIntent(pi);
        }
        try {
            nm.notify(20000 + (Math.abs(pkg.hashCode()) % 10000), b.build());
        } catch (Throwable ignored) {
            // 没有通知权限
        }
    }

    static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    // ---------------- 监听名单 ----------------

    static Set<String> getWatching(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, MODE_PRIVATE);
        Set<String> s = p.getStringSet("watching", null);
        if (s == null) {
            s = new HashSet<>();
            s.add(DEFAULT_WATCH);
            p.edit().putStringSet("watching", new HashSet<>(s)).apply();
        }
        return new HashSet<>(s);
    }

    static void setWatching(Context ctx, Set<String> pkgs) {
        ctx.getSharedPreferences(PREF, MODE_PRIVATE)
                .edit().putStringSet("watching", new HashSet<>(pkgs)).apply();
    }
}
