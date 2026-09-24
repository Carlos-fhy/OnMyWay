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

后端目前供可信本地开发环境使用，没有部署级鉴权和限流，不应直接暴露到公网。语音、对话和地点请求会按功能发送至所配置的服务商；待办、常用地点和正式行程存储在手机本地。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

连接测试设备后可运行 `:app:connectedDebugAndroidTest`。后端单元测试使用模拟地图与模型响应；真实定位、录音、高德导航及账号可用性需自行验收。
