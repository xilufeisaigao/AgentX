# planning 模块

## 职责

`planning` 负责把 architect 的规划结果落成任务网络：

- 建 module
- 建 task
- 建 task dependency
- 维护 task 状态迁移
- 为 worker 领取任务提供 ready 队列

## 入站入口

- API:
  [PlanningController](../../src/main/java/com/agentx/agentxbackend/planning/api/PlanningController.java)
  - `createModule`
  - `createTask`
  - `addTaskDependency`

更常见的真实入口不是人工 API，而是：

- [ArchitectWorkPlanningService](../../src/main/java/com/agentx/agentxbackend/process/application/ArchitectWorkPlanningService.java)
  - `planAndPersist`

## 主要表

- `work_modules`
- `work_tasks`
- `work_task_dependencies`

## 关键代码入口

- 核心服务:
  [PlanningCommandService](../../src/main/java/com/agentx/agentxbackend/planning/application/PlanningCommandService.java)
  - `createModule`
  - `createTask`
  - `addTaskDependency`
  - `markAssigned`
  - `markDelivered`
  - `markDone`
  - `releaseAssignment`
  - `reopenDelivered`
  - `refreshWaitingTasks`
  - `claimReadyTaskForWorker`

## 在全链路里的位置

planning 是 requirement 和 execution 之间的落地点：

1. architect 产出模块/任务计划
2. planning 把它变成真正可执行的数据结构
3. execution 再从 planning 的 ready 任务里 claim

## 想查什么就看哪里

- 为什么某个 session 只有这些任务
  - 看 [ArchitectWorkPlanningService](../../src/main/java/com/agentx/agentxbackend/process/application/ArchitectWorkPlanningService.java)
  - 再看 [PlanningCommandService](../../src/main/java/com/agentx/agentxbackend/planning/application/PlanningCommandService.java)
- 为什么某个 task 还在 `WAITING_DEPENDENCY`
  - 看 `work_task_dependencies`
  - 再看 `refreshWaitingTasks`
- 为什么 worker 领取不到任务
  - 看 `claimReadyTaskForWorker`
  - 再联动看 `workforce` 和 `execution`

## 调试入口

- API: `GET /api/v0/sessions/{sessionId}/task-board`
- SQL: `select * from work_modules where session_id = '<SESSION_ID>';`
- SQL: `select * from work_tasks where module_id = '<MODULE_ID>';`
- SQL: `select * from work_task_dependencies where task_id = '<TASK_ID>' or depends_on_task_id = '<TASK_ID>';`

## 工程优化思路

### 近期整理

- 明确 task 状态流转约束，减少 `mark*` 方法的隐式前置条件。
- 为 task dependency 引入更清晰的非法状态报错。

### 可维护性与可观测性

- 给 planning 增加任务状态迁移测试矩阵。
- 让 task board 查询能直接解释“为什么当前不是 READY”。

### 中长期演进

- 把 planning 结果从简单任务列表演进为可验证的 DAG 模型。
- 进一步把任务模板、所需 toolpack、verify 策略显式建模，而不是散落在字符串和 JSON 中。
