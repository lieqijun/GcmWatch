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

    private static final String KEEPALIVE_PATH = "/data/adb/service.d/99-gcmwatch.sh";

    private TextView tvRoot;
    private TextView tvStatus;
    private TextView tvLog;
    private Button btnPerm;
    private Button btnToggle;
    private Button btnScan;
    private Button btnTest;
    private Button btnCheck;
    private Button btnKeep;
    private LinearLayout llApps;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean running = sp.getBoolean("running", false);
            String status = sp.getString("status", "未启动");
            tvStatus.setText((running ? "【运行中】 " : "【已停止】 ") + status);
            btnToggle.setText(running ? "停止监听" : "开始监听");
            tvLog.setText(Diag.dump());
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
        tvLog = findViewById(R.id.tvLog);
        btnPerm = findViewById(R.id.btnPerm);
        btnToggle = findViewById(R.id.btnToggle);
        btnScan = findViewById(R.id.btnScan);
        btnTest = findViewById(R.id.btnTest);
        btnCheck = findViewById(R.id.btnCheck);
        btnKeep = findViewById(R.id.btnKeep);
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
                    Diag.add(MainActivity.this, "开始监听，已排 JobScheduler 兜底");
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

        btnKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                installKeepAlive();
            }
        });

        checkRoot();

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
            Diag.add(this, "通知权限已授予");
        }
    }

    private void checkRoot() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.available();
                final boolean db = ok && GcmDb.dbExists();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            tvRoot.setText("root：未授权（点「读取应用列表」会弹 su）");
                        } else if (!db) {
                            tvRoot.setText("root：已授权 · 找不到 gcmstatus 数据库");
                        } else {
                            tvRoot.setText("root：已授权 · microG 数据库正常");
                        }
                    }
                });
            }
        }).start();
    }

    private void installKeepAlive() {
        btnKeep.setEnabled(false);
        Toast.makeText(this, "正在写入…（会弹 su）", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String script =
                        "#!/system/bin/sh\n"
                                + "# GcmWatch 保活看门狗（由 app 写入，Magisk service.d）\n"
                                + "(\n"
                                + "  sleep 20\n"
                                + "  while true; do\n"
                                + "    if ! pidof com.gcmwatch >/dev/null 2>&1; then\n"
                                + "      am start-foreground-service -n com.gcmwatch/.WatchService"
                                + " >/dev/null 2>&1\n"
                                + "      sleep 5\n"
                                + "      am startservice -n com.gcmwatch/.WatchService >/dev/null 2>&1\n"
                                + "    fi\n"
                                + "    sleep 60\n"
                                + "  done\n"
                                + ") &\n";
                final String err = RootShell.writeRootText(KEEPALIVE_PATH, script);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        btnKeep.setEnabled(true);
                        String msg = err == null
                                ? "已写入 " + KEEPALIVE_PATH + "，重启后生效"
                                : err;
                        Diag.add(MainActivity.this, "写保活脚本: " + msg);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
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
                            Diag.add(MainActivity.this, "读取列表失败: " + fError);
                            Toast.makeText(MainActivity.this, "失败：" + fError,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Diag.add(MainActivity.this, "读取列表成功，" + fRows.size() + " 个应用");
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
                    Diag.add(MainActivity.this, (isChecked ? "监听 " : "取消监听 ") + row.pkg);
                }
            });
            llApps.addView(cb);
        }
    }
}
