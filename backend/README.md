# 后端目录与运行入口

后端按职责分包。HTTP 入口保持 `backend.main:app`，现有接口与数据位置不因目录整理改变。

```text
backend/
├── main.py               FastAPI 装配、公共接口与启动入口
├── paths.py              固定项目根、后端根和数据目录
├── agent/                A1 任务、模型决策、工具/MCP、成果与核验
├── assistant/            当前 Android 助手接口与旧请求恢复账本
├── planning/             高德适配、路线模型、候选筛选与规划
├── execution/            能力契约、执行回执、确认与业务事件
├── memory/               个人记忆、助手接入与旧格式验证
├── data_services/        正式待办、备份与同步
├── evaluation/           离线回放、模型评测与 Agent 评测记录
│   └── cases/            固定办事场景与模型评测输入
├── tests/                按上述职责分类的回归测试
│   ├── fixtures/         原始失败上下文与共享测试数据
│   ├── test_api.py       公共 HTTP 接口
│   └── test_package_layout.py  默认存储位置与案例资源回归
├── data/                 本地数据库，Git 忽略
├── logs/                 本地运行日志，Git 忽略
├── .env                  本地配置，Git 忽略
├── .env.example          配置示例
└── requirements*.txt     运行及测试依赖
```

## 常用入口

以下命令从仓库根目录执行：

```powershell
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
python -m pytest backend/tests -q
python -m pytest backend/tests/agent -q
python -m backend.evaluation.replay --output app/build/reports/harness-replay.json
python -m backend.evaluation.model --help
python tools/evaluate_agent_a1.py --help
```

`evaluation.replay` 使用固定响应和隔离数据；真实模型评测需要明确的数据范围、预算和适用授权，离线回放不能替代真实模型或设备验收。评测程序按新包路径导入，旧的 `backend.replay`、`backend.model_eval` 命令已迁移。

## 新增代码放在哪里

- 通用任务运行、上下文、工具发现、MCP、成果审查放在 `agent/`；新增业务不向核心循环加入场景分支。
- 地图与路线业务放在 `planning/`；执行身份、确认、未知结果处理放在 `execution/`。
- 正式待办与备份同步放在 `data_services/`；长期偏好与学习规则放在 `memory/`。
- 当前 Android 助手和旧请求恢复放在 `assistant/`。A2 接管并完成回归前，它们仍是生产依赖。
- 评测实现和样本放在 `evaluation/`；对应测试放进 `tests/` 下同名目录。共享上下文夹具继续放 `tests/fixtures/`。

包的 `__init__.py` 不加载服务或提供旧模块别名。代码使用明确模块路径，例如 `from backend.agent.store import AgentStore`，避免同名包被误当成原来的模块。函数内原有延迟导入继续保留，以处理现有业务依赖。

## 数据与迁移边界

五个默认数据库仍位于 `backend/data/`，原有 `ONMYWAY_*_DB` 配置和 `AgentStore` 显式路径优先级保持不变。`main.py` 仍从 `backend/.env` 加载配置。移动模块时不得用新的 `__file__` 相邻目录替换这些稳定路径。

2026-11-10 的整理没有删除仍被 HTTP、Android、Agent 或评测调用的生产模块；仅合并一处重复 JSON 键检查函数。旧真实评测报告、输入、哈希和状态保留原记录，不能把包迁移后的本地回归写成新的真实模型通过。
