package com.gcmwatch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private TextView tvRoot;
    private TextView tvStatus;
    private Button btnPerm;
    private Button btnToggle;
    private Button btnScan;
    private Button btnTest;
    private Button btnCheck;
    private LinearLayout llApps;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private long lastRootCheck;
    private volatile boolean checkingRoot;
    private int rootRetry;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean running = sp.getBoolean("running", false);
            long last = sp.getLong("lastPoll", 0);
            long age = last == 0 ? -1 : (System.currentTimeMillis() - last) / 1000;
            boolean alive = age >= 0 && age < 40;

            String head;
            if (!running) {
                head = "【已停止】 ";
            } else if (alive) {
                head = "【运行中】 ";
            } else {
                head = "【服务没在跑】 ";
            }

            String tail;
            if (age < 0) {
                tail = " · 还没检查过";
            } else if (age < 60) {
                tail = " · 最后检查 " + age + " 秒前";
            } else {
                tail = " · 最后检查 " + (age / 60) + " 分钟前";
            }

            tvStatus.setText(head + sp.getString("status", "未启动") + tail);
            btnToggle.setText(running ? "停止监听" : "开始监听");
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences(WatchService.PREF, MODE_PRIVATE);

        tvRoot = findViewById(R.id.tvRoot);
        tvStatus = findViewById(R.id.tvStatus);
        btnPerm = findViewById(R.id.btnPerm);
        btnToggle = findViewById(R.id.btnToggle);
        btnScan = findViewById(R.id.btnScan);
        btnTest = findViewById(R.id.btnTest);
        btnCheck = findViewById(R.id.btnCheck);
        llApps = findViewById(R.id.llApps);

        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
                }
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sp.getBoolean("running", false)) {
                    WatchJob.cancel(MainActivity.this);
                    stopService(new Intent(MainActivity.this, WatchService.class));
                } else {
                    startForegroundService(new Intent(MainActivity.this, WatchService.class));
                    WatchJob.schedule(MainActivity.this);
                }
            }
        });

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WatchService.testNotification(MainActivity.this);
            }
        });

        btnCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, WatchService.class);
                i.setAction(WatchService.ACT_POLL);
                startForegroundService(i);
                Toast.makeText(MainActivity.this, "已请求立即检查", Toast.LENGTH_SHORT).show();
            }
        });

        // 点顶部那一行可以重新检测 root：
        // 第一次弹 su 授权框时如果点慢了，会按超时判成未授权
        tvRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkRoot(true);
            }
        });

        // 之前开过监听、但进程被清了：一打开界面就自动把服务拉回来
        if (sp.getBoolean("running", false)) {
            startForegroundService(new Intent(this, WatchService.class));
            WatchJob.schedule(this);
        }

        checkRoot(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            btnPerm.setVisibility(View.VISIBLE);
        } else {
            btnPerm.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
        // 用户可能刚去 root 管理器里点了授权，回来时给重试次数重新计数
        rootRetry = 0;
        checkRoot(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            btnPerm.setVisibility(View.GONE);
        }
    }

    private void checkRoot(final boolean force) {
        if (checkingRoot) {
            return;
        }
        if (!force && System.currentTimeMillis() - lastRootCheck < 3000) {
            return;
        }
        checkingRoot = true;
        lastRootCheck = System.currentTimeMillis();
        tvRoot.setText("root：检测中…（第一次会弹 su，请点允许）");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String text = GcmDb.diagnose();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        checkingRoot = false;
                        tvRoot.setText(text);
                        if (text.startsWith("root：已授权")) {
                            rootRetry = 0;
                            // 只在真的找到库时才触发一次立即检查；
                            // 没找到时文本里也有 "gcmstatus" 字样，别再拿它当判据
                            if (text.contains(" · 数据库 ") && sp.getBoolean("running", false)) {
                                // root 刚接通：让服务马上按新路径重建一次基线
                                Intent i = new Intent(MainActivity.this, WatchService.class);
                                i.setAction(WatchService.ACT_POLL);
                                startForegroundService(i);
                            }
                        } else {
                            // 重装后 UID 变了，root 管理器要重新记一次授权。
                            // 用户点完「允许」时本页不会收到任何事件，所以这里自己重测几次。
                            scheduleRootRetry();
                        }
                    }
                });
            }
        }).start();
    }

    private void scheduleRootRetry() {
        if (rootRetry >= 6) {
            return;
        }
        rootRetry++;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    checkRoot(true);
                }
            }
        }, 3000);
    }

    private void scan() {
        btnScan.setEnabled(false);
        llApps.removeAllViews();
        Toast.makeText(this, "读取中…（会弹 su）", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                List<GcmDb.Row> rows = null;
                try {
                    rows = GcmDb.read(MainActivity.this);
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final List<GcmDb.Row> fRows = rows;
                final String fError = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        btnScan.setEnabled(true);
                        if (fError != null) {
                            Toast.makeText(MainActivity.this, "失败：" + fError,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        fillApps(fRows);
                    }
                });
            }
        }).start();
    }

    private void fillApps(List<GcmDb.Row> rows) {
        llApps.removeAllViews();
        if (rows == null || rows.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("数据库里没有应用记录。说明 microG 至今没收到过任何推送。");
            llApps.addView(tv);
            return;
        }

        final Set<String> watching = WatchService.getWatching(this);

        for (final GcmDb.Row row : rows) {
            CheckBox cb = new CheckBox(this);
            String label = WatchService.appLabel(this, row.pkg);
            cb.setText(label + "  (" + row.pkg + ")\n已收 " + row.count + " 条 / "
                    + row.bytes + " 字节");
            cb.setTextSize(14f);
            cb.setChecked(watching.contains(row.pkg));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Set<String> cur = WatchService.getWatching(MainActivity.this);
                    if (isChecked) {
                        cur.add(row.pkg);
                    } else {
                        cur.remove(row.pkg);
                    }
                    WatchService.setWatching(MainActivity.this, cur);
                }
            });
            llApps.addView(cb);
        }
    }
}
