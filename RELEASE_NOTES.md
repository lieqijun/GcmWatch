## 这个版本换掉了保活方案

上一个版本靠 Magisk `service.d` 脚本看门狗拉起进程，**实测完全无效**：`/data/local/tmp/gcmwatch.log` 根本没生成，手动执行脚本也拉不起 app。这套东西已经整体删除。

改用 LSPosed，hook 在 system_server 里，做两件事：

**1. 杀不死** —— 拦截 AMS 上对本应用的杀进程调用：

```
forceStopPackage
killBackgroundProcesses
killApplicationProcess
killApplication
killPackageProcessesLocked
killPackageProcesses
```

**2. 被杀自动重启** —— hook `ProcessList.startProcessLocked`，把本应用的 `ProcessRecord.persistent` 置 true。即使被 lmkd 在内核层 SIGKILL（上面那些 hook 拦不到的路径），system_server 发现进程死亡后也会自动重新拉起。

被拦下的方法按**真实返回类型**给默认值（`killPackageProcessesLocked` 返回 boolean），不是一律 null —— 否则系统侧拆箱 NPE 会把 system_server 搞崩。

## 其他变化

- 删掉应用内日志、删掉 `/data/local/tmp` 日志
- 删掉「安装 root 保活」按钮，改成 LSPosed 配置说明
- 通知只报「哪个应用有新消息」，不带条数
- `JobScheduler` 15 分钟兜底保留，和 LSPosed 不冲突

## 安装后必做

1. LSPosed 管理器 → 模块 → 启用「GCM 消息监听」
2. 作用域 → 只勾 **系统框架** → 保存
3. **重启手机**
4. 验证：清后台后 30 秒执行 `adb shell pidof com.gcmwatch`，有 PID 就是生效

日志在 LSPosed 管理器的「日志」页，过滤 `GcmWatch-LSP`。

## 要求

- root（Magisk / KernelSU）+ **LSPosed 1.9.3 以上**（本模块用 libxposed API 102，旧版 LSPosed 不会识别）
- root 权限给本 app（读 microG 的 `gcmstatus` 数据库要用）
- microG 的「Google 云端消息推送」里目标应用已注册成功

## 出事怎么救

```
adb shell su -c "touch /data/adb/modules/zygisk_lsposed/disable"
adb reboot
```

## 原理

microG 的 `McsService.handleAppMessage()` 第一行就是 `database.noteAppMessage(...)`，消息一到就记账，跟目标应用有没有被唤醒无关。本 app 用 root 轮询 `/data/data/com.google.android.gms/databases/gcmstatus` 里的 `apps` 表，`total_message_count` 涨了就弹通知。目标应用可以完全冻结、零进程。

拿不到消息内容（`app_data` 是密文），只能提示「有新消息」。
