# 本项目体量极小，混淆没有收益，全部保留，避免反射/hook 入口被裁掉
-keep class com.gcmwatch.** { *; }

# 保留 Manifest 引用的组件（AGP 会自动加 keep，这里只是把它写明，防止规则变动）
-keep class com.gcmwatch.MainActivity { *; }
-keep class com.gcmwatch.WatchService { *; }
-keep class com.gcmwatch.WatchJob { *; }
-keep class com.gcmwatch.BootReceiver { *; }
-keep class com.gcmwatch.WakeReceiver { *; }

# 去掉日志调用的字符串拼接开销
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# ---- LSPosed / libxposed 模块规则（官方要求）----
# 保留 XposedModule 入口类，并在混淆后同步改写 java_init.list 里的类名
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# libxposed API 是 compileOnly，运行时才有，忽略缺失告警
-dontwarn io.github.libxposed.**
