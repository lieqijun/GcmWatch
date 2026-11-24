package com.gcmwatch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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
    private Button btnKeep;
    private Button btnBattery;
    private LinearLayout llApps;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;

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
        btnKeep = findViewById(R.id.btnKeep);
        btnBattery = findViewById(R.id.btnBattery);
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

        btnKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModuleHelp();
            }
        });

        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askBatteryWhitelist();
            }
        });

        // 之前开过监听、但进程被清了：一打开界面就自动把服务拉回来
        if (sp.getBoolean("running", false)) {
            startForegroundService(new Intent(this, WatchService.class));
            WatchJob.schedule(this);
        }

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

    private void askBatteryWhitelist() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "已在电池白名单里", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打不开白名单页面：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void showModuleHelp() {
        String msg = "1. 手机需已安装 LSPosed（新版，支持 libxposed API 101/102）\n\n"
                + "2. 打开 LSPosed 管理器 → 模块 → 找到「GCM 消息监听」并启用\n\n"
                + "3. 点该模块的「作用域」，只勾【系统框架】，保存\n\n"
                + "4. 重启手机（或重启系统框架）才生效\n\n"
                + "生效后：清理后台杀不掉本应用；被系统强杀后会自动重启。\n\n"
                + "在 LSPosed 管理器的「日志」里过滤 GcmWatch-LSP，能看到每条 hook 结果。";
        new android.app.AlertDialog.Builder(this)
                .setTitle("LSPosed 保活怎么开")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
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
