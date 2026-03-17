# AgentX 全链路

这一页只回答一件事：
从头到尾，一次最小 session 是怎么被系统自动推进到交付的。

## 先看图

```mermaid
flowchart LR
  A["POST /api/v0/sessions"] --> B["session 模块创建 Session"]
  B --> C["SessionCreatedEventListener"]
  C --> D["SessionBootstrapInitProcessManager 创建 bootstrap 模块和 init task"]
  D --> E["POST /api/v0/sessions/{id}/requirement-agent/drafts"]
  E --> F["requirement 模块生成 requirement draft"]
  F --> G["POST /api/v0/requirement-docs/{docId}/confirm"]
  G --> H["RequirementConfirmedEventListener"]
  H --> I["RequirementConfirmedProcessManager 创建 ARCH_REVIEW ticket"]
  I --> J["ArchitectAutoProcessScheduler 轮询"]
  J --> K["ArchitectTicketAutoProcessorService + ArchitectWorkPlanningService"]
  K --> L["planning 模块创建 work_modules / work_tasks / dependencies"]
  L --> M["ContextRefreshProcessManager / ContextCompileService 刷 context"]
  M --> N["WorkerAutoProvisionScheduler / WorkerRuntimeScheduler"]
  N --> O["RunCommandService claim run"]
  O --> P["WorkspaceService + JGitClientAdapter 分配 worktree"]
  P --> Q["LocalWorkerTaskExecutor 执行 IMPL"]
  Q --> R["RunFinishedProcessManager"]
  R --> S["DeliveredTaskMergeGateProcessManager"]
  S --> T["MergeGateService + CliGitClientAdapter"]
  T --> U["RunCommandService 创建 VERIFY run"]
  U --> V["LocalWorkerTaskExecutor 执行 VERIFY"]
  V --> W["MergeGateCompletionProcessManager / MergeGateCompletionService"]
  W --> X["task 进入 DONE，session 可 complete"]
  X --> Y["POST /api/v0/sessions/{id}/complete"]
  Y --> Z["POST /api/v0/sessions/{id}/delivery/clone-repo"]
```

## 分阶段解释

### 1. Session 创建

入口：

- [SessionCommandController](../../src/main/java/com/agentx/agentxbackend/session/api/SessionCommandController.java)
- [SessionCommandService](../../src/main/java/com/agentx/agentxbackend/session/application/SessionCommandService.java)

关键动作：

- 写 `sessions`
- 发布 `SessionCreatedEvent`

紧接着触发：

- [SessionCreatedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/SessionCreatedEventListener.java)
- [SessionBootstrapInitProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/SessionBootstrapInitProcessManager.java)

结果：

- 自动建一个 `bootstrap` 模块
- 自动建一个 `tmpl.init.v0` 初始化任务

### 2. Requirement 草案与确认

入口：

- [RequirementAgentController](../../src/main/java/com/agentx/agentxbackend/requirement/api/RequirementAgentController.java)
- [RequirementAgentDraftService](../../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementAgentDraftService.java)
- [RequirementDocController](../../src/main/java/com/agentx/agentxbackend/requirement/api/RequirementDocController.java)
- [RequirementDocCommandService](../../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementDocCommandService.java)

关键动作：

- 写 `requirement_docs`
- 写 `requirement_doc_versions`
- `confirm` 后发布 `RequirementConfirmedEvent`

### 3. Requirement 确认后进入 Architect 自动处理

入口：

- [RequirementConfirmedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RequirementConfirmedEventListener.java)
- [RequirementConfirmedProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RequirementConfirmedProcessManager.java)

关键动作：

- 创建 `ARCH_REVIEW` ticket
- 写 `tickets`
- 写 `ticket_events`

随后由 scheduler 推进：

- [ArchitectAutoProcessScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ArchitectAutoProcessScheduler.java)
- [ArchitectTicketAutoProcessorService](../../src/main/java/com/agentx/agentxbackend/process/application/ArchitectTicketAutoProcessorService.java)

### 4. Architect 产出模块和任务

关键类：

- [ArchitectWorkPlanningService](../../src/main/java/com/agentx/agentxbackend/process/application/ArchitectWorkPlanningService.java)
- [PlanningCommandService](../../src/main/java/com/agentx/agentxbackend/planning/application/PlanningCommandService.java)

结果：

- 创建 `work_modules`
- 创建 `work_tasks`
- 创建 `work_task_dependencies`

最小闭环样本里实际产出：

- `bootstrap` 模块 1 个任务
- `健康检查服务模块` 4 个任务

### 5. Context 刷新和编译

关键类：

- [ContextRefreshProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/ContextRefreshProcessManager.java)
- [ContextCompileService](../../src/main/java/com/agentx/agentxbackend/contextpack/application/ContextCompileService.java)

结果：

- 生成 `task_context_snapshots`
- 将 pack 和 skill 写到 `/agentx/runtime-data/context/...`

这一步很关键，因为 run 并不是直接拿 session 文本去执行，而是绑定最近一次 `READY` 的 context snapshot。

### 6. Worker 自动供给与自动执行

关键类：

- [WorkerAutoProvisionService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerAutoProvisionService.java)
- [WorkerRuntimeAutoRunService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerRuntimeAutoRunService.java)
- [WorkerAutoProvisionScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerAutoProvisionScheduler.java)
- [WorkerRuntimeScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerRuntimeScheduler.java)

它们做的事：

- 扫描等待中的任务
- 确保有匹配 toolpack 的 worker
- 让 worker claim task
- 创建 run 并开始 lease/heartbeat 生命周期

### 7. Run 创建、工作区分配、代码执行

关键类：

- [RunCommandService](../../src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java)
- [WorkspaceService](../../src/main/java/com/agentx/agentxbackend/workspace/application/WorkspaceService.java)
- [JGitClientAdapter](../../src/main/java/com/agentx/agentxbackend/workspace/infrastructure/external/JGitClientAdapter.java)
- [LocalWorkerTaskExecutor](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java)

关键动作：

- 分配 `git_workspaces`
- 创建 `run/*` 分支和 worktree
- 执行 IMPL
- 写 `task_runs`
- 写 `task_run_events`

### 8. Run 结束后的流程分叉

统一入口：

- [RunDomainEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RunDomainEventListener.java)
- [RunFinishedProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RunFinishedProcessManager.java)
- [RunNeedsInputProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java)

分叉逻辑：

- 成功交付时，进入 merge gate
- verify 失败时，可能由 [VerifyFailureRecoveryProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/VerifyFailureRecoveryProcessManager.java) 拉起恢复
- 需要人类输入时，不直接问用户，而是转成 ticket

### 9. Merge Gate 与 VERIFY

关键类：

- [DeliveredTaskMergeGateProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/DeliveredTaskMergeGateProcessManager.java)
- [MergeGateService](../../src/main/java/com/agentx/agentxbackend/mergegate/application/MergeGateService.java)
- [CliGitClientAdapter](../../src/main/java/com/agentx/agentxbackend/mergegate/infrastructure/external/CliGitClientAdapter.java)
- [MergeGateCompletionProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/MergeGateCompletionProcessManager.java)
- [MergeGateCompletionService](../../src/main/java/com/agentx/agentxbackend/mergegate/application/MergeGateCompletionService.java)

关键动作：

- 读取 `main` 当前 head
- rebase task branch 形成 merge candidate
- 创建 VERIFY run
- VERIFY 成功后 fast-forward `main`
- 打 `delivery/*` tag
- 任务从 `DELIVERED` 进入 `DONE`

### 10. Session 完成与 Clone 发布

关键类：

- [SessionCommandService](../../src/main/java/com/agentx/agentxbackend/session/application/SessionCommandService.java)
- [SessionCompletionReadinessService](../../src/main/java/com/agentx/agentxbackend/session/application/query/SessionCompletionReadinessService.java)
- [DeliveryCloneController](../../src/main/java/com/agentx/agentxbackend/delivery/api/DeliveryCloneController.java)
- [DeliveryClonePublishService](../../src/main/java/com/agentx/agentxbackend/delivery/application/DeliveryClonePublishService.java)
- [GitBareCloneRepositoryAdapter](../../src/main/java/com/agentx/agentxbackend/delivery/infrastructure/external/GitBareCloneRepositoryAdapter.java)

关键动作：

- 查询层判断是否满足完成条件
- session `complete`
- 将 session repo 发布成临时 bare clone
- 通过 `git://127.0.0.1:19418/...` 暴露给用户 clone

## 这条链路的三个现实特点

### 不是单线调用

全链路靠三种触发方式协作：

1. API 入口
2. Spring 事件监听器
3. 后台 scheduler 轮询

### 允许中间失败

本次样本里有 2 个失败 run，但后续流程继续推进并修复成功。
因此“是否闭环”不能只看有没有失败，而要看失败后有没有恢复和最终交付。

### 查询层是第二层真相

`progress`、`task-board`、`ticket-inbox`、`run-timeline` 都是聚合视图。
它们对排查非常好用，但不要把这些字段直接等同于单表字段。
