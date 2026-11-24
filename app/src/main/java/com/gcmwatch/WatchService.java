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
    static final long POLL_MS = 8000L;   // 8 秒一次，stat 很便宜
    static final String DEFAULT_WATCH = "com.tencent.mm";

    static final String ACT_POLL = "com.gcmwatch.POLL_NOW";

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
        // fork su / 拷文件 / 开数据库都不能占主线程
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
                            Diag.add(WatchService.this, "poll 异常: " + e);
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
        String action = intent == null ? null : intent.getAction();
        sp.edit().putBoolean("running", true).apply();
        startAsForeground();

        if (!running) {
            running = true;
            Diag.add(this, "服务启动，开始轮询");
            handler.post(loop);
        } else if (ACT_POLL.equals(action)) {
            // 手动「立即检查」，把排队的下一轮提前到现在
            handler.removeCallbacks(loop);
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
        Diag.add(this, "服务被销毁");
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
            setStatus("读不到 microG 数据库：su 被拒绝，或路径不同");
            return;
        }

        boolean initialized = sp.getBoolean("init", false);
        if (initialized && stamp.equals(sp.getString("stamp", ""))) {
            return; // 数据库没被写过，跳过
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
            boolean watched = watching.contains(row.pkg);

            if (initialized && watched) {
                if (oldCount < 0) {
                    // 基线建立时这个应用还没有记录（此前零推送），现在第一条来了。
                    // 旧代码要求 oldCount >= 0，导致首条永远不通知，这里修掉。
                    notifyMessage(row.pkg, Math.max(row.count, 1), row.count);
                    notified++;
                    Diag.add(this, "首次通知 " + row.pkg + " count=" + row.count);
                } else if (row.count > oldCount) {
                    notifyMessage(row.pkg, row.count - oldCount, row.count);
                    notified++;
                    Diag.add(this, "通知 " + row.pkg + " " + oldCount + "->" + row.count);
                }
            }

            // 只向上更新，避免快照回退导致基线被拉低后重复通知
            if (row.count > oldCount) {
                ed.putLong("c:" + row.pkg, row.count);
            }
            ed.putLong("t:" + row.pkg, row.timestamp);
        }

        ed.putBoolean("init", true);
        ed.putString("stamp", stamp);
        ed.apply();

        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());

        // 把正在监听的应用当前计数直接显示出来，方便一眼判断「计数到底涨没涨」
        StringBuilder counts = new StringBuilder();
        for (GcmDb.Row row : rows) {
            if (watching.contains(row.pkg)) {
                counts.append(row.pkg.substring(row.pkg.lastIndexOf('.') + 1))
                        .append('=').append(row.count).append(' ');
            }
        }

        String base;
        if (!initialized) {
            base = "基线已建立（" + rows.size() + " 个应用）· 之后的推送才通知";
            Diag.add(this, "建立基线，rows=" + rows.size() + " watching=" + watching);
        } else if (notified > 0) {
            base = "刚收到 " + notified + " 个应用的新消息 · " + time;
        } else {
            base = "监听中 · " + time;
        }
        if (counts.length() > 0) {
            base = base + " · " + counts.toString().trim();
        }
        setStatus(base);
    }

    // ---------------- 通知 ----------------

    private void createChannels() {
        createChannelsStatic(this);
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
            Diag.add(this, "notify 失败: NotificationManager 为空");
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
                .setStyle(new Notification.BigTextStyle()
                        .bigText(text + "（累计 " + total + " 条）"))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (pi != null) {
            b.setContentIntent(pi);
        }
        try {
            nm.notify(20000 + (Math.abs(pkg.hashCode()) % 10000), b.build());
        } catch (Throwable t) {
            Diag.add(this, "notify 抛异常: " + t);
        }
    }

    /** 「测试通知」：不依赖 microG、不依赖 root，纯粹验证通知通道 */
    static void testNotification(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            Diag.add(ctx, "测试通知失败: NotificationManager 为空");
            return;
        }
        createChannelsStatic(ctx);
        Notification.Builder b = new Notification.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("测试通知：通道正常")
                .setContentText("看到这条说明通知权限和通知渠道都没问题")
                .setStyle(new Notification.BigTextStyle()
                        .bigText("测试能弹、真实消息不弹 → 问题在计数读取，不在通知。"))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        try {
            nm.notify(29999, b.build());
            Diag.add(ctx, "已发送测试通知");
        } catch (Throwable t) {
            Diag.add(ctx, "测试通知抛异常: " + t);
        }
    }

    static void createChannelsStatic(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel fg = new NotificationChannel(CH_FG, "后台监听",
                NotificationManager.IMPORTANCE_MIN);
        fg.setShowBadge(false);
        nm.createNotificationChannel(fg);
        NotificationChannel msg = new NotificationChannel(CH_MSG, "新消息提醒",
                NotificationManager.IMPORTANCE_HIGH);
        msg.enableVibration(true);
        msg.setShowBadge(true);
        nm.createNotificationChannel(msg);
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
