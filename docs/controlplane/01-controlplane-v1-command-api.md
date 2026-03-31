# Controlplane V1 Command API

本文只定义控制面的第一批命令接口，不讨论查询接口、Dashboard、列表页或展示层 DTO。

当前目标很收敛：

1. 给固定主链补正式的 command-side controlplane API
2. 让 requirement / ticket / workflow 启动这些人工入口不再只能通过测试或直接调 use case
3. 把 agent 资产管理的边界先固定在 `AgentDefinition`，而不是误把临时 runtime instance 当成控制面资产

## 1. 当前边界

### 1.1 本批只做 command API

本批控制面只做这三组命令能力：

1. workflow 启动与手工驱动
2. requirement / ticket 的人类闭环
3. agent definition 的注册与启停

本批明确不做：

1. workflow / ticket / task / run 查询接口
2. dashboard / 列表 / 展示面 read model
3. agent pool 运行视图
4. 直接管理容器或 `agent_pool_instances`

### 1.2 为什么管理的是 AgentDefinition，而不是 AgentPoolInstance

当前平台里：

1. `AgentDefinition` 是平台资产
2. `AgentPoolInstance` 是 runtime 在派发 task run 时动态创建、续租和回收的运行实例

所以控制面第一批应该管理：

1. agent 是否注册
2. agent 是否启用
3. agent 绑定哪些 capability packs

而不是让人手工创建或删除某个 runtime instance。

## 2. 第一批接口范围

## 2.1 Workflow Commands

### `POST /api/v1/controlplane/workflows`

作用：

1. 启动新的固定 coding workflow
2. 写入 requirement seed
3. 返回 command 结果摘要

请求字段：

1. `title`
2. `requirementTitle`
3. `requirementContent`
4. `createdByActorId`
5. `autoAgentMode`

### `POST /api/v1/controlplane/workflows/{workflowRunId}/drive`

作用：

1. 手工驱动 workflow 到当前稳定点
2. 主要用于开发、运维和第一版控制面

说明：

1. 后台 driver/scheduler 继续保留
2. 这个接口不是为了替代异步内核，而是为了提供一个显式操作入口

## 2.2 Ticket / Requirement Commands

### `POST /api/v1/controlplane/tickets/{ticketId}/answer`

作用：

1. 回答 requirement clarification
2. 回答 architect clarification
3. 回答 runtime / verify / coding 产生的人类 ticket

请求字段：

1. `answer`
2. `answeredByActorId`

### `PUT /api/v1/controlplane/workflows/{workflowRunId}/requirement/current`

作用：

1. 直接提交完整 requirement 新版本
2. 触发 requirement 节点重新审阅

请求字段：

1. `docId`
2. `title`
3. `content`
4. `editedByActorId`

### `POST /api/v1/controlplane/workflows/{workflowRunId}/requirement/confirm`

作用：

1. 显式确认当前 requirement 最新版本
2. 关闭当前 requirement confirmation ticket

请求字段：

1. `docId`
2. `version`
3. `confirmedByActorId`

## 2.3 Agent Commands

### `POST /api/v1/controlplane/agents`

作用：

1. 注册新的 `AgentDefinition`
2. 在创建时绑定 capability packs

请求字段：

1. `agentId`
2. `displayName`
3. `purpose`
4. `runtimeType`
5. `model`
6. `maxParallelRuns`
7. `architectSuggested`
8. `autoPoolEligible`
9. `manualRegistrationAllowed`
10. `enabled`
11. `capabilityPackIds`

### `PATCH /api/v1/controlplane/agents/{agentId}/enable`

作用：

1. 启用 agent definition

### `PATCH /api/v1/controlplane/agents/{agentId}/disable`

作用：

1. 停用 agent definition
2. 这也是当前“删除 worker”语义在控制面中的安全映射

说明：

1. 第一版不做物理删除
2. 历史 `task_runs / workflow_node_runs / agent_pool_instances` 仍会引用该 agent

### `PUT /api/v1/controlplane/agents/{agentId}/capability-packs`

作用：

1. 替换现有 `AgentDefinition` 的 capability pack 绑定
2. 让控制面可以调整“这个 agent 定义到底能接哪些 capability”，而不必直接改库

请求字段：

1. `capabilityPackIds`

## 3. 命令结果返回

本批 command API 不直接返回展示面大 DTO，只返回 command result summary。

### Workflow 类命令统一返回

建议返回：

1. `workflowRunId`
2. `workflowStatus`
3. `requirementDocId`
4. `requirementStatus`
5. `currentRequirementVersion`
6. `confirmedRequirementVersion`
7. `pendingHumanTickets`
8. `openTaskBlockers`
9. `taskCounts`

### Agent 类命令统一返回

建议返回：

1. `agentId`
2. `displayName`
3. `runtimeType`
4. `model`
5. `enabled`
6. `architectSuggested`
7. `autoPoolEligible`
8. `manualRegistrationAllowed`
9. `capabilityPackIds`

## 4. architect 与“创建新 agent”的关系

当前系统已经有：

1. capability -> agent 的派发前检查
2. architect suggestion / auto-pool 的字段和配置位

但当前还没有形成下面这条闭环：

1. architect 发现没有合适 agent
2. 创建正式提请
3. controlplane 审核并注册新 agent
4. workflow 自动恢复派发

所以本批控制面的 agent 注册/启停能力，是为后续这条闭环打基础，而不是说这条闭环已经完成。

## 5. 实施顺序

### P11.1

先做完整 command-side 基线：

1. workflow start / drive
2. ticket answer
3. requirement edit / confirm
4. agent definition create / enable / disable
5. capability pack 绑定更新

### P11.2

最后再做：

1. query-side controlplane API
2. dashboard / inbox / runtime ops / DAG / task-run 展示接口

## 6. 当前原则

1. command API 必须复用现有 use case 或聚合命令，不绕过主链真相直接改表
2. 不把 `agent_pool_instances` 误建模为控制面资产
3. 不在 command API 阶段提前引入展示层 read model 膨胀
4. requirement / ticket / agent 管理先做稳，再进入查询和 UI
