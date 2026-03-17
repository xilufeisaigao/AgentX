# process 模块

## 职责

`process` 是当前系统的编排中枢。
它不拥有业务实体，但拥有跨模块自动推进逻辑：

- 监听领域事件
- 轮询 scheduler
- 创建/关闭 ticket
- 触发 planning
- 触发 context refresh
- 拉起 worker auto provision / auto run
- 处理 verify 失败恢复
- 处理 merge gate 完成
- 处理 runtime garbage collection

如果说这个项目哪里最像“大脑”，就是这里。

## 入站入口

### API

- [ArchitectAutomationController](../../src/main/java/com/agentx/agentxbackend/process/api/ArchitectAutomationController.java)
- [WorkforceAutomationController](../../src/main/java/com/agentx/agentxbackend/process/api/WorkforceAutomationController.java)
- [RuntimeLlmConfigController](../../src/main/java/com/agentx/agentxbackend/process/api/RuntimeLlmConfigController.java)

### Event Listener

- [SessionCreatedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/SessionCreatedEventListener.java)
- [RequirementConfirmedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RequirementConfirmedEventListener.java)
- [RequirementHandoffRequestedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RequirementHandoffRequestedEventListener.java)
- [ContextRefreshEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ContextRefreshEventListener.java)
- [RunDomainEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RunDomainEventListener.java)

### Scheduler

- [ArchitectAutoProcessScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ArchitectAutoProcessScheduler.java)
- [WorkerAutoProvisionScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerAutoProvisionScheduler.java)
- [WorkerRuntimeScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerRuntimeScheduler.java)
- [WorkerPoolCleanupScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerPoolCleanupScheduler.java)
- [RunLeaseWatchdogScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RunLeaseWatchdogScheduler.java)
- [RuntimeGarbageCollectionScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RuntimeGarbageCollectionScheduler.java)
- [RuntimeEnvironmentCleanupScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RuntimeEnvironmentCleanupScheduler.java)

## 主要表

`process` 没有独占表，但会操作很多核心实体：

- `tickets`
- `ticket_events`
- `work_modules`
- `work_tasks`
- `task_context_snapshots`
- `task_runs`
- `task_run_events`

## 关键代码入口

- session 启动:
  [SessionBootstrapInitProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/SessionBootstrapInitProcessManager.java)
- requirement -> architect:
  [RequirementConfirmedProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RequirementConfirmedProcessManager.java)
- architect -> planning:
  [ArchitectTicketAutoProcessorService](../../src/main/java/com/agentx/agentxbackend/process/application/ArchitectTicketAutoProcessorService.java)
  [ArchitectWorkPlanningService](../../src/main/java/com/agentx/agentxbackend/process/application/ArchitectWorkPlanningService.java)
- context refresh:
  [ContextRefreshProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/ContextRefreshProcessManager.java)
- worker 自动化:
  [WorkerAutoProvisionService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerAutoProvisionService.java)
  [WorkerRuntimeAutoRunService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerRuntimeAutoRunService.java)
- run 完成:
  [RunFinishedProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RunFinishedProcessManager.java)
  [RunNeedsInputProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java)
  [VerifyFailureRecoveryProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/VerifyFailureRecoveryProcessManager.java)
- merge 完成:
  [DeliveredTaskMergeGateProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/DeliveredTaskMergeGateProcessManager.java)
  [MergeGateCompletionProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/MergeGateCompletionProcessManager.java)
- 垃圾回收:
  [RuntimeGarbageCollectionService](../../src/main/java/com/agentx/agentxbackend/process/application/RuntimeGarbageCollectionService.java)

## 在全链路里的位置

这就是全链路真正串起来的地方。
如果没有 `process`，各模块只是一堆静态能力：

1. `session` 能建会话，但不会自动出 bootstrap task
2. `requirement` 能确认需求，但不会自己变成 architect ticket
3. `planning` 能建任务，但不会自己驱动 worker
4. `execution` 能跑 run，但不会自己进入 merge gate

## 想查什么就看哪里

- 为什么系统会自动往前推进
  - 先看 scheduler
  - 再看对应 process manager
- 为什么失败后还能恢复
  - 看 [VerifyFailureRecoveryProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/VerifyFailureRecoveryProcessManager.java)
- 为什么 run 需要人工输入时会生成 ticket
  - 看 [RunNeedsInputProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java)
- 为什么 runtime 配置和 env 不一致
  - 看 [RuntimeLlmConfigController](../../src/main/java/com/agentx/agentxbackend/process/api/RuntimeLlmConfigController.java)

## 调试入口

- API: `POST /api/v0/architect/auto-process`
- API: `POST /api/v0/workforce/auto-provision`
- API: `POST /api/v0/workforce/runtime/auto-run`
- API: `POST /api/v0/execution/lease-recovery`
- API: `GET /api/v0/runtime/llm-config`
- backend 日志: `docker compose --env-file .env.docker logs -f backend`

## 工程优化思路

### 近期整理

- 把各个 scheduler 的轮询日志统一格式，至少包含 `session_id`、`ticket_id`、`task_id`、`run_id`。
- 给 process manager 的输入输出补更明确的边界注释和单测。

### 可维护性与可观测性

- 将关键编排路径画成显式状态机并对齐测试，而不是只依赖过程式串联。
- 为每个 scheduler 增加“本次为什么没做事”的可观测信息。

### 中长期演进

- 把过于庞大的 `process` 拆成更少耦合的 orchestration 子域。
- 逐步将跨模块编排从“类与类直接协作”演进到更清晰的命令/事件协议层。
