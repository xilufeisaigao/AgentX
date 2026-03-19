# AgentX 真相源说明

这页只讲一个问题：
当不同文档、接口、日志、代码看起来互相矛盾时，应该先信什么。

## 优先级

### 1. 表结构真相

- [../schema/agentx_schema_v0.sql](../schema/agentx_schema_v0.sql)

适合回答：

- 某个字段到底存不存在
- 某个关联是不是直接字段还是要 join
- 哪些表是真正的一手持久化状态

### 2. API 形状真相

- [../openapi/agentx-control-plane.v0.yaml](../openapi/agentx-control-plane.v0.yaml)

适合回答：

- 控制面暴露了哪些 HTTP 接口
- 请求和响应的基本 shape 是什么

### 3. 运行链路真相

- [../architecture/03-end-to-end-chain.md](../architecture/03-end-to-end-chain.md)

适合回答：

- 某次动作之后为什么会自动触发后续步骤
- 哪些是事件驱动，哪些是 scheduler 驱动
- 哪些类组成了完整主链路

### 4. 当前状态真相

- [../current-state/02-runtime-audit-2026-03-17.md](../current-state/02-runtime-audit-2026-03-17.md)

适合回答：

- 现在的 Docker 运行面到底是什么状态
- 最近一次真实闭环样本是什么
- 运行时 LLM 配置和环境变量是否一致

### 5. 代码定位真相

- [../05-code-index.md](../05-code-index.md)

适合回答：

- 某个行为先看哪个类、哪个方法
- 某个问题应该从哪个模块切入

## 常见误区

### 误区 1: 把 query 字段当表字段

例如：

- `canCompleteSession`
- `phase`
- `deliveryTagPresent`

这些字段来自聚合查询和规则计算，不是 `sessions` 表原生字段。

另外：

- query 接口当前对外 JSON 字段名是 `camelCase`
- 读响应时优先相信真实接口输出和 controller 测试

### 误区 2: 只看 `.env.docker`

当前系统支持运行时 LLM 配置覆盖。
所以看到 provider/model 对不上时，优先看：

- `GET /api/v0/runtime/llm-config`

### 误区 3: 只看 worktree

worktree 是短生命周期。
长期证据更多保留在：

- `task_runs`
- `task_run_events`
- `task/*` 分支
- `run/*` 分支
- `delivery/*` tag
