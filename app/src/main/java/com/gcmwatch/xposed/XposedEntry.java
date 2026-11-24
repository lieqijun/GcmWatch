package com.gcmwatch.xposed;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LSPosed 模块入口（libxposed API 102，作用于 system_server）。
 *
 * 只做一件事：让 com.gcmwatch 的进程杀不死、被杀自动重启。
 *
 *  1) 拦下系统层面的「计划性」杀进程入口（清理后台 / force-stop / 释放内存），
 *     命中本应用时直接返回，不真的杀。
 *  2) 把本应用的 ProcessRecord 标记成 persistent，这样即使被内核层强杀（lmkd），
 *     system_server 发现进程死亡后也会自己把它拉回来。
 *
 * 注意：这套 hook 的写法和 hook 点来自 smscode-to-ntfy 项目，这里额外做了两件事：
 *  - 被拦下的方法按真实返回类型给默认值（killPackageProcessesLocked 返回 boolean，
 *    直接返回 null 会在系统侧拆箱 NPE，那会把 system_server 搞崩）；
 *  - ProcessRecord 的 persistent 字段名两个候选都试，找不到就放弃，不抛异常。
 */
public class XposedEntry extends XposedModule {

    private static final String TAG = "GcmWatch-LSP";
    private static final String TARGET = "com.gcmwatch";

    /** 只打一次「已标记 persistent」，避免刷屏 */
    private boolean persistentLogged;

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader cl = param.getClassLoader();
        hookKillGuards(cl);
        hookAutoRestart(cl);
    }

    // ---------------- 杀不死 ----------------

    private void hookKillGuards(ClassLoader cl) {
        final Class<?> ams;
        try {
            ams = Class.forName("com.android.server.am.ActivityManagerService", false, cl);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "找不到 ActivityManagerService：" + t);
            return;
        }

        String[] names = {
                "forceStopPackage",
                "killBackgroundProcesses",
                "killApplicationProcess",
                "killApplication",
                "killPackageProcessesLocked",
                "killPackageProcesses"
        };

        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            hookAll(ams, name, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (mentionsTarget(chain.getArgs())) {
                        log(Log.INFO, TAG, "拦下 " + name + "（不放行）");
                        return defaultValue(chain);
                    }
                    return chain.proceed();
                }
            });
        }
    }

    /** 某次杀进程调用的参数里是否点名了本应用 */
    private static boolean mentionsTarget(List<Object> args) {
        if (args == null) {
            return false;
        }
        for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof String) {
                String s = (String) a;
                if (s.equals(TARGET) || s.startsWith(TARGET + ":")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 被拦下的方法要按真实返回类型给默认值，不能一律 null */
    private static Object defaultValue(XposedInterface.Chain chain) {
        Executable ex = chain.getExecutable();
        Class<?> rt = ex instanceof Method ? ((Method) ex).getReturnType() : void.class;
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        if (rt == short.class) {
            return (short) 0;
        }
        if (rt == byte.class) {
            return (byte) 0;
        }
        if (rt == char.class) {
            return (char) 0;
        }
        if (rt == float.class) {
            return 0f;
        }
        if (rt == double.class) {
            return 0d;
        }
        return null;
    }

    // ---------------- 被杀自动重启 ----------------

    private void hookAutoRestart(ClassLoader cl) {
        final Class<?> processList;
        try {
            processList = Class.forName("com.android.server.am.ProcessList", false, cl);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "找不到 ProcessList：" + t);
            return;
        }

        hookAll(processList, "startProcessLocked", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                markPersistent(result);
                return result;
            }
        });
    }

    /** 若刚起来的进程属于本应用，就把它的 ProcessRecord 标成 persistent */
    private void markPersistent(Object processRecord) {
        if (processRecord == null) {
            return;
        }
        try {
            Object info = getField(processRecord, "info");
            if (!(info instanceof ApplicationInfo)) {
                return;
            }
            if (!TARGET.equals(((ApplicationInfo) info).packageName)) {
                return;
            }
            if (setBooleanField(processRecord, "persistent")
                    || setBooleanField(processRecord, "mPersistent")) {
                if (!persistentLogged) {
                    persistentLogged = true;
                    log(Log.INFO, TAG, "已把本应用进程标记为 persistent（被杀自动重启）");
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "标记 persistent 失败：" + t);
        }
    }

    // ---------------- 反射工具 ----------------

    private static Object getField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                // 字段不在这里，往上找父类
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 找到并写入 boolean 字段；字段不存在返回 false */
    private static boolean setBooleanField(Object obj, String name) {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                if (f.getType() != boolean.class) {
                    return false;
                }
                f.setAccessible(true);
                f.setBoolean(obj, true);
                return true;
            } catch (Throwable ignored) {
                // 往上找
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** 按方法名 hook 一个类（含父类）里所有同名重载 */
    private void hookAll(Class<?> clazz, String name, XposedInterface.Hooker hooker) {
        Class<?> c = clazz;
        int n = 0;
        while (c != null && c != Object.class) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                c = c.getSuperclass();
                continue;
            }
            for (int i = 0; i < methods.length; i++) {
                Method m = methods[i];
                if (!m.getName().equals(name)) {
                    continue;
                }
                try {
                    deoptimize(m);
                    hook(m).intercept(hooker);
                    n++;
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "hook " + name + " 失败：" + t);
                }
            }
            c = c.getSuperclass();
        }
        log(Log.INFO, TAG, "hook " + name + " → " + n + " 个重载");
    }
}
