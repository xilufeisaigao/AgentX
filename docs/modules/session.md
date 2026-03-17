# session 模块

## 职责

`session` 管“会话本身”：

- 创建、暂停、恢复、完成 session
- 查询 session 历史和当前 requirement 摘要
- 判断 session 是否已经具备 `complete` 条件

它不负责自动规划、执行和 merge。
这些动作都在 `process` 编排下发生。

## 入站入口

- API:
  [SessionCommandController](../../src/main/java/com/agentx/agentxbackend/session/api/SessionCommandController.java)
  - `createSession`
  - `pauseSession`
  - `resumeSession`
  - `completeSession`
- API:
  [SessionQueryController](../../src/main/java/com/agentx/agentxbackend/session/api/SessionQueryController.java)
  - `listSessionsWithCurrentRequirementDoc`
  - `getSessionWithCurrentRequirementDoc`

## 主要表

- `sessions`

补充说明：

- `canCompleteSession` 不是 `sessions` 表字段。
- 这个值来自查询层和 readiness 规则。

## 关键代码入口

- 应用服务:
  [SessionCommandService](../../src/main/java/com/agentx/agentxbackend/session/application/SessionCommandService.java)
  - `createSession`
  - `completeSession`
- 查询服务:
  [SessionHistoryQueryService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionHistoryQueryService.java)
  - `listSessionsWithCurrentRequirementDoc`
  - `findSessionWithCurrentRequirementDoc`
- 完成条件:
  [SessionCompletionReadinessService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionCompletionReadinessService.java)
  - `getCompletionReadiness`

## 在全链路里的位置

session 是整条链的起点和终点：

1. `createSession` 写入 `sessions`
2. 发布 `SessionCreatedEvent`
3. `process` 模块收到事件后自动创建 bootstrap 任务
4. 当 query 层判断满足完成条件后，再允许 `completeSession`

## 想查什么就看哪里

- 新 session 为什么自动带一个 bootstrap task
  - 看 [SessionCreatedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/SessionCreatedEventListener.java)
  - 再看 [SessionBootstrapInitProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/SessionBootstrapInitProcessManager.java)
- 为什么 session 现在还不能 complete
  - 看 [SessionCompletionReadinessService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionCompletionReadinessService.java)
  - 再看 [ProgressQueryService](../../src/main/java/com/agentx/agentxbackend/query/application/ProgressQueryService.java)
- session 历史页怎么带出当前 requirement
  - 看 [SessionHistoryQueryService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionHistoryQueryService.java)

## 调试入口

- API: `GET /api/v0/sessions`
- API: `GET /api/v0/sessions/{sessionId}`
- API: `GET /api/v0/sessions/{sessionId}/progress`
- SQL: `select * from sessions where session_id = '<SESSION_ID>';`

## 工程优化思路

### 近期整理

- 把 `complete` 失败原因输出得更明确，避免只能去 query 层倒推。
- 给 `SessionCreatedEvent` 和 `complete` 结果补更稳定的结构化日志。

### 可维护性与可观测性

- 把 completion readiness 拆成可枚举规则列表，并暴露更细粒度的 blocker 来源。
- 为 session 生命周期补统一 audit trail。

### 中长期演进

- 让 session 只保留生命周期控制，所有聚合态统一下沉到 query/read model。
- 把 session 完成条件从服务内计算演进为可测试的策略对象集合。
