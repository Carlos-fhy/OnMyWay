<h1 align="center">🧭 OnMyWay</h1>

<p align="center">
  <strong>An open-source voice-first AI navigation agent that finds things you can get done along the way.</strong><br/>
  通过语音或文字记录待办，结合当前位置、目的地和出行方式，发现值得顺路完成的事情。<br/>
  <strong>事项由你选择，路线变更由你确认。</strong>
</p>

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Android API 26+"/></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&amp;logo=kotlin&amp;logoColor=white" alt="Kotlin 2.2"/></a>
  <a href="https://developer.android.com/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&amp;logo=jetpackcompose&amp;logoColor=white" alt="Jetpack Compose"/></a>
  <a href="https://fastapi.tiangolo.com"><img src="https://img.shields.io/badge/FastAPI-Python%203.11%2B-009688?style=for-the-badge&amp;logo=fastapi&amp;logoColor=white" alt="FastAPI"/></a>
  <a href="https://lbs.amap.com"><img src="https://img.shields.io/badge/AMap-SDK%2011.2-1677FF?style=for-the-badge" alt="高德地图 SDK"/></a>
  <a href="https://developers.google.com/optimization"><img src="https://img.shields.io/badge/OR--Tools-Routing-EA4335?style=for-the-badge" alt="OR-Tools"/></a>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Carlos-fhy/OnMyWay?style=for-the-badge&amp;color=2E7D32" alt="MIT License"/></a>
  <a href="https://github.com/Carlos-fhy/OnMyWay/stargazers"><img src="https://img.shields.io/github/stars/Carlos-fhy/OnMyWay?style=for-the-badge&amp;logo=github&amp;color=2E7D32" alt="GitHub Stars"/></a>
  <a href="https://github.com/Carlos-fhy/OnMyWay/forks"><img src="https://img.shields.io/github/forks/Carlos-fhy/OnMyWay?style=for-the-badge&amp;logo=github&amp;color=2E7D32" alt="GitHub Forks"/></a>
</p>

<p align="center">
  <sub>Android 客户端 · Python 后端 · 本地优先 · 断线可恢复</sub>
</p>

---

## 📖 目录

- [✨ 核心能力](#-核心能力)
- [📱 界面预览](#-界面预览)
- [🧱 技术栈](#-技术栈)
- [🚀 快速开始](#-快速开始)
- [📁 项目结构](#-项目结构)
- [📚 文档](#-文档)
- [🚧 当前边界](#-当前边界)
- [📄 许可证](#-许可证)

## ✨ 核心能力

- 🎙️ **语音优先的记录方式**：按住说话、松开识别并提交，也支持键盘输入。说一句“取快递、打印论文”，小助手把它整理成待办与路线。
- ✅ **确认后才落库的待办**：草稿需要显式确认；任务、草稿消费和确认收据原子写入，重复确认不会重复创建。支持稍后提醒、忽略本次提醒，以及一小时以后、明天九点和自选时间；预约不改变计划日期、截止时间或优先级。
- 📍 **会自己安排地点的小助手**：预设快递站、打印店等常用地点后，同类型地点在当前位置 3 公里内时自动填入草稿，再把安排结果交给你核对；地点不对可以随时更换。
- 🧭 **真正顺路的规划**：结合当前位置、目的地、出行方式、截止时间和办事时长，从有效待办中筛选顺路组合，展示额外距离与耗时；助手先给出方案，你可以调整后确认。
- 🗺️ **应用内导航与途中调整**：内置高德地图与导航；途中可通过对话移除事项、更换目的地，或加入打印、超市、药店等顺路事项，试算后确认更新路线。中断的调整可以取回，算路失败重试会复用已收到的意图并重新定位。
- 🗓️ **按时间整理的记录**：待办按截止日期或完成日期分组，助手对话按日期分隔，已确认路线保留快照入口；历史路线可查看原路径，并从当前位置重新规划到历史目的地。
- 🕘 **可恢复的行程与对话**：对话请求在发送前写入本机 SQLite，断线、重开或进程死亡后可继续取回，保持原请求编号并去重。恢复的任务和路线仍需你确认，旧回复不会覆盖已经变化的草稿或行程；已结束的导航不会自动复活。
- 📶 **本地优先，电脑同步**：待办、行程、对话、常用地点和建议反馈可同步到电脑 SQLite；离线写入使用持久重试队列。重装后连接同一服务先拉回记录，版本冲突保留双方数据，电脑保留历史版本。
- 🧠 **轻量、可关闭的偏好学习**：顺路建议的接受、调整、直达或取消记录在本机；步行与骑行预算可分别设定，未设定时只在同类选择足够一致后才尝试估计。设置中可暂停或清空学习记录，事件不包含任务名称、地点、坐标或对话原文。

典型流程：

> 记录“打印论文” → 说出目的地 → 选择出行方式 → 查看顺路建议 → 选择事项 → 确认路线 → 开始导航

时间和距离来自地图接口与确定性计算，模型负责理解意图。

## 📱 界面预览

| 小助手对话 | 应用内导航 | 行程记录 |
| :---: | :---: | :---: |
| <img src="assets/assistant.png" width="260" alt="小助手对话界面"/> | <img src="assets/navigation.png" width="260" alt="应用内导航界面"/> | <img src="assets/route-detail.png" width="260" alt="行程记录详情"/> |

## 🧱 技术栈

| 层级 | 技术 |
| --- | --- |
| 客户端 | Kotlin 2.2 · Jetpack Compose · Material 3 · minSdk 26 / targetSdk 36 |
| 本地数据 | Room 2.7（显式迁移）· SharedPreferences · WorkManager 2.10 |
| 地图与导航 | 高德地图 Android SDK 11.2（地图 / 定位 / 搜索 / 导航） |
| 语音输入 | Android 系统语音识别（按住说话） |
| 后端 | Python 3.11+ · FastAPI · Pydantic · SQLite |
| 路线优化 | Google OR-Tools |
| 构建与测试 | Gradle Kotlin DSL · JDK 17 · JUnit · AndroidX Instrumentation |

## 🚀 快速开始

项目包含 Kotlin／Compose Android 客户端和 Python／FastAPI 后端。开始前准备：

- Python 3.11+、JDK 17、Android Studio 与 Android SDK 36
- 高德 Web 服务 Key（后端）和高德 Android SDK Key（客户端）
- 模型服务配置（用于理解自然语言意图）

**1. 启动后端**

```bash
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r backend/requirements-dev.txt
cp backend/.env.example backend/.env   # 填入高德 Web 服务与模型配置
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

**2. 构建 Android App**

在 `local.properties` 中填写 `amap.android.key`，然后使用 Android Studio 构建，或执行：

```bash
./gradlew :app:installDebug
```

**3. 连接手机**

```bash
adb reverse tcp:8000 tcp:8000
```

在 App 中把服务地址设为 `http://127.0.0.1:8000`，首次连接电脑数据服务时输入数据服务口令。

完整配置、测试命令和故障排查见 [本地开发指南](docs/PUBLIC_SETUP.md)。仓库不提供服务凭据、真实用户数据或预配置 APK。

## 📁 项目结构

```text
OnMyWay/
├── app/          Android 客户端：Compose UI、Room、导航、小助手
├── backend/      FastAPI 服务：规划、地图代理、同步与业务事件
├── docs/         环境配置与开发指南
├── tools/        真机连接检查与后端巡检脚本
├── third_party/  第三方许可与说明
└── assets/       README 界面截图
```

## 📚 文档

| 文档 | 内容 |
| --- | --- |
| [本地开发指南](docs/PUBLIC_SETUP.md) | 环境准备、配置、构建与测试命令 |
| [第三方说明](THIRD_PARTY_NOTICES.md) | 第三方依赖许可与服务条款 |
| [MIT License](LICENSE) | 项目原创代码许可证 |

## 🚧 当前边界

- 本地开发 MVP，后端尚无部署级鉴权，不应直接开放公网。
- 自动匹配最多检查 6 个有效待办，候选地点搜索有范围限制。
- 从地图服务返回的候选路线中选择，不保证全路网最短；导航 SDK 会独立算路。
- 尚未校验营业时间与实际排队时间，额外总耗时包含用户填写的办事时长。
- 语音仍需按住录音，尚无免按键连续监听或云端 TTS 播报。
- 导航调整的最近对话和待确认方案在同一行程内可恢复（30 分钟内），恢复后必须重新定位并算路才能确认；助手首页对话和待确认意图保存在本机，重开后路线需要重新计算。电脑同步首次需要输入口令关联，之后依赖后端在线与手机连接；未送达数据在卸载后无法找回。当前为单用户版本同步，尚无多用户账号、逐条冲突合并与增量同步。
- 通知自动提取、完整 Harness 和长期用户模型不属于本次发布能力。
- 请求中断恢复覆盖助手首页及同一行程内的导航调整。设备请求日志与导航调整检查点使用 SQLite，不随电脑业务快照同步；卸载前未取回的请求不能靠电脑同步恢复。已结束的导航不会自动复活。后端运行记录暂未设置保留期限，服务器业务确认事务仍待实现。

## 📄 许可证

项目原创代码采用 [MIT](LICENSE) 许可证。第三方依赖和服务遵循各自许可与条款，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
