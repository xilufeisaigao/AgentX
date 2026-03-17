# query 模块

## 职责

`query` 负责把底层表和规则拼成用户看得懂的进度视图：

- session progress
- ticket inbox
- task board
- run timeline

它不是简单 CRUD，而是整个系统“解释自己当前状态”的读模型层。

## 入站入口

- API:
  [ProgressQueryController](../../src/main/java/com/agentx/agentxbackend/query/api/ProgressQueryController.java)
  - `getSessionProgress`
  - `getTicketInbox`
  - `getTaskBoard`
  - `getRunTimeline`

## 主要表

query 没有独占表。
它综合读取：

- `sessions`
- `requirement_docs`
- `tickets`
- `ticket_events`
- `work_modules`
- `work_tasks`
- `task_context_snapshots`
- `task_runs`
- `task_run_events`

以及 `session` 模块的 completion readiness 规则。

## 关键代码入口

- 查询服务:
  [ProgressQueryService](../../src/main/java/com/agentx/agentxbackend/query/application/ProgressQueryService.java)
  - `getSessionProgress`
  - `getTicketInbox`
  - `getTaskBoard`
  - `getRunTimeline`
- 依赖的 readiness:
  [SessionCompletionReadinessService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionCompletionReadinessService.java)
  - `getCompletionReadiness`

## 在全链路里的位置

query 不推进流程，但决定你怎么理解流程。

例如当前样本里：

- `runCounts.failed = 2`
- 但 `sessionStatus = COMPLETED`

这是 query 视角在告诉你：

- 中间确实失败过
- 但系统最终恢复成功并完成了闭环

还要注意一个运行时事实：

- query 接口当前实际输出字段名是 `camelCase`
- 不要按旧设计想当然地把它当成 `snake_case`

## 想查什么就看哪里

- `canCompleteSession` 是怎么来的
  - 看 [ProgressQueryService](../../src/main/java/com/agentx/agentxbackend/query/application/ProgressQueryService.java)
  - 再看 [SessionCompletionReadinessService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionCompletionReadinessService.java)
- `phase` 是怎么来的
  - 看 `getSessionProgress`
- `deliveryTagPresent` 是怎么来的
  - 看 progress 聚合逻辑和 git delivery 证据读取
- run timeline 为什么能看到失败恢复
  - 看 `getRunTimeline`

## 调试入口

- API: `GET /api/v0/sessions/{sessionId}/progress`
- API: `GET /api/v0/sessions/{sessionId}/ticket-inbox`
- API: `GET /api/v0/sessions/{sessionId}/task-board`
- API: `GET /api/v0/sessions/{sessionId}/run-timeline`

## 工程优化思路

### 近期整理

- 明确每个 query 字段的来源，避免把聚合字段误当成单表字段。
- 给 progress 里的 blocker 和 action 补稳定枚举值。

### 可维护性与可观测性

- 把 query 字段来源文档化或代码内结构化标注。
- 为 progress 和 task board 增加更细的解释字段，减少只能靠人脑倒推。

### 中长期演进

- 将 query 层逐步演进为独立 read model，而不是在服务内即时拼接全部逻辑。
- 为前端和排查工具提供面向诊断的查询视图，而不仅是展示视图。
