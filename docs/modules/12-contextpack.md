# contextpack 模块

## 职责

`contextpack` 负责把 session、ticket、task、run 等事实编译成给 agent 使用的上下文快照：

- 编译 role pack
- 编译 task context pack
- 编译 task skill
- 刷新 task context snapshot 状态

它是“让执行拿到正确上下文”的关键中枢。

## 入站入口

- API:
  [ContextCompileController](../../src/main/java/com/agentx/agentxbackend/contextpack/api/ContextCompileController.java)
  - `compileRolePack`
  - `compileTaskContextPack`
  - `compileTaskSkill`
  - `getTaskContextStatus`

自动触发入口：

- [ContextRefreshProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/ContextRefreshProcessManager.java)
  - `handleRequirementConfirmed`
  - `handleTicketEvent`
  - `handleRunFinished`

## 主要表

- `task_context_snapshots`

同时还有文件系统落点：

- `/agentx/runtime-data/context/context/task-context-packs`
- `/agentx/runtime-data/context/context/task-skills`

## 关键代码入口

- 核心服务:
  [ContextCompileService](../../src/main/java/com/agentx/agentxbackend/contextpack/application/ContextCompileService.java)
  - `compileRolePack`
  - `compileTaskContextPack`
  - `compileTaskSkill`
  - `refreshTaskContextsBySession`
  - `refreshTaskContextByTask`
  - `getTaskContextStatus`
- 事实查询:
  [MybatisContextFactsQueryAdapter](../../src/main/java/com/agentx/agentxbackend/contextpack/infrastructure/persistence/MybatisContextFactsQueryAdapter.java)
  - `findRequirementBaselineBySessionId`
  - `findTaskPlanningByTaskId`
  - `listRecentArchitectureTickets`
  - `listRecentTicketEvents`
  - `listRecentTaskRuns`

## 在全链路里的位置

它决定某个 run 到底会看到什么上下文。

真实过程是：

1. planning 或 ticket 变化
2. `process` 触发 context refresh
3. 生成新的 `task_context_snapshots`
4. run claim 时绑定最新 `READY` snapshot

## 想查什么就看哪里

- 为什么这个 task 现在没有 READY context
  - 看 `task_context_snapshots`
  - 再看 `getTaskContextStatus`
- 某个 context pack 用了哪些事实
  - 看 [MybatisContextFactsQueryAdapter](../../src/main/java/com/agentx/agentxbackend/contextpack/infrastructure/persistence/MybatisContextFactsQueryAdapter.java)
- 某次 run 到底绑定了哪个 context
  - 看 `task_runs.context_snapshot_id`
  - 再看 `task_context_snapshots`

## 调试入口

- API: `GET /api/v0/tasks/{taskId}/context-status`
- API: `POST /api/v0/context/task-context-pack:compile`
- SQL: `select * from task_context_snapshots where task_id = '<TASK_ID>' order by compiled_at desc;`
- 文件系统: `/agentx/runtime-data/context/context/task-context-packs/TASK-*`

## 工程优化思路

### 近期整理

- 明确 snapshot 状态和触发来源，减少“为什么重编译”的黑盒感。
- 给 pack/skill 文件加稳定 metadata，便于直接读文件排查。

### 可维护性与可观测性

- 为 context refresh 触发链补结构化事件。
- 增加 snapshot 与 run 绑定关系的可视化查询。

### 中长期演进

- 把 context 编译从字符串拼装演进为结构化上下文模型。
- 为不同任务类型提供可配置的 context 裁剪和预算策略。
