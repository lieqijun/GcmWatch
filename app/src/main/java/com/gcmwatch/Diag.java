package com.gcmwatch;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 极简日志：内存环形缓冲 + 外部文件（/sdcard/Android/data/com.gcmwatch/files/log/run.log）。
 * 目的是让「不弹通知」这种问题在手机界面上就能自证，不用连 adb。
 */
final class Diag {

    private static final int MAX_LINES = 200;
    private static final long MAX_FILE = 256 * 1024L;

    private static final ArrayDeque<String> BUF = new ArrayDeque<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Diag() {
    }

    static synchronized void add(Context ctx, String msg) {
        String line = TS.format(new Date()) + "  " + msg;
        BUF.addLast(line);
        while (BUF.size() > MAX_LINES) {
            BUF.removeFirst();
        }
        appendFile(ctx, line);
    }

    static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : BUF) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    static synchronized void clear() {
        BUF.clear();
    }

    private static void appendFile(Context ctx, String line) {
        if (ctx == null) {
            return;
        }
        try {
            File dir = ctx.getExternalFilesDir("log");
            if (dir == null) {
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "run.log");
            if (f.length() > MAX_FILE) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(line);
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // 没有外部存储就只留内存日志
        }
    }

    static File logFile(Context ctx) {
        File dir = ctx.getExternalFilesDir("log");
        return dir == null ? null : new File(dir, "run.log");
    }
}
