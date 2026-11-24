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
 * microG 源码（org.microg.gms.gcm.GcmDatabase）里 DB_NAME = "gcmstatus"，
 * 走 SQLiteOpenHelper，所以真实路径是
 *     /data/data/<gms包名>/databases/gcmstatus
 * gms 包名不一定是 com.google.android.gms（各种修改版会换包名），所以这里逐个探。
 *
 * 表 apps(package_name, last_error, last_message_timestamp,
 *         total_message_count, total_message_bytes, allow_register, wake_for_delivery)
 *
 * microG 在 McsService.handleAppMessage() 的第一行就调用 noteAppMessage()，
 * 也就是消息一到达就记账，和目标应用有没有被唤醒、广播有没有投递成功无关。
 */
final class GcmDb {

    /** v1.3 用的固定路径，保持第一优先 */
    private static final String DEFAULT_PATH = "/data/data/com.google.android.gms/databases/gcmstatus";

    /** 可能出现 gcmstatus 的包名，按可能性排序 */
    private static final String[] PKGS = {
            "com.google.android.gms",
            "com.microg.gms",
            "app.revanced.android.gms",
            "com.mgoogle.android.gms",
    };

    private static final String[] DB_NAMES = {"gcmstatus", "gcmstatus.db"};

    /** 探测到的库路径，进程内缓存 */
    private static String cachedPath;

    private GcmDb() {
    }

    /** 返回库文件的绝对路径，找不到返回 null */
    static String path() {
        if (cachedPath != null) {
            return cachedPath;
        }
        // 第一优先：v1.3 用的固定路径，判断方式也照旧（ls 成功且输出里有这个路径）
        if (existsLikeV13(DEFAULT_PATH)) {
            cachedPath = DEFAULT_PATH;
            return cachedPath;
        }

        // 兜底：换个包名 / 文件名再找（各发行版可能改包名）
        // 命令末尾一定要有 true：最后一条 [ -f ] 失败会把整条命令的退出码变成 1，
        // 而命中行已经打在 stdout 里了，不该因为退出码被丢掉。
        StringBuilder cmd = new StringBuilder();
        for (String pkg : PKGS) {
            for (String name : DB_NAMES) {
                cmd.append("[ -f '/data/data/").append(pkg).append("/databases/").append(name)
                        .append("' ] && echo '").append(pkg).append('/').append(name).append("'; ");
            }
        }
        cmd.append("true");
        RootShell.Result r = RootShell.run(cmd.toString());
        String best = null;
        for (String line : r.text().split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (best == null) {
                best = s;
            }
            if (s.endsWith("/gcmstatus")) {
                best = s;
                break;
            }
        }
        if (best != null) {
            cachedPath = "/data/data/" + best;
        }
        return cachedPath;
    }

    private static boolean existsLikeV13(String p) {
        RootShell.Result r = RootShell.run("ls -l '" + p + "' 2>/dev/null");
        return r.ok() && r.text().contains(p);
    }

    /** 库文件是否已经生成（microG 从没收到过推送时，这个文件可能还不存在） */
    static boolean dbExists() {
        return path() != null;
    }

    /**
     * 数据库签名，用于判断有没有变化。
     * 优先看 -wal（microG 开了 WAL），它每次写入都会变。
     * 只看 stdout，不看退出码：-wal 不存在时 stat 会返回非 0，但主库那行仍然有效。
     */
    static String stamp() {
        String p = path();
        if (p == null) {
            return "";
        }
        RootShell.Result r = RootShell.run(
                "stat -c '%Y:%s' '" + p + "-wal' 2>/dev/null; "
                        + "stat -c '%Y:%s' '" + p + "' 2>/dev/null; true");
        return r.text().trim();
    }

    /**
     * 给界面看的诊断文本：root 通不通、库在哪、找不到时各候选包的 databases 目录长什么样。
     */
    static String diagnose() {
        StringBuilder sb = new StringBuilder();
        RootShell.Result id = RootShell.run("id");
        boolean root = id.ok() && id.text().contains("uid=0");
        if (!root) {
            sb.append("root：未授权");
            String msg = oneLine(id.err.isEmpty() ? id.text() : id.err);
            if (msg.length() > 0) {
                sb.append(" · ").append(clip(msg, 90));
            }
            // 关键判据：这些文件在本进程里到底看不看得见。
            // 全都「无」而 Magisk 里已经给了授权 → su 被 Magisk 的 DenyList / LSPosed 的
            // unmount 藏起来了，不是没装 root。
            sb.append("\nsu 文件：");
            String[] probe = {"/system/bin/su", "/debug_ramdisk/su", "/system/xbin/su"};
            for (String p : probe) {
                sb.append(p).append(new File(p).exists() ? " 有  " : " 无  ");
            }
            sb.append("\n弹窗点「允许」；3 秒后自动重测，也可点这行手动重测");
            return sb.toString();
        }
        sb.append("root：已授权");

        String p = path();
        if (p != null) {
            sb.append(" · 数据库 ").append(p.replace("/data/data/", ""));
            return sb.toString();
        }

        cachedPath = null;
        sb.append(" · 没找到 gcmstatus\n下面是各包 databases 目录：");
        for (String pkg : PKGS) {
            RootShell.Result r = RootShell.run(
                    "ls '/data/data/" + pkg + "/databases' 2>&1 | tr '\\n' ' '");
            String ls = oneLine(r.text());
            if (ls.contains("No such file")) {
                ls = "（无此包）";
            } else if (ls.trim().isEmpty()) {
                ls = "（空）";
            }
            sb.append("\n").append(pkg).append("：").append(clip(ls, 90));
        }
        return sb.toString();
    }

    /**
     * 拷贝数据库到 app 自己的缓存目录再查询。
     * 必须用 cat 走 stdout，而不是 cp：cp 过来的文件带着别的 SELinux 标签，本进程读不了。
     *
     * 抓快照时如果正好撞上 microG 写入，-wal 可能拿到半截内容，查询会失败。
     * 所以失败就重抓重试 3 次，而不是去删 -wal（删了会把未合并的数据丢掉）。
     */
    static List<Row> read(Context ctx) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return readOnce(ctx);
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new Exception("未知错误") : last;
    }

    private static List<Row> readOnce(Context ctx) throws Exception {
        String dbPath = path();
        if (dbPath == null) {
            throw new Exception("没找到 gcmstatus（点顶部文字看诊断）");
        }

        File dir = new File(ctx.getCacheDir(), "gcmdb");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建缓存目录");
        }

        File main = new File(dir, "gcmstatus");
        File wal = new File(dir, "gcmstatus-wal");
        File shm = new File(dir, "gcmstatus-shm");

        // 每次都清空旧的 -wal/-shm，避免上一次的残留和这次的主库不匹配
        //noinspection ResultOfMethodCallIgnored
        wal.delete();
        //noinspection ResultOfMethodCallIgnored
        shm.delete();

        if (!RootShell.pullTo(dbPath, main)) {
            cachedPath = null;
            throw new Exception("读不到 " + dbPath + "（su 被拒绝？）");
        }
        boolean gotWal = RootShell.pullTo(dbPath + "-wal", wal);
        boolean gotShm = RootShell.pullTo(dbPath + "-shm", shm);

        if (!gotWal || !gotShm) {
            // 只有主库也能查，但没有 WAL 会漏掉最近的写入
            //noinspection ResultOfMethodCallIgnored
            wal.delete();
            //noinspection ResultOfMethodCallIgnored
            shm.delete();
        }
        return query(main);
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

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static final class Row {
        String pkg = "";
        long timestamp;
        long count;
        long bytes;
    }
}
