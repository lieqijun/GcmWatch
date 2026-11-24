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

    /** su 的常见位置；Magisk / KernelSU / 系统自带 */
    private static final String[] SU_CANDIDATES = {
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su"
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

    static Result run(String cmd) {
        Result last = null;
        for (String binary : SU_CANDIDATES) {
            Result r = runOnce(binary, cmd);
            if (r.code != EXIT_NO_BINARY) {
                return r;
            }
            last = r;
        }
        return last == null ? new Result(EXIT_NO_BINARY, new byte[0], "su not found") : last;
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
