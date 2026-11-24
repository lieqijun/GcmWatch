## v1.5：修掉 v1.4 读不到列表的问题

**v1.4 读不到列表，是我的锅，不是路径写错。**

v1.4 加了个多包名探测，命令长这样：

```
[ -f '.../com.google.android.gms/databases/gcmstatus' ] && echo '命中'; ... ; [ -f '.../com.mgoogle.../gcmstatus.db' ] && echo '命中'
```

最后那条 `[ -f ]` 一旦不成立，整条命令退出码就是 1，而代码把「退出码非 0」当成了 **su 失败**，直接把 stdout 里已经命中的行丢了。所以只要最后几个候选不存在，就永远找不到库。

### 改回 v1.3 的判断

- 第一优先：`/data/data/com.google.android.gms/databases/gcmstatus`，判断方式与 v1.3 完全相同（`ls -l` 成功且输出含该路径）
- 多包名探测降到兜底，并且：
  - 命令末尾加 `true`，退出码不再影响解析
  - 只解析 stdout
- `stamp()` 同理，不再用退出码判断（`-wal` 不存在时 `stat` 会返回非 0，但主库那行仍然有效）

### 重装后 root 显示获取不到

卸载重装后 UID 变了，root 管理器要重新记一次授权。而 v1.4 只在你**回到这个界面**时才重测，你点完「允许」停在当前页，它就一直是「未授权」，看起来像是要重启才正常。

v1.5 改成：未授权时**每 3 秒自动重测，最多 6 次**；每次回到界面重新计数。点顶部那行也可以手动重测。

## 安装后

1. 打开 app，顶部那行应为 `root：已授权 · 数据库 com.google.android.gms/databases/gcmstatus`
2. 未授权就先在弹窗点「允许」，然后等它自己变（3 秒内）
3. 点「读取应用列表」→ 勾微信 → 「开始监听」

**第一条不会响**，首次运行只建基线。

## 原理

microG 的 `McsService.handleAppMessage()` 第一行就是 `database.noteAppMessage(...)`，消息一到就记账，跟目标应用有没有被唤醒无关。本 app 用 root 轮询 `gcmstatus` 的 `apps` 表，`total_message_count` 涨了就弹通知。目标应用可以完全冻结、零进程。

拿不到消息内容（`app_data` 是密文），只能提示「有新消息」。
