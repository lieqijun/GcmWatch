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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private TextView tvRoot;
    private TextView tvStatus;
    private Button btnPerm;
    private Button btnToggle;
    private Button btnScan;
    private LinearLayout llApps;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            boolean running = sp.getBoolean("running", false);
            String status = sp.getString("status", "未启动");
            tvStatus.setText((running ? "【运行中】" : "【已停止】") + status);
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
        llApps = findViewById(R.id.llApps);

        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askNotificationPermission();
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sp.getBoolean("running", false)) {
                    stopService(new Intent(MainActivity.this, WatchService.class));
                } else {
                    Intent i = new Intent(MainActivity.this, WatchService.class);
                    startForegroundService(i);
                }
            }
        });

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });

        checkRoot();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                btnPerm.setVisibility(View.VISIBLE);
            } else {
                btnPerm.setVisibility(View.GONE);
            }
        } else {
            btnPerm.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(statusTick);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            btnPerm.setVisibility(View.GONE);
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
                            tvRoot.setText("root：未授权（点了「读取列表」会弹 su 授权框）");
                        } else if (!db) {
                            tvRoot.setText("root：已授权 · 但找不到 microG 的 gcmstatus 数据库");
                        } else {
                            tvRoot.setText("root：已授权 · microG 数据库正常");
                        }
                    }
                });
            }
        }).start();
    }

    private void scan() {
        btnScan.setEnabled(false);
        llApps.removeAllViews();
        Toast.makeText(this, "读取中…（会弹 su 授权）", Toast.LENGTH_SHORT).show();

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
                            Toast.makeText(MainActivity.this, "失败：" + fError, Toast.LENGTH_LONG).show();
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
            tv.setText("数据库里没有任何应用记录。说明 microG 还没收到过任何推送。");
            llApps.addView(tv);
            return;
        }

        final Set<String> watching = WatchService.getWatching(this);

        for (final GcmDb.Row row : rows) {
            CheckBox cb = new CheckBox(this);
            String label = WatchService.appLabel(this, row.pkg);
            cb.setText(label + "  (" + row.pkg + ")\n已收 " + row.count + " 条 / " + row.bytes + " 字节");
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
