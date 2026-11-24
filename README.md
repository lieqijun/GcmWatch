# GcmWatch — 用 microG 的推送计数做「微信代收」

微信完全冻结、零进程，也能收到「有新消息」的通知。

## 原理

microG 的 `McsService.handleAppMessage()` 第一行就是：

```java
database.noteAppMessage(msg.category, DataMessageStanza.ADAPTER.encodedSize(msg));
```

**消息一到达就记账**，跟微信有没有被唤醒、广播能不能投递成功完全无关。记账结果落在：

```
/data/data/com.google.android.gms/databases/gcmstatus
表 apps(
  package_name, last_message_timestamp, total_message_count,
  total_message_bytes, allow_register, wake_for_delivery
)
```

这个 app 就是拿 root 轮询这张表：`total_message_count` 涨了就弹一条通知。

- 不启动微信，微信可以一直冻着
- 拿不到消息内容（`app_data` 是密文），只能提示「有新消息」

## v1.3 变化

- **保活改用 LSPosed**（libxposed API 102，作用于 system_server）：
  - 拦下 `forceStopPackage` / `killBackgroundProcesses` / `killApplication` / `killApplicationProcess` / `killPackageProcesses(Locked)` 对本应用的调用 → 清理后台杀不掉
  - hook `ProcessList.startProcessLocked`，把本应用的 `ProcessRecord` 标成 `persistent` → 被内核层强杀后 system_server 自动拉起
- **删掉 Magisk `service.d` 看门狗脚本**（实测拉不起来，`am start-foreground-service` / 广播 / monkey 三条路都没成功）
- 删掉应用内日志、删掉 `/data/local/tmp` 日志
- 通知文案只报「哪个应用有新消息」，不带条数

## 需要什么

- root（Magisk / KernelSU）+ **LSPosed**（需支持 libxposed API 101/102，即 LSPosed 1.9.3 之后的版本）
- microG，且「Google 云端消息推送」里目标应用已经注册成功
- 不需要 Thanox

## 装完必做：启用模块

1. 打开 **LSPosed 管理器** → 模块 → 找到「GCM 消息监听」→ 启用
2. 点该模块的「作用域」→ 只勾 **系统框架** → 保存
3. 重启手机（或重启系统框架）
4. 在 LSPosed 的「日志」里过滤 `GcmWatch-LSP`，能看到每条 hook 的结果：
   ```
   hook forceStopPackage → 1 个重载
   hook startProcessLocked → 2 个重载
   已把本应用进程标记为 persistent（被杀自动重启）
   ```

## 编译与发布（GitHub Actions）

- push 到 `main`：只构建，产物在 Actions 的 Artifacts 里（`gcmwatch-debug.zip`）
- push 一个 `v*` tag：构建后自动创建 Release，并把 APK 作为附件上传（说明文字取 `RELEASE_NOTES.md`）

```bash
git tag -a v1.3 -m "GcmWatch v1.3" && git push origin refs/tags/v1.3
```


## 使用

1. 打开 app，点「读取 microG 应用列表」，弹出 su 授权就同意
2. 勾选要监听的应用（默认已勾 `com.tencent.mm`）
3. 点「开始监听」
4. MIUI：本 app **省电策略 → 无限制**，**自启动 → 允许**
5. 用另一台手机发一条微信

**注意第一次不会响。** 首次运行只是建立基线，之后新增的推送才通知。

## 已知限制

- 轮询间隔 8 秒，通知最多延迟 8 秒。想更快就改 `WatchService.POLL_MS`
- WAL 抓取时撞上 microG 写入，快照可能不完整，会自动退回只读主库（那一次可能漏，下一条补差额）
- microG 数据目录不是 `/data/data/com.google.android.gms` 时读不到
- 只在 MIUI 14 / Android 13 上设计

## 出事怎么救

如果改完开机异常，用 adb 停掉 LSPosed 再重启：

```
adb shell su -c "touch /data/adb/modules/zygisk_lsposed/disable"
adb reboot
```

## 目录

```
app/src/main/resources/META-INF/xposed/   LSPosed 模块元数据（module.prop / scope.list / java_init.list）
app/src/main/java/com/gcmwatch/
    xposed/XposedEntry.java      LSPosed 入口：system_server 保活 hook
    MainActivity.java            界面：root 检查、启停、勾选应用
    WatchService.java            前台服务：轮询 + 弹通知
    GcmDb.java                   读 microG 的 gcmstatus 库
    RootShell.java               执行 su 命令、二进制安全地取 stdout
    BootReceiver.java            开机自启
    WatchJob.java                JobScheduler 兜底
```
