## v1.7：root 是好的，是命名空间把它挡住了

v1.6 在两个错误方向上打转（su 被 DenyList 藏、库在 DE 分区），这版是实测定位出来的。

### 根因

Android 给每个应用进程建了独立的 mount namespace，把别的 app 的数据目录用 tmpfs 盖住：

```
/ /data/data      tmpfs mode=751     ← 整个目录被盖成空的
/ /data/user      tmpfs mode=751
/ /data/user_de   tmpfs mode=751
/data/com.gcmwatch  /data/data/com.gcmwatch   ← 只把它自己的 bind 回来
```

`su` 是从本 app 进程里 fork 出来的，**继承的就是这个 namespace**。所以哪怕返回 `uid=0`，
`ls /data/data/com.google.android.gms/databases` 也只能得到 `No such file or directory`
—— 这个路径在它的视角里根本不存在。

实测对照（同一台机器、同一个 pid）：

| 命令 | 结果 |
|---|---|
| `ls /data/data/com.google.android.gms/databases` | `No such file or directory` |
| `nsenter -t 1 -m -- ls /data/data/com.google.android.gms/databases` | `gcmstatus`、`gcmstatus-wal`、`exposure.db`… |

### 这版改了什么

- `RootShell.run()` 先 `nsenter -t 1 -m -- /system/bin/sh -c '...'` 切到 init 的 namespace 再执行
- 首次调用自动探测 su 与 nsenter 是否可用，探不到就**原样退回旧行为**，不引入新故障
- 诊断文本：找到库时显示**完整路径**；找不到时逐包显示「包在不在 / databases 里有什么」，
  不再把「包装了但 databases 目录还没建」报成「无此包」
- 删掉 v1.6 加的 DE（`/data/user_de/0`）探测 —— 实测那台机器 DE 下根本没有 `databases` 目录，
  库就在 CE，多探两个位置纯属噪音

### 装完怎么验

1. 点顶部那行，应显示 `root：已授权 · 数据库 /data/data/com.google.android.gms/databases/gcmstatus`
2. 点「申请通知权限」并允许（v1.6 及之前 `POST_NOTIFICATIONS` 是关的，计数涨了也不会弹）
3. 点「读取应用列表」，应能看到 `com.tencent.mm` 已注册、`已收 N 条`

## 原理

microG 的 `McsService.handleAppMessage()` 第一行就是 `database.noteAppMessage(...)`，消息一到就记账，跟目标应用有没有被唤醒无关。本 app 用 root 轮询 `gcmstatus` 的 `apps` 表，`total_message_count` 涨了就弹通知。目标应用可以完全冻结、零进程。

拿不到消息内容（`app_data` 是密文），只能提示「有新消息」。
