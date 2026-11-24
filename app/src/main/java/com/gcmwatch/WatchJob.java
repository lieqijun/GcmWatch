package com.gcmwatch;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 保活兜底（不依赖 root）：每 15 分钟被系统唤醒一次，发现服务没在跑就重新拉起。
 *
 * 为什么不用 Xposed hook：hook 只能改运行期行为，进程被 MIUI 杀掉之后 hook 也随之消失，
 * 没法自救；而要 hook 系统进程还得装 LSPosed 并重启，风险远大于收益。
 * 真正能「进程死了还能拉起来」的只有进程之外的东西：JobScheduler、AlarmManager，
 * 或者 root 侧的看门狗脚本（见 MainActivity 里的「安装 root 保活」）。
 */
public class WatchJob extends JobService {

    static final int JOB_ID = 7711;

    @Override
    public boolean onStartJob(JobParameters params) {
        SharedPreferences sp = getSharedPreferences(WatchService.PREF, MODE_PRIVATE);
        if (sp.getBoolean("running", false)) {
            try {
                startForegroundService(new Intent(this, WatchService.class));
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void schedule(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, WatchJob.class))
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();
        try {
            js.schedule(job);
        } catch (Throwable ignored) {
        }
    }

    static void cancel(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js != null) {
            js.cancel(JOB_ID);
        }
    }
}
