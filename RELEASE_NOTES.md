## v1.6：su 被藏起来了，不是没 root

你截图里的报错是关键证据：

```
root：未授权 · java.io.IOException: Cannot run program
"/debug_ramdisk/su": error=2, No such file or directory
```

`error=2` = 文件不存在。代码是依次试 `su` / `/system/bin/su` / `/debug_ramdisk/su` / `/system/xbin/su` / `/sbin/su`，全部 ENOENT 才会报出最后一个。**也就是说这个 app 进程里，一个 su 都看不见。**

Magisk 里已经勾了本应用授权（另一张截图），所以不是授权问题，也不是没装 root。

### 最可能的原因（推断，下面有验证方法）

Zygisk 的「强制执行拒绝列表 / Enforce DenyList」一旦对本应用生效，会在该进程里 **unmount 掉 Magisk 的挂载**，`/system/bin/su` 就从它的文件系统里消失了。

你说的时间顺序也能对上：**先弄好 LSPosed（模块启用，本应用进程被 Zygisk 注入）→ 再给 root，就读不到了。**

### 这版能帮你确认

界面现在会多一行，直接告诉你那三个文件在**本进程里**看不看得见：

```
su 文件：/system/bin/su 无  /debug_ramdisk/su 无  /system/xbin/su 无
```

- 三个都是「无」+ Magisk 里确实给了授权 → 就是被 unmount 藏起来了
- 有一个「有」但还报错 → 那是 exec 权限问题，另一回事

同时加了兜底：`/data/adb/magisk/su`、`/data/adb/ap/bin/su`、`/data/adb/ksu/bin/su`，以及借 `/system/bin/sh` 用 PATH 再找一次 su。

### 你可以先试这两步

1. **Magisk → 设置 → 关掉「强制执行拒绝列表（Enforce DenyList）」**；如果「配置拒绝列表」里勾了「GCM 消息监听」，取消勾选
2. 然后**强制停止本应用**（设置 → 应用 → 强制停止，不必重启手机），再打开

如果关掉之后那行变成「已授权」，就说明是 deny 列表干的。

极端的确认方式：LSPosed 里临时停用本模块 → 重启 → 看 root 是否恢复。恢复就说明是模块注入导致的，那再决定是保活优先还是读库优先。

## 安装后

1. 打开 app，顶部第一行看 root 状态，第二行看 su 文件有/无
2. 有 root 后点「读取应用列表」→ 勾微信 → 「开始监听」
3. **第一条不会响**，首次运行只建基线

## 原理

microG 的 `McsService.handleAppMessage()` 第一行就是 `database.noteAppMessage(...)`，消息一到就记账，跟目标应用有没有被唤醒无关。本 app 用 root 轮询 `gcmstatus` 的 `apps` 表，`total_message_count` 涨了就弹通知。目标应用可以完全冻结、零进程。

拿不到消息内容（`app_data` 是密文），只能提示「有新消息」。
