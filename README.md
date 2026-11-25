# SyncClipboard Xposed (`com.scluzlep.syncclipboard`)

面向 Android 9–17（LSPosed / **libxposed API 102**）的无感后台剪贴板同步模块，完整兼容 **SyncClipboard v3.3.4** 官方服务端（SignalR + HTTP 历史记录 + `Title`/`Tags` + `X-SyncClipboard-Transfer-Data-Hash`）及标准 WebDAV 服务端，并支持类似 Mihomo 的单活多节点策略调度。

## 核心特性

- **系统级无感保活（无需手动后台加白或常驻前台通知）**：
  - `system_server`（UID 1000）内自动将本应用加入 Doze & eBPF 网络白名单（`IDeviceIdleController` / `DeviceIdleInternal`），并以特权绑定维持单进程同步引擎活跃。
  - 所有网络套接字均在模块自身进程内发起，规避高版本 Android `systemui_app` SELinux 套接字限制。
  - 息屏即断开可选延迟默认为 `0`（保持长连接），同步事务期间使用有界 `PARTIAL_WAKE_LOCK`（最长 15s），写回剪贴板时自动附加 `SUPPRESS_CLIPBOARD_OVERLAY` 防止息屏亮屏弹窗。
- **默认开启系统通知验证码（OTP）捕获**：
  - 无需申请 `RECEIVE_SMS`，在 `system_server` 通知入队阶段前置识别 4–8 位验证码并脱敏日志、标记 `EXTRA_IS_SENSITIVE`，并与剪贴板监听共享 10s SHA-256 去重缓存，防止点击“复制验证码”时重复上传。
- **完整 SyncClipboard v3.3.4 协议兼容**：
  - 支持 `Text`（`<= 10240` 内联、`> 10240` 无 BOM UTF-8 `.txt` 传输）、`Image`（含 `File` 扩展名自动识别及 `FileProvider` 写回）、`File`、`Group`（`.zip` 多文件打包与 UTF-8 字节序哈希校验）。
  - 支持 `/api/capabilities`（404 回退 `/api/version >= 3.1.1`）、`POST /api/history/query`、`POST /api/history`（`data` 末尾分片 + `X-SyncClipboard-Transfer-Data-Hash`）、`PATCH /api/history/{type}/{hash}`（精确区分 `tags` 省略与显式清空 `[]` 及 409 冲突处理）、`GET /api/history/{Type}-{Hash}/data`、`DELETE /api/history/clear`。
  - 支持 SignalR `/SyncClipboardHub`（JSON v1 + 15s 心跳）与 WebDAV（`PROPFIND` / `MKCOL` / `PUT` / `GET` / `ETag`）。
- **单活多节点选择器 & 历史隔离**：
  - 支持 `MANUAL`、`FALLBACK`、`URL_TEST`（含滞后防抖）、`RULE_AUTO`（传输类型 / CIDR / 可选 SSID）。
  - 严格保证同一时刻最多仅激活一个远程会话（`activeConnectionCount <= 1`），各节点本地历史按 `nodeStorageKey` 独立存储。
- **MIUIX + Monet 界面与分享扩展**：
  - 基于 `top.yukonga.miuix.kmp:miuix-ui-android:0.9.0` 与莫奈动态取色，提供 **概览**、**节点**、**历史**、**设置** 四大页面及系统分享页（单文件 / 多文件打包 `Group` 上传）、控制中心快捷磁贴。

## 作用域

在 LSPosed 中启用模块后，作用域仅需勾选：
- `com.android.systemui`
- `system`（系统框架）

## 本地构建

环境要求：`JDK 21`、`compileSdk 37`、Build-Tools `37.0.0`、Gradle `9.7.0`。

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
