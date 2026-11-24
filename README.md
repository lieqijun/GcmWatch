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

## v1.6 变化（su 被 DenyList / Zygisk 藏起来）

现象：`Cannot run program "/debug_ramdisk/su": error=2`，所有候选路径都 ENOENT，但 Magisk 里已经勾了授权。

- 界面新增一行 `su 文件：/system/bin/su 有/无 …`，直接判断这三个文件在当前进程里看不看得见
- su 候选增加 `/data/adb/magisk/su`、`/data/adb/ap/bin/su`、`/data/adb/ksu/bin/su`
- 绝对路径全失败时，借 `/system/bin/sh` 用自己的 PATH 再找一次 su
- 处理办法：Magisk 设置里关掉「强制执行拒绝列表（Enforce DenyList）」，并确认「配置拒绝列表」里没有勾本应用，然后强制停止应用再打开

## v1.5 变化（修 v1.4 读不到列表）

**根因**：v1.4 的多包名探测把「shell 退出码」当成了「su 是否成功」。命令末尾最后一个 `[ -f ]` 不成立 → 退出码 1 → 已经命中并打在 stdout 里的路径被整条丢弃。

- 改回 v1.3 的固定路径优先：`/data/data/com.google.android.gms/databases/gcmstatus`，判断方式与 v1.3 一致
- 多包名探测降到兜底，命令末尾加 `true`，只解析 stdout，不看退出码
- `stamp()` 同样不再用退出码判断
- **未授权时每 3 秒自动重测，最多 6 次**（重装后 UID 变了要重新授权，点完「允许」不再需要重启或切页面）
- 每次回到界面重新计数；点顶部那行也能手动重测

## v1.4 变化

- 界面去掉「LSPosed 保活」「电池白名单」两个按钮，只留开始监听 / 立即检查 / 读取应用列表 / 测试通知
- root 检测重做：su 超时 20s → 40s；顶部一行显示 su 真实结果；找不到库时列出各包 `databases` 目录内容
- su 查找增加 `/system/xbin/su`、`/sbin/su`、`/debug_ramdisk/su`
- 去掉 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限

## v1.3 变化（保活，已不在界面上露出）

- 保活用 LSPosed（libxposed API 102，作用于 system_server）：
  - 拦下 `forceStopPackage` / `killBackgroundProcesses` / `killApplication` / `killApplicationProcess` / `killPackageProcesses(Locked)` 对本应用的调用
  - hook `ProcessList.startProcessLocked`，把本应用的 `ProcessRecord` 标成 `persistent`
- 代码还在，但不想要可以完全不管，不影响主流程

## 需要什么

- root（Magisk / KernelSU），并授权给本 app
- microG，且「Google 云端消息推送」里目标应用已经注册成功
- 不需要 Thanox；LSPosed 可选

## 可选：启用 LSPosed 保活模块

1. LSPosed 管理器 → 模块 → 找到「GCM 消息监听」→ 启用
2. 作用域 → 只勾 **系统框架** → 保存
3. 重启手机

启用后清理后台杀不掉本应用；被强杀会自动重启。不启用也能用，只是被系统清后台后要靠系统自己拉回来。

## 编译与发布（GitHub Actions）

- push 到 `main`：只构建，产物在 Actions 的 Artifacts 里（`gcmwatch-debug.zip`）
- push 一个 `v*` tag：构建后自动创建 Release，并把 APK 作为附件上传（说明文字取 `RELEASE_NOTES.md`）

```bash
git tag -a v1.6 -m "GcmWatch v1.6" && git push origin refs/tags/v1.6
```


## 使用

1. 打开 app，顶部那行应显示「root：已授权 · 数据库 …」；不是就点它，弹 su 点允许
2. 点「读取应用列表」，勾选要监听的应用（默认已勾 `com.tencent.mm`）
3. 点「开始监听」
4. MIUI：本 app **省电策略 → 无限制**，**自启动 → 允许**
5. 用另一台手机发一条微信

**注意第一次不会响。** 首次运行只是建立基线，之后新增的推送才通知。

## 已知限制

- 轮询间隔 8 秒，通知最多延迟 8 秒。想更快就改 `WatchService.POLL_MS`
- WAL 抓取时撞上 microG 写入，快照可能不完整，会自动退回只读主库（那一次可能漏，下一条补差额）
- microG 数据目录既不是 `com.google.android.gms` 也不是 `com.microg.gms` 等候选包名时读不到（界面会列出实际内容）
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
