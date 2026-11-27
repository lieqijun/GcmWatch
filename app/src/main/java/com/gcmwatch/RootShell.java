package com.gcmwatch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.TimeUnit;

/**
 * 极简 root 命令执行器。
 * - 二进制安全：stdout 原样返回 byte[]
 * - 带超时，避免 su 卡死拖住监听线程
 */
final class RootShell {

    static final int EXIT_NO_BINARY = -2;

    /** su 的常见位置；Magisk / KernelSU / APatch / 系统自带 */
    static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/debug_ramdisk/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/magisk/su",
            "/data/adb/ap/bin/su",
            "/data/adb/ksu/bin/su"
    };

    /** 首次授权要等用户在弹窗里点「允许」，所以不能太短 */
    private static final int TIMEOUT_SEC = 40;

    static final class Result {
        final int code;
        final byte[] out;
        final String err;

        Result(int code, byte[] out, String err) {
            this.code = code;
            this.out = out == null ? new byte[0] : out;
            this.err = err == null ? "" : err;
        }

        boolean ok() {
            return code == 0;
        }

        String text() {
            try {
                return new String(out, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return new String(out);
            }
        }
    }

    private RootShell() {
    }

    /**
     * 执行一条 root 命令。
     *
     * 关键：Android 11 起每个 app 进程都在自己的 mount namespace 里，
     * /data/data、/data/user、/data/user_de 都被 tmpfs 盖住，只把本 app 自己的目录
     * bind 回来。su 是在 app 进程里 fork 的，**继承的正是 app 的 namespace**，
     * 所以哪怕 uid=0，`ls /data/data/com.google.android.gms/...` 也只能得到
     * No such file or directory —— 路径在这个 namespace 里根本不存在。
     *
     * 所以先用 nsenter 切到 init(PID 1) 的 mount namespace 再跑，才是真的全局视角。
     * 探不到 nsenter 的机器（老系统 / su 不给 CAP_SYS_ADMIN）自动退回原样执行。
     */
    static Result run(String cmd) {
        return execRaw(wrap(cmd));
    }

    private static final String PROBE = "GCMWATCH_NSPROBE";

    /** null = 还没探测过 */
    private static Boolean needNs;

    private static boolean needNs() {
        if (needNs != null) {
            return needNs;
        }
        Result plain = execRaw("echo " + PROBE);
        if (!plain.ok() || !plain.text().contains(PROBE)) {
            // su 本身不通，由 diagnose() 去报「未授权」，这里不做判断
            needNs = Boolean.FALSE;
            return needNs;
        }
        Result ns = execRaw("nsenter -t 1 -m -- echo " + PROBE);
        needNs = ns.ok() && ns.text().contains(PROBE);
        return needNs;
    }

    /** 让下一次 run() 重新探测 nsenter：用户点顶部那行重测时用，避免一次探失败就永久降级 */
    static void resetProbe() {
        needNs = null;
    }

    private static String wrap(String cmd) {
        if (!needNs()) {
            return cmd;
        }
        // toybox 的 nsenter 需要显式给 shell，否则分号、管道都没人解释
        return "nsenter -t 1 -m -- /system/bin/sh -c " + sq(cmd);
    }

    private static Result execRaw(String cmd) {
        Result last = null;
        for (String binary : SU_CANDIDATES) {
            Result r = runOnce(binary, cmd);
            if (r.code != EXIT_NO_BINARY) {
                return r;
            }
            last = r;
        }
        // 所有绝对路径都 exec 失败：可能是本进程的 PATH / mount namespace 里看不到 su
        // （Magisk 的 DenyList、LSPosed 注入后的 unmount 都会造成这个现象）。
        // 再借 sh 找一次，sh 有自己的 PATH。
        Result viaSh = runOnce("/system/bin/sh", "su -c " + sq(cmd));
        if (viaSh.code != EXIT_NO_BINARY && viaSh.code != 127) {
            return viaSh;
        }
        return last == null ? new Result(EXIT_NO_BINARY, new byte[0], "su not found") : last;
    }

    /** 单引号包裹，内部单引号按 shell 规则转义 */
    private static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 快速判断 root 是否可用 */
    static boolean available() {
        Result r = run("id");
        return r.ok() && r.text().contains("uid=0");
    }

    /** 读取一个文件的内容（不存在或有权限问题时 code != 0） */
    static Result cat(String path) {
        return run("cat '" + path + "' 2>/dev/null");
    }

    private static Result runOnce(String binary, String cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(binary, "-c", cmd);
            pb.redirectErrorStream(false);
            p = pb.start();

            final InputStream es = p.getErrorStream();
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread errThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain(es, errBuf);
                }
            });
            errThread.setDaemon(true);
            errThread.start();

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            drain(p.getInputStream(), outBuf);

            boolean finished = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                return new Result(-1, outBuf.toByteArray(), "timeout");
            }
            try {
                errThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new Result(p.exitValue(), outBuf.toByteArray(), errText(errBuf));
        } catch (java.io.IOException e) {
            // su 二进制不存在
            return new Result(EXIT_NO_BINARY, new byte[0], String.valueOf(e));
        } catch (Throwable t) {
            return new Result(-1, new byte[0], String.valueOf(t));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String errText(ByteArrayOutputStream buf) {
        try {
            return new String(buf.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把 root 那边的文件内容抓成本地文件，返回是否成功 */
    static boolean pullTo(String srcPath, File dst) {
        Result r = cat(srcPath);
        if (!r.ok() || r.out.length == 0) {
            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            return false;
        }
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
            try {
                fo.write(r.out);
                fo.flush();
            } finally {
                fo.close();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
