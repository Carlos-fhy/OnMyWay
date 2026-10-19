# 本地开发配置

需要 Python 3.11+、JDK 17、Android Studio 和 Android SDK 36。最低支持 Android 8（API 26）。

## 后端

在项目根目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend/requirements-dev.txt
Copy-Item backend/.env.example backend/.env
```

已有 `.env` 时直接编辑，不要覆盖。填写自己的凭据：

| 配置 | 用途 |
| --- | --- |
| `AMAP_WEB_KEY` | 高德 **Web 服务** Key，搜索地点和规划路线 |
| `DASHSCOPE_API_KEY` | 阿里云百炼模型调用 Key，保留在后端 |
| `DASHSCOPE_BASE_URL` | 与 Key 地域匹配的服务根地址；支持 `/compatible-mode/v1` 或 `/api/v1` |
| `AGENT_MODEL` | 账号可访问的文字模型 ID，以控制台为准 |
| `VOICE_ASR_MODEL` | 语音识别模型 ID；当前适配 Qwen 音频原生识别协议 |
| `VOICE_TTS_MODEL` | 预留参数，尚未实现云端播报 |
| `ONMYWAY_BACKUP_TOKEN` | 可选电脑备份口令；至少 32 个随机字符，只写入电脑的 `backend/.env` |
| `ONMYWAY_BACKUP_DB` | 可选电脑备份 SQLite 文件路径；默认 `backend/data/backups.sqlite3` |
| `ONMYWAY_HARNESS_DB` | 可选意图执行日志 SQLite 路径；默认 `backend/data/harness.db` |

文字请求使用兼容 Chat Completions 的路径；语音使用 `/api/v1/services/aigc/multimodal-generation/generation`。仅支持文字的兼容网关不一定支持此语音协议。

```powershell
.\.venv\Scripts\python.exe -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

本地健康检查：`http://127.0.0.1:8000/health`。`amap_configured` 只表示配置非空，不代表上游 Key 已通过验证。

## Android

Android Studio 打开项目，在本地生成的 `local.properties` 中添加：

```properties
amap.android.key=YOUR_AMAP_ANDROID_SDK_KEY
```

此处必须使用高德 **Android SDK** Key，并在高德控制台绑定包名 `com.onmyway.app` 和当前签名证书 SHA-1。可运行 `./gradlew signingReport` 查询本地签名。Web 服务 Key 不能替代 Android SDK Key。

用自己的 Key 构建，不要发布包含私人 Key 的调试 APK。第三方 SDK 由 Gradle 下载，本仓库不包含 SDK 二进制或授权凭据。

USB 手机授权调试后执行：

```powershell
adb reverse tcp:8000 tcp:8000
```

App 设置的服务地址为 `http://127.0.0.1:8000`。模拟器可使用 `http://10.0.2.2:8000`。当前本地 HTTP 联调需使用 debug 构建；release 默认禁止明文请求。

Windows 下可运行 `tools/check-device-backend.ps1` 检查电脑后端、恢复 USB 转发并验证手机收到 HTTP 200。USB 重连后可能需要重新转发。`tools/watch-device-backend.ps1 -Serial <SERIAL>` 可在两小时内自动恢复转发，但不能修复 USB 传输故障或模型上游错误。

后端目前只允许来自本机的连接（包括 USB ADB reverse），即使误把 uvicorn 绑定到 0.0.0.0，远程客户端也会收到 HTTP 403。除本地数据同步口令外仍没有部署级用户鉴权和限流；不得通过反向代理绕过本机限制后开放公网。租服务器前需先完成身份、配额和 TLS 设计。语音、对话和地点请求会按功能发送至所配置的服务商；手机保留离线记录，启用电脑数据同步后把已同步记录持久保存到电脑 SQLite。未连接或尚未同步的记录仍只存在手机。

### 数据库存储格式

手机正式记录、助手对话和待确认方案保存在 Room 管理的 SQLite 数据库 `onmyway.db`；旧安装的助手偏好记录会在首次打开时迁入。仓库中的 `app/schemas/com.onmyway.app.data.local.TaskDatabase/*.json` 是 Room 自动生成的**表结构与迁移校验文件**，不是用户记录；升级测试依赖它们，不能改成 `.sqlite` 或直接删除。电脑备份保存在 `backend/data/backups.sqlite3` 的独立 SQL 表（`backup_tasks`、`backup_route_history`、`backup_assistant_messages` 等），不再把整份备份存为单个 JSON 文档。手机与后端仍以 JSON 传输请求；已有路线快照与少量偏好设置仍含 JSON 文本字段，后续会按迁移计划拆为结构化表。

后端另用 `backend/data/harness.db` 保存意图请求的编号、执行状态与建议结果，可用 `ONMYWAY_HARNESS_DB` 指定路径。“完成”只表示建议已经生成，不代表待办或导航已经执行。此库包含建议内容，不在 `/sync` 快照范围内，需要单独备份；仓库忽略该数据库及整个 `backend/data/` 目录。

### 电脑主记录与手机同步

在 `backend/.env` 填写 `ONMYWAY_BACKUP_TOKEN`（至少 32 个随机字符），重启后端。在 App 的「设置 → 电脑数据同步」输入相同口令，点击「连接电脑并同步」。历史配置名保留兼容；电脑主记录仍在 `backend/data/backups.sqlite3`，可用 `ONMYWAY_BACKUP_DB` 指定位置。

- 首次连接时，先读取电脑：空手机拉回已有数据；电脑尚无记录时才上传手机数据。两边已有不同记录时暂停，保留双方。
- 手机修改先落入 Room，变化触发同步；未送达的具体请求存入手机 `onmyway-sync.db`，应用进程重启后仍可重试。联网同步成功后显示「已同步到电脑」；后台还会定期补同步，执行时机受 Android 调度影响。
- 写入使用电脑分配的版本号与持久请求 ID。旧版本写入被拒绝，丢响应后重复提交不会重复生效。电脑保留 SQLite 结构化历史版本，删除当前记录不会立即销毁历史副本。
- 重装后输入原服务地址与口令连接即可拉回已同步数据。恢复后重新打开 App 加载对话与设置；旧导航标记为需要重算。手机尚未同步的修改无法在卸载后找回。
- 两端并行编辑时会显示版本冲突；当前版本保护双方数据，尚未提供逐条自动合并和冲突处理界面。导航进行中不自动应用远端替换。当前是单用户受控电脑服务，不是多用户云同步。
- 常用地点等部分设置仍在手机偏好存储，恢复会通过 SQLite 日志续办。跨 Room/偏好存储的完全原子提交、冲突解决界面与恢复后页面即时刷新仍需后续完善。

新接口：`GET /sync` 读取当前版本及记录，`PUT /sync` 按 `store_id/base_revision/request_id` 写入。`GET /sync/versions` 返回最近 50 个历史版本的元信息，`GET /sync/versions/{revision}` 只读获取历史内容；都要求同一 `X-Backup-Token`。旧 `PUT /backup` 不能覆盖已有电脑记录或已启用同步的数据库。

口令由 Android Keystore 加密保存。HTTP 仅允许本机 ADB 转发或模拟器桥接，远程地址需 HTTPS；后端本机访问限制仍然生效。电脑 SQLite 包含地点、对话和历史版本，应限制账户访问并备份磁盘。历史暂不自动清理、磁盘默认未加密，单次同步上限为 8 MiB；历史保留策略、分页增量传输与云端用户身份仍未完成。迁移服务器时须保留电脑数据库及其 `store_id`，不能用空数据库替代，并在公网部署前完成鉴权、TLS 与多用户隔离。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

仅对专用测试设备或模拟器运行 `:app:connectedDebugAndroidTest`，用 `ANDROID_SERIAL` 指定目标，避免清理用户手机 App 和数据。测试后执行 `:app:installDebug`，再运行 `tools/check-device-backend.ps1 -Serial <SERIAL>` 验证安装及手机端 HTTP 200。后端单元测试使用模拟地图与模型响应；真实定位、录音、高德导航及账号可用性需自行验收。
