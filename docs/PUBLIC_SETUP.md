# 本地开发配置

需要 Python 3.11+、JDK 17、Android Studio 和 Android SDK 36。最低支持 Android 8（API 26）。

## 开发方向与职责

2026-11-08 起，开发主线调整为 AI Native 个人 Agent：围绕用户目标形成「决策 → 工具执行 → 观察结果 → 再决策」循环，通过 MCP 扩展工具，由模型根据工具描述和实际返回结果选择下一步。后端已有最小通用循环、持久化任务与成果、受配置约束的 MCP 工具发现和执行，并适配待办读取、地点搜索、路线试算和原执行状态查询。本地合成模型与 MCP 已有验证；限定输入与预算的真实模型、高德和 Exa 官方 MCP 评测正在进行，A1 整体验收尚未通过，Android 尚未接入新任务 API。

- **后端保持模块化单体**：承接任务目标与约束、上下文构建、模型决策、能力目录、工具执行控制、状态持久化和成果。主 Agent 按需创建一层通用子 Agent，指定目标、只读工具、输入证据与预算，并读取、验收成果后继续。主子任务共享总预算，当前由同一 worker 顺序调度，无需分别部署服务；真实主子协作仍待完整验收。
- **Android 承担交互与设备能力**：持续对话、任务进度、成果展示、用户确认、定位和导航 SDK 执行；保留离线提交和设备回执，逐步迁出分散的业务编排。
- **分开保存数据职责**：长期记忆保存有来源和适用范围的个人信息，任务状态保存本次工作进度，业务系统保存权威记录。按预算组装上下文，不能把全量聊天和全部工具描述持续堆入模型输入。
- **工具按契约执行**：能力目录保留工具身份和输入输出模式；MCP 连接器负责认证、发现和通信。执行控制落实参数校验、已有授权、预算、超时与回执。外部写入结果未知时先核实，不能假定任意 MCP 都支持幂等重试。
- **以完整场景替换旧编排**：先交付最小通用循环与 MCP 接入，再统一 Android 的任务和成果入口，随后加入已授权事件触发与日常部署。新路径接管并通过对应验收后删除旧分支，避免长期双轨。

首批使用两个场景验证同一运行机制：「四天旅行规划」要求自主选择已接入的合适工具，依据真实返回结果形成和修改方案；「取快递 + 打印论文 + 回宿舍」要求取消打印后保留快递和宿舍，中断后核对原执行、不重复操作，旧结果不覆盖新要求。工具故障、缺失信息、取消和预算耗尽都必须有明确行为。

取消未确认打印草稿只移除该草稿；从本次行程移除打印只调整行程，正式待办仍保留且未完成。删除或完成正式待办需要另外的明确指令。

工具目录可按实际命名空间分页浏览，摘要可见不等于允许调用：完整 Schema、权限和版本校验仍是执行条件。当前运行的成果使用 `artifact_id` 和 `expected_version` 显式更新，版本冲突要求重新读取；省略两项表示新建独立成果，跨任务修订不覆盖旧运行。

生产 worker 在 `finish` 前使用同一模型配置进行独立来源核对，输入完整当前成果与完成说明、程序生成的调用事实及标明覆盖范围的原始证据片段。只有当前版本得到 `supported` 才完成；`contradicted` 或 `insufficient` 反馈修正，每个运行最多两次核对请求，均计入原步数、活动时间和模型调用预算。上下文不足、供应商错误或无效核对明确暂停。模型核对不是事实正确性的保证，未显示的来源不算已审阅；实际模型/官方服务验收继续与本地固定测试分开记录。

创建任务可在 `constraints.delivery_contract` 显式约定成果类型、正文结构、成果数量上限和等待用户模式。程序在保存前校验；单一成果的修改须沿用原编号和版本，不重复新建。核对格式错误仅有一次额度内纠错，已提出的问题不得被丢弃。上述机制不自动理解所有自然语言约束，也不代替真实事实核查。

既有导航恢复和设备验收继续单列，只在缺陷影响当前切片时阻塞对应验收；不要求先完成所有导航扩展才能验证通用循环。固定响应回放、真实模型与 MCP 调用、Android 设备操作和实际出行分别留证，任一类通过都不能替代其他类别。

显式交付任务在暂停追问前会独立核对用户原话中的已有答案和问题必要性；此请求同样计入运行及模型预算。任务修订回执只说明任务定义变化，不能替代业务写入或设备回执。完整工具结果优先保留；超限才使用有覆盖标记的缩略/分页。以上机制仍需真实模型验收。

## 后端

源码已按职责分包，目录、离线评测与测试命令见 [后端目录与运行入口](../backend/README.md)。服务仍以 `backend.main:app` 启动，配置仍为 `backend/.env`，默认数据库仍在 `backend/data/`。

在项目根目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r backend/requirements-dev.txt
Copy-Item backend/.env.example backend/.env
```

已有 `.env` 时直接编辑，不要覆盖。填写自己的凭据：

| 配置 | 用途 |
| --- | --- |
| `AMAP_WEB_KEY` | 高德 **Web 服务** Key，用于地点与路线接口，也可由官方高德 MCP 的 `query_env` 引用 |
| `DASHSCOPE_API_KEY` | 文字模型服务的 API Key，保留在后端；变量名沿用历史配置 |
| `DASHSCOPE_BASE_URL` | 兼容 Chat Completions 的服务根地址；使用百炼时需与 Key 地域匹配，支持 `/compatible-mode/v1` 或 `/api/v1` |
| `AGENT_MODEL` | 账号可访问的文字模型 ID，以控制台为准 |
| `VOICE_ASR_MODEL` | 语音识别模型 ID；当前适配 Qwen 音频原生识别协议 |
| `VOICE_TTS_MODEL` | 预留参数，尚未实现云端播报 |
| `ONMYWAY_BACKUP_TOKEN` | 个人数据服务口令；记忆、业务同步和 Agent API 共用，至少 32 个随机字符，保存在后端私有配置中 |
| `ONMYWAY_BACKUP_DB` | 可选电脑备份 SQLite 文件路径；默认 `backend/data/backups.sqlite3` |
| `ONMYWAY_HARNESS_DB` | 可选意图执行日志 SQLite 路径；默认 `backend/data/harness.db` |
| `ONMYWAY_MEMORY_DB` | 可选个人记忆 SQLite 路径；默认 `backend/data/personal_memory.sqlite3`，不包含在业务同步快照中 |
| `ONMYWAY_AGENT_WORKER_ENABLED` | 默认 `false`；设为 `true` 并重启后端，才会在后台推进 Agent 任务 |
| `ONMYWAY_AGENT_DELEGATION_ENABLED` | 默认 `true`；worker 启用后允许主 Agent 创建一层只读子任务。设为 `false` 停止新增委派，已有子任务仍可继续并接受验收 |
| `ONMYWAY_AGENT_DB` | 可选 Agent 任务、工具回执、成果及事件 SQLite 路径；默认 `backend/data/agent.sqlite3`，不包含在 `/sync` 快照中 |
| `ONMYWAY_MCP_SERVERS_FILE` | 可选 MCP JSON 配置路径；为空时只注册内置工具，相对路径从启动后端的工作目录解析 |
| `ONMYWAY_AGENT_INPUT_TOKENS` | 默认 `12000`，范围 `1024`–`14336`；模型输入按 UTF-8 字节数保守估算预算，另预留输出空间 |
| `ONMYWAY_AGENT_LEASE_SECONDS` | 默认 `90`，范围 `1`–`300`；工作线程持有任务的租约时长，用于失联后的接管判断 |

文字请求使用兼容 Chat Completions 的路径；语音使用 `/api/v1/services/aigc/multimodal-generation/generation`。仅支持文字的兼容网关不一定支持此语音协议。

```powershell
.\.venv\Scripts\python.exe -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

本地健康检查：`http://127.0.0.1:8000/health`。`amap_configured` 只表示配置非空，不代表上游 Key 已通过验证。

## Agent 与 MCP

新 Agent 由后端 API 驱动，Android 的对话界面暂未调用这些接口。任务存储与后台执行分开：`ONMYWAY_AGENT_WORKER_ENABLED=false` 时仍可创建和读取任务，但任务不会自动推进。开启前配置至少 32 个字符的 `ONMYWAY_BACKUP_TOKEN`，以及可用的 `DASHSCOPE_API_KEY`、`DASHSCOPE_BASE_URL` 和 `AGENT_MODEL`，然后在 `backend/.env` 中设置：

```dotenv
ONMYWAY_AGENT_WORKER_ENABLED=true
ONMYWAY_AGENT_DELEGATION_ENABLED=true
ONMYWAY_AGENT_INPUT_TOKENS=12000
ONMYWAY_AGENT_LEASE_SECONDS=90
# 可选：从项目根目录启动时使用此路径。
ONMYWAY_MCP_SERVERS_FILE=backend/data/mcp.servers.json
```

未接入外部工具时将 `ONMYWAY_MCP_SERVERS_FILE` 留空。开启 worker 后会使用配置的模型和工具服务处理已提交任务；模型未配置时任务暂停，并记录 `provider_not_configured`。`GET /agent/status` 可查看 `enabled`、`state` 和脱敏错误；`running` 只代表工作线程已启动，不代表上游模型或所有 MCP 服务已验证可用。修改环境变量或 MCP 配置文件后重启后端。

### 子 Agent 的范围

主 Agent 可直接使用工具，也可根据目标委派检索、比较或规划等子任务；这些职责不限定为旅游场景。每个子任务有独立身份、运行状态和上下文，只能使用主 Agent 显式分配的服务端只读工具，并通过明确引用读取获授权证据，不自动继承完整对话或个人记忆。子 Agent 不能继续创建下一层子任务。

子任务提交成果后，主 Agent 接受前必须完整读取对应版本；需要返工时可拒绝并重新委派。主、子任务共用父修订的总步数、工具次数和活动时间预算，并为主 Agent 的验收留出余量。父任务修改或取消后，旧子任务结果不得覆盖新要求。当前使用同一 worker 顺序执行，不提供分布式并行调度。

此协作机制已有代码和本地测试，真实模型成功创建子任务、查询、交付、父任务验收并完成的整条链路仍需验收。服务端可先在开发电脑运行，远程部署仍需补齐身份认证、HTTPS、访问控制与备份恢复。

### MCP 配置

配置由服务所有者提供，模型不能自行添加服务器地址、凭据或授权。远程连接必须匹配代码中的官方来源注册表，当前核查日期为 2026-11-08：

| `official_service` | 允许的官方端点 | 凭据与公开参数 | 来源 |
| --- | --- | --- | --- |
| `amap` | `https://mcp.amap.com/mcp` | 必须通过 `query_env` 提供 `key` | [高德官方接入说明](https://lbs.amap.com/api/mcp-server/gettingstarted) |
| `exa` | `https://mcp.exa.ai/mcp` | 当前采用无需凭据的连接方式；仅允许 `query_params.tools` 筛选工具 | [Exa 官方 MCP 文档](https://docs.exa.ai/reference/exa-mcp) |

`official_service` 必须与 HTTPS 主机、`/mcp` 路径及默认 443 端口一致；不跟随重定向。不接受任意第三方网关或只改 URL 的新增服务；新增远程服务需先核查第一方来源并扩展注册表。`localhost`、`127.0.0.1`、`::1` 仅作本地协议测试服务，不能声明官方来源或携带查询参数及查询凭据，本地测试通过不算官方服务验收。

下面的配置可保存为 `backend/data/mcp.servers.json`，该目录不进入版本控制。先在私有 `backend/.env` 或后端进程环境中设置 `AMAP_WEB_KEY`，再启用 worker。此配置选择高德地点与天气查询，以及 Exa 网页搜索与读取；实际可用工具仍以服务返回的目录为准：

```json
{
  "servers": [
    {
      "name": "amap",
      "official_service": "amap",
      "url": "https://mcp.amap.com/mcp",
      "query_env": {
        "key": "AMAP_WEB_KEY"
      },
      "allowed_tools": {
        "maps_text_search": "read",
        "maps_search_detail": "read",
        "maps_weather": "read"
      },
      "timeout_seconds": 30,
      "max_response_bytes": 65536
    },
    {
      "name": "exa",
      "official_service": "exa",
      "url": "https://mcp.exa.ai/mcp",
      "query_params": {
        "tools": "web_search_exa,web_fetch_exa"
      },
      "allowed_tools": {
        "web_search_exa": "read",
        "web_fetch_exa": "read"
      },
      "timeout_seconds": 30,
      "max_response_bytes": 65536
    }
  ]
}
```

`query_env` 和可选的 `headers_env` 都填写环境变量名，加载时解析为实际查询参数或请求头；引用未设置或为空的变量会使配置失败。凭据只保存在私有环境中，不能直接写进 JSON、`query_params` 或 URL。`headers_env` 的变量值应是完整请求头值，例如 `Bearer …`；无需请求头认证时省略。配置不接受明文 `headers` 或 `query_secrets`，URL 不能包含用户名、密码、查询串或片段。当前 Exa 注册表未开放 Key 查询参数，不能直接添加 `exaApiKey`。

`allowed_tools` 使用服务实际公布的工具名，只注册列出的工具；值由本地声明为 `read`、`write` 或 `device`。应按照实际影响分类，不能把写入工具标为只读以绕过确认；服务返回的注解不会扩大本地授权。当前 `write` 和 `device` 调用进入逐次确认流程，确认只针对已保存的那次调用及其参数。内置工具命名空间为 `onmyway`，工具结果和成果读取使用 `agent`；外部服务器名称不能占用这两个名称。

工具目录在连接时通过 `tools/list` 获取；模型请求再次发现工具时可刷新配置中的服务，会话过期后也需重新发现。模型先检索简短目录，再获取所选工具的完整输入输出模式。明确指定完整工具编号或服务命名空间时优先匹配该身份，其余结果按描述词汇相关度排列。执行前校验参数、工具版本与授权，工具变化后必须重新判断；MCP 返回内容作为观察数据，不能覆盖系统规则或授予权限。

当前协议范围为 **MCP Streamable HTTP 的 JSON 与 POST SSE 响应**，协商 `2025-06-18` 或 `2025-03-26`，支持初始化、会话标识、分页 `tools/list` 和 `tools/call`。POST 返回 `text/event-stream` 时，在时限和字节预算内读取与请求 ID 匹配的结果，进度通知不作为调用结果。尚不支持旧 GET SSE 传输、stdio、OAuth 自动发现或服务端主动请求。不自动重试工具调用，写入超时等不确定情况保留为结果未知，不能据此宣称写入失败或再次执行。

配置文件上限 64 KiB，最多 10 个服务，每个服务最多 200 个允许工具；服务名必须唯一。单次响应默认上限 64 KiB，可在 1 KiB–8 MiB 内配置，但运行时还有整体结果和模型上下文预算，调大响应上限不保证整份结果进入模型。单次 MCP 超时必须大于 0 且不超过 120 秒。

### 任务 API

以下接口均需 `X-Backup-Token` 请求头，值为配置的 `ONMYWAY_BACKUP_TOKEN`，并受现有后端本机访问限制。`agent_task_id` 表示一次用户目标，区别于待办 `task_id`；`agent_run_id` 对应任务的某次修订运行，旧 H2 `run_id` 仍只是一次能力调用的身份。

| 接口 | 用途 |
| --- | --- |
| `GET /agent/status` | 查看后台执行开关、运行状态和错误码 |
| `POST /agent/tasks` | 持久化目标、约束、成功标准与预算，返回 HTTP 201；不表示任务已执行 |
| `GET /agent/tasks?limit=50` | 查询任务，最多 100 条 |
| `GET /agent/tasks/{agent_task_id}` | 读取当前修订、运行状态、工具调用和成果 |
| `POST /agent/tasks/{agent_task_id}/revisions` | 用完整目标定义创建新修订，隔离旧修订的迟到结果 |
| `POST /agent/tasks/{agent_task_id}/cancel` | 取消后续推进；不撤销已发生的外部效果 |
| `POST /agent/tasks/{agent_task_id}/resume` | 在状态允许时恢复；不清零预算，不重发结果未知的调用 |
| `POST /agent/tasks/{agent_task_id}/calls/{tool_call_id}/approve` | 确认当前修订中的具体待授权调用 |
| `GET /agent/tasks/{agent_task_id}/events?after=0&limit=100` | 按游标读取事件，返回 `next_cursor`、`has_more`，每页最多 200 条 |

创建任务的 JSON 示例（实际发送会按 worker 配置触发模型请求）：

```json
{
  "request_id": "demo-travel-request-001",
  "conversation_id": "demo-conversation-001",
  "message_id": "demo-message-001",
  "goal": "规划一次四天旅行，先查询目的地天气和景点，再给出可修改的每天安排。",
  "constraints": {
    "destination": "杭州",
    "days": 4,
    "pace": "轻松；缺少日期时先询问"
  },
  "success_criteria": ["列出信息来源与未核实事项", "不要预订或支付"],
  "budget": {"max_steps": 16, "max_tool_calls": 12, "max_duration_seconds": 300}
}
```

客户端为每次新操作生成新 `request_id`，同一操作重试则保留原 ID 和原始请求内容；创建任务还按 `conversation_id` 与 `message_id` 去重。同一个身份不能换一份内容重发，否则返回冲突。重试返回原操作响应，需再次 `GET` 任务获取最新进度。

修订请求包含 `request_id`、`expected_revision`、`goal`、`constraints` 和 `success_criteria`；它是完整定义替换，省略的约束或成功标准会变为空。取消、恢复与工具确认使用 `request_id`、`expected_revision` 及可选 `reason`。先读取当前 `revision`，再携带它提交；过期修订不能覆盖最新要求。需要补充信息时通过修订提交更新后的完整目标和约束，或在允许恢复时用 `reason` 提供补充说明。

默认预算为 16 个决策步骤、12 次工具调用和 300 秒累计运行时间。等待用户、等待确认及暂停期间不消耗运行时间，恢复也不会重置已用预算；进程失联时按租约上界保守计入。成果发布与外部执行回执分别保存，任务 `completed` 表示成果已交付，不能据此声称订票、导航或其他外部操作成功。结果未知的外部效果会阻止继续执行，需先核实，当前没有通用外部效果核对 API。

任务、事件、工具结果及成果保存在独立的 `backend/data/agent.sqlite3`；这是运行状态库，不是个人长期记忆，也不包含在 `/sync` 手机业务快照中，迁移服务时需单独备份。内置路线工具只试算方案，不完成正式待办、不确认路线，也不启动设备导航。

内置 `onmyway.read_execution` 接受原能力调用的 UUID `run_id`，只读查询执行记录和已经核实的设备回执；该编号与 `agent_task_id` 不同。只有 `device_execution_observed=true` 及匹配的结果回执才能证明对应设备行为，已记录或已确认不能写成导航已开始。查询不会确认、派发、重试或启动导航，也不会补造设备回执；Android 新任务与设备入口仍待 A2 接入。

### 验证边界与下一步

本地合成模型、协议测试服务和进程重启检查用于验证生产调用路径、任务修订及恢复机制，不能证明真实模型可靠选工具或旅行成果可用。当前真实模型与官方 MCP 评测正在进行，采用合成输入和限定调用预算；逐项复核工具来源、实际返回、成果依据、用户修改、失败及未运行项，A1 仍未关闭。下一步先完成最终代码回归、旅行事实与第二天修订、办事修订复测，以及草稿取消、来源边界和真实主子协作，再进入 A2 的 Android 任务进度、成果、确认与恢复接入，随后 A3 的已授权事件与日常部署。继续保留「取快递 + 打印论文 + 回宿舍」和「四天旅行规划」两组场景；导航设备操作、手机连接与实际出行另行验收。

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

在「设置 → 数据与连接」保存个人服务地址 `http://127.0.0.1:8000`。模拟器可使用 `http://10.0.2.2:8000`。当前本地 HTTP 联调需使用 debug 构建；release 默认禁止明文请求。

Windows 下可运行 `tools/check-device-backend.ps1` 检查电脑后端、恢复 USB 转发并验证手机收到 HTTP 200。USB 重连后可能需要重新转发。`tools/watch-device-backend.ps1 -Serial <SERIAL>` 可在两小时内自动恢复转发，但不能修复 USB 传输故障或模型上游错误。

后端目前只允许来自本机的连接（包括 USB ADB reverse），即使误把 uvicorn 绑定到 0.0.0.0，远程客户端也会收到 HTTP 403。除本地数据同步口令外仍没有部署级用户鉴权和限流；不得通过反向代理绕过本机限制后开放公网。租服务器前需先完成身份、配额和 TLS 设计。语音、对话和地点请求会按功能发送至所配置的服务商；手机保留离线记录，启用电脑数据同步后把已同步记录持久保存到电脑 SQLite。未连接或尚未同步的记录仍只存在手机。

### 数据库存储格式

手机正式记录、助手对话和待确认方案保存在 Room 管理的 SQLite 数据库 `onmyway.db`；旧安装的助手偏好记录会在首次打开时迁入。仓库中的 `app/schemas/com.onmyway.app.data.local.TaskDatabase/*.json` 是 Room 自动生成的**表结构与迁移校验文件**，不是用户记录；升级测试依赖它们，不能改成 `.sqlite` 或直接删除。电脑备份保存在 `backend/data/backups.sqlite3` 的独立 SQL 表（`backup_tasks`、`backup_route_history`、`backup_assistant_messages` 等），不再把整份备份存为单个 JSON 文档。手机与后端仍以 JSON 传输请求；已有路线快照与少量偏好设置仍含 JSON 文本字段，后续会按迁移计划拆为结构化表。

后端另用 `backend/data/harness.db` 保存意图请求的编号、执行状态与建议结果，可用 `ONMYWAY_HARNESS_DB` 指定路径。“完成”只表示建议已经生成，不代表待办或导航已经执行。此库包含建议内容，不在 `/sync` 快照范围内，需要单独备份；仓库忽略该数据库及整个 `backend/data/` 目录。

个人记忆由后端单独保存在 `backend/data/personal_memory.sqlite3`，包括偏好、临时情境、经历及相关来源和版本；手机保存用于离线查看的缓存。当前通过相同数据服务口令连接，在「设置 → 个人记忆」管理，参考与自动保存分别控制。忘记会停用相关记忆及归纳，不会删除聊天原文；业务同步不包含这份记忆数据库，完整的记忆备份恢复流程尚未提供。

### 电脑主记录与手机同步

在 `backend/.env` 填写 `ONMYWAY_BACKUP_TOKEN`（至少 32 个随机字符），重启后端。在 App 的「设置 → 数据与连接」输入相同数据服务口令，点击「连接并同步」；已连接时按钮为「立即同步」。历史配置名保留兼容；电脑主记录仍在 `backend/data/backups.sqlite3`，可用 `ONMYWAY_BACKUP_DB` 指定位置。

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

仅对专用测试设备或模拟器运行 `:app:connectedDebugAndroidTest`，用 `ANDROID_SERIAL` 指定目标，避免清理用户手机 App 和数据。测试后执行 `:app:installDebug`，再运行 `tools/check-device-backend.ps1 -Serial <SERIAL>` 验证安装及手机端 HTTP 200 和 `status=ok`。后端单元测试使用模拟地图与模型响应；真实定位、录音、高德导航及账号可用性需自行验收。

新 Agent 切片的测试应覆盖任务修订、工具选择、参数校验、调用预算、结果未知和恢复，不只检查模型输出格式。真实模型或外部 MCP 验证需记录服务、合成输入范围、调用预算与结果；服务错误和未运行项目分别标注，不能计为通过。仅修改文档时检查事实、链接和新旧计划一致性，不把文档检查写成运行时验收。
