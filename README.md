# GcmWatch — 用 microG 的推送计数做「微信代收」

微信完全冻结、零进程，也能收到「有新消息」的通知。

## v1.1 变化

- **修 bug**：基线建立时如果 `com.tencent.mm` 还没有记录，旧逻辑 `oldCount >= 0` 会让**第一条消息永远不通知**。现在按 0 处理，首条就通知。
- **自带日志**：界面底部滚动显示运行日志，同时写到 `Android/data/com.gcmwatch/files/log/run.log`，不用连 adb 也能判断卡在哪一步。
- **测试通知**：不依赖 root 和 microG，单独验证通知通道。
- **立即检查**：跳过 8 秒轮询周期，马上抓一次。
- **双保活**：JobScheduler 每 15 分钟兜底 + 可一键写入 Magisk `service.d` 看门狗脚本（进程死了 60 秒内拉回）。
- **R8**：debug 包也开 minify + shrinkResources，dex 合并成单个。

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

- 不 hook 系统，不改 system_server，不碰 LSPosed
- 不启动微信，微信可以一直冻着
- 拿不到消息内容（`app_data` 是密文），只能提示「有新消息」

## 需要什么

- root（Magisk / KernelSU 都行），授权本 app 使用 su
- microG，且「Google 云端消息推送」里目标应用已经注册成功
- 不需要 LSPosed，不需要 Thanox

## 编译（GitHub Actions）

1. 新建一个空仓库，把本目录下所有文件推上去（`.github/workflows/build.yml` 必须在仓库根）
2. push 后自动触发，打开仓库的 **Actions** → 最新一次 `build` → 底部 **Artifacts** → 下载 `gcmwatch-debug.zip`
3. 解压得到 `app-debug.apk`，装到手机上

也可以在 Actions 页面点 **Run workflow** 手动触发。

## 使用

1. 打开 app，点「读取 microG 应用列表」，弹出 su 授权就同意
2. 列表里会列出所有在 microG 里注册过的应用，勾选要监听的（默认已勾 `com.tencent.mm`）
3. 点「开始监听」
4. 去 MIUI 设置里给本 app：**省电策略 → 无限制**，**自启动 → 允许**（否则 MIUI 会杀掉常驻服务）
5. 用另一台手机发一条微信

**注意第一次不会响。** 首次运行只是建立基线，之后新增的推送才通知。所以发消息前先让它跑一轮（12 秒）。

## 已知限制

- 轮询间隔 12 秒，通知最多延迟 12 秒。想更快就改 `WatchService.POLL_MS`
- WAL 文件抓取时如果正好撞上 microG 写入，快照可能不完整，代码会自动退回只读主库（那一次可能漏掉，下一条消息会补上差额）
- microG 装的位置不是 `/data/data/com.google.android.gms` 时读不到，报错会写清楚
- 只在 MIUI 14 / Android 13 上设计，其他版本没测

## 目录

```
.github/workflows/build.yml         CI
app/src/main/java/com/gcmwatch/
    MainActivity.java               界面：root 检查、启停、勾选应用
    WatchService.java               前台服务：轮询 + 弹通知
    GcmDb.java                      读 microG 的 gcmstatus 库
    RootShell.java                  执行 su 命令、二进制安全地取 stdout
    BootReceiver.java               开机自启
```
