## v1.4：把「给了 root 还是失败」这件事查清楚

上一版界面上的两个按钮 —— **LSPosed 保活**、**电池白名单** —— 已经去掉，界面只剩四个操作：开始监听 / 立即检查 / 读取应用列表 / 测试通知。

### root 检测重做

之前失败有两种可能，这一版都处理了：

**1. su 授权弹窗点慢了被当成「未授权」**
第一次 `su` 要弹窗，原来只等 20 秒；超时就直接判失败，而且界面不会自动重试，于是一直显示未授权。
现在等 40 秒，并在每次回到界面时重新检测。

**2. 数据库路径写死**
原来只认 `/data/data/com.google.android.gms/databases/gcmstatus`。现在一次 `su` 里同时探测：

```
com.google.android.gms
com.microg.gms
app.revanced.android.gms
com.mgoogle.android.gms
  × gcmstatus / gcmstatus.db
```

### 顶部那行现在会说人话

- 正常：`root：已授权 · 数据库 com.google.android.gms/databases/gcmstatus`
- 未授权：`root：未授权 · <su 的原始报错>`
- 找不到库：把每个候选包名的 `databases` 目录内容直接列出来

**点这一行 = 重新检测。** 找不到库时，这一行显示的内容就能看出到底是包名不对、还是 microG 根本没建库。

### 其他

- su 查找增加 `/system/xbin/su`、`/sbin/su`、`/debug_ramdisk/su`
- root 刚接通时，如果监听已开着，会自动按新路径重建一次基线
- 去掉 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限
- 模块元数据（LSPosed 保活）代码仍然保留，只是不再在界面上露出；不需要可以完全不管

## 安装后必做

1. 打开 app → 顶部那行应该是「root：已授权 · 数据库 …」
2. 不是的话点它，弹 su 就点允许
3. 点「读取应用列表」，勾上微信
4. 点「开始监听」

**第一条不会响**，首次运行只建基线。

## 要求

- root（Magisk / KernelSU）
- root 授权给本 app
- microG 的「Google 云端消息推送」里目标应用已注册成功

## 原理

microG 的 `McsService.handleAppMessage()` 第一行就是 `database.noteAppMessage(...)`，消息一到就记账，跟目标应用有没有被唤醒无关。本 app 用 root 轮询 `gcmstatus` 的 `apps` 表，`total_message_count` 涨了就弹通知。目标应用可以完全冻结、零进程。

拿不到消息内容（`app_data` 是密文），只能提示「有新消息」。
