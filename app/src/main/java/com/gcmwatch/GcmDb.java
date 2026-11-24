package com.gcmwatch;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 读 microG 的 GCM 状态库。
 *
 * 库文件: /data/data/com.google.android.gms/databases/gcmstatus
 * 表:     apps(package_name, last_message_timestamp, total_message_count,
 *              total_message_bytes, allow_register, wake_for_delivery)
 *
 * microG 在 McsService.handleAppMessage() 的第一行就调用 noteAppMessage()，
 * 也就是消息一到达就记账，和目标应用有没有被唤醒、广播有没有投递成功无关。
 */
final class GcmDb {

    static final String DB_PATH = "/data/data/com.google.android.gms/databases/gcmstatus";

    static final class Row {
        String pkg = "";
        long timestamp;
        long count;
        long bytes;
    }

    private GcmDb() {
    }

    /**
     * 数据库签名，用于判断有没有变化。
     * 优先看 -wal（microG 开启了 WAL），它每次写入都会变。
     */
    static String stamp() {
        String cmd = "stat -c '%Y:%s' " + DB_PATH + "-wal 2>/dev/null; "
                + "stat -c '%Y:%s' " + DB_PATH + " 2>/dev/null";
        RootShell.Result r = RootShell.run(cmd);
        if (!r.ok()) {
            return "";
        }
        return r.text().trim();
    }

    static boolean dbExists() {
        RootShell.Result r = RootShell.run("ls -l " + DB_PATH + " 2>/dev/null");
        return r.ok() && r.text().contains(DB_PATH);
    }

    /**
     * 拷贝数据库到 app 自己的缓存目录再查询。
     * 必须用 cat 走 stdout，而不是 cp：cp 过来的文件带着别的 SELinux 标签，本进程读不了。
     */
    static List<Row> read(Context ctx) throws Exception {
        File dir = new File(ctx.getCacheDir(), "gcmdb");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建缓存目录");
        }

        File main = new File(dir, "gcmstatus");
        File wal = new File(dir, "gcmstatus-wal");
        File shm = new File(dir, "gcmstatus-shm");

        boolean gotMain = RootShell.pullTo(DB_PATH, main);
        if (!gotMain) {
            throw new Exception("读不到 " + DB_PATH + "（root 未授权？路径不对？）");
        }
        boolean gotWal = RootShell.pullTo(DB_PATH + "-wal", wal);
        RootShell.pullTo(DB_PATH + "-shm", shm);

        try {
            return query(main);
        } catch (Throwable first) {
            // 抓 WAL 时如果正好撞上写入，快照可能不完整，退回只读主库
            if (gotWal) {
                //noinspection ResultOfMethodCallIgnored
                wal.delete();
                //noinspection ResultOfMethodCallIgnored
                shm.delete();
                try {
                    return query(main);
                } catch (Throwable ignored) {
                }
            }
            throw new Exception("数据库解析失败: " + first);
        }
    }

    private static List<Row> query(File dbFile) {
        List<Row> result = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            c = db.rawQuery(
                    "SELECT package_name, last_message_timestamp, total_message_count, total_message_bytes "
                            + "FROM apps", null);
            while (c.moveToNext()) {
                Row row = new Row();
                row.pkg = c.isNull(0) ? "" : c.getString(0);
                row.timestamp = c.isNull(1) ? 0 : c.getLong(1);
                row.count = c.isNull(2) ? 0 : c.getLong(2);
                row.bytes = c.isNull(3) ? 0 : c.getLong(3);
                if (row.pkg.length() > 0) {
                    result.add(row);
                }
            }
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }
}
