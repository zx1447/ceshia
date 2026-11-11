# Node 应用（`app/index.js`）崩溃与故障排查文档

## 1. 自动生成的日志文档路径

插件已在运行目录自动生成持久化的诊断日志文档，**重启 MC 服务器也不会清空**：

- **`logs/.mcchajian/app_crash_report.log`**：Node 崩溃与故障诊断主文档（超过 `512 KB` 自动轮转为 `app_crash_report.log.1`）
- **`logs/.mcchajian/app.log`**：Node 标准输出与错误输出日志（超过 `1 MB` 自动轮转为 `app.log.1`）
- **`logs/.mcchajian/deploy.log`**：依赖下载与 `npm install` / `koffi` 安装日志

---

## 2. 日志采集触发时机

1. **JS 预加载层（`.nd_preload.js`）实时捕获**
   - `[JS_UNCAUGHT_EXCEPTION]`：未捕获异常与未处理 Promise 拒绝（附完整堆栈、`pid`、`uptime`、`rss`、`heap`）。
   - `[JS_WARNING]`：内存或事件监听器异常告警（如 `MaxListenersExceededWarning`）。
   - `[JS_PROCESS_EXIT]`：Node 以非 `0` 状态码退出。
2. **Java 守护层（`EssentialsX.java`）自动取证**
   - `startup_exit` / `startup_timeout_60s`：Node 启动 60 秒内提前退出或端口就绪超时。
   - `runtime_exit`：运行中检测到 `!nodeProcess.isAlive()`（先写报告再重启，避免静默重启丢失现场）。
   - `healthcheck_unresponsive`：进程仍在但本地 HTTP 健康检查失联（事件循环阻塞、死锁或绑定端口异常）。
   - `[MISSING_RUNTIME_FILES]`：`nodejs/bin/.node_real` 或 `app/index.js` 丢失。

每次 Java 层崩溃记录都会附带：
- `exitCode` 与具体原因诊断（`diagnosis=`）
- `os` / `arch` / `libc`（区分 `glibc` 与 Alpine `musl`）
- `jvmHeap` 与宿主机/容器内存快照（`cgroupMem` 含 `used` / `limit` / `oom_kill`，以及 `/proc/meminfo`）
- `app.log` 最后 60 行（启动短时间内崩溃会额外附带 `deploy.log` 最后 25 行）

---

## 3. 常见退出码对照排查

| 日志关键字 / 退出码 | 常见原因 | 排查方向 |
|---|---|---|
| `exitCode=137` (`SIGKILL`) | 容器达到 `cgroup memory.max` 触发 Linux OOM Killer 优先杀掉 `node`；或面板/宿主机监控强杀子进程 | 查看日志中的 `cgroupMem=v2 used=... limit=... oom_kill ...` 与 `MemAvailable` |
| `exitCode=139` (`SIGSEGV`) / `134` (`SIGABRT`) | `koffi` 等原生 `.so` 在 Alpine (`musl`) 或老旧 `glibc` 环境崩溃，或 V8 内存分配异常 | 查看 `libc=` 与 `app.log tail` 中的原生报错位置 |
| `exitCode=143` (`SIGTERM`) | 外部面板看门狗、停服流程或进程清理策略发送了终止信号 | 检查 MC 面板任务/进程限制规则 |
| `exitCode=1` | JS 未捕获异常、Promise 拒绝、依赖安装不完整或端口冲突 | 查看同一时间点上方的 `[JS_UNCAUGHT_EXCEPTION]` 堆栈与 `app.log tail` |
| `healthcheck_unresponsive` | 事件循环卡死、远端请求阻塞、或未在本地回环端口成功监听 | 查看 `app.log tail` 是否卡在初始化、TLS 或网络调用 |
| `[MISSING_RUNTIME_FILES]` | 面板定时清理脚本删掉了 `logs/.mcchajian` 文件，或 `deploy.sh` 下载失败 | 查看 `deploy.log` 尾部及面板文件清理策略 |
