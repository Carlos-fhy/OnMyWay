# OnMyWay

An open-source voice-first AI navigation agent that finds things you can get done along the way.

通过语音或文字记录待办，结合当前位置、目的地和出行方式，发现值得顺路完成的事情。**事项由你选择，路线变更由你确认。**

## 当前 MVP

- 按住说话、松开识别并提交，也支持文字输入。
- 待办草稿确认保存，轻量任务管理与本地提醒。
- 地点搜索、常用地点，以及步行／骑行路线规划。
- 自动筛选顺路建议，展示额外距离、路途和办事耗时；用户选择事项后预览组合。
- 应用内高德地图与导航；途中可对话移除事项、更换目的地，再确认更新路线。
- 正式行程保存在 Room；导航调整在当前进程内保留多轮上下文。

典型流程：记录“打印论文” → 说出目的地 → 选择出行方式 → 查看建议 → 选择事项 → 确认路线 → 开始导航。时间和距离来自地图接口与确定性计算，模型负责理解意图。

## 快速开始

项目包含 Kotlin／Compose Android 客户端和 Python／FastAPI 后端，规划使用 OR-Tools，地图使用高德服务。

1. 准备 Python 3.11+、JDK 17、Android Studio 和 SDK 36。
2. 复制 `backend/.env.example` 为 `backend/.env`，填入自己的高德 Web 服务与模型配置。
3. 安装 `backend/requirements-dev.txt`，运行 `python -m uvicorn backend.main:app --host 127.0.0.1 --port 8000`。
4. 在 `local.properties` 填写 `amap.android.key`，使用自己的 Android SDK Key 构建 debug App。
5. USB 手机执行 `adb reverse tcp:8000 tcp:8000`，App 服务地址设为 `http://127.0.0.1:8000`。

完整配置及测试命令见 [本地开发指南](docs/PUBLIC_SETUP.md)。仓库不提供服务凭据、真实用户数据或预配置 APK。

## 当前边界

- 本地开发 MVP，后端尚无部署级鉴权，不应直接开放公网。
- 自动匹配最多检查 6 个有效待办，候选地点搜索有范围限制。
- 从地图服务返回的候选路线中选择，不保证全路网最短；导航 SDK 会独立算路。
- 尚未校验营业时间与实际排队时间，额外总耗时包含用户填写的办事时长。
- 语音仍需按住录音，尚无免按键连续监听或云端 TTS 播报。
- 对话记忆尚不支持杀进程恢复；没有账号和云同步。
- 通知自动提取、完整 Harness 和长期用户模型不属于本次发布能力。

## 许可证

项目原创代码采用 [MIT](LICENSE) 许可证。第三方依赖和服务遵循各自许可与条款，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
