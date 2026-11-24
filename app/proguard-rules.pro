# 没有反射、没有第三方库，默认规则就够。
# 保留 Manifest 引用的组件（AGP 会自动加 keep，这里只是把它写明，防止规则变动）
-keep class com.gcmwatch.MainActivity { *; }
-keep class com.gcmwatch.WatchService { *; }
-keep class com.gcmwatch.WatchJob { *; }
-keep class com.gcmwatch.BootReceiver { *; }
-keep class com.gcmwatch.WakeReceiver { *; }

# 去掉日志调用的字符串拼接开销（本项目只用自定义 Diag，不调用 android.util.Log）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
