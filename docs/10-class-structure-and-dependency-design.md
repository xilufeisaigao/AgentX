# 模块 10：类结构与依赖设计（MVP）

更新时间：2026-02-23

范围说明：
1. 本文只定义 **MVP 需要的类结构**，不追求“把所有可能类先建出来”。
2. 本文与 `docs/schema/agentx_schema_v0.sql`、`docs/09-control-plane-api-contract.md` 对齐。
3. 本文回答三个问题：
   - 类大致怎么分层、怎么分模块
   - 模块之间如何依赖，哪些依赖禁止
   - 每个实体类对应哪张表

---

## 1. 总体构想（先说结论）

采用 **模块化单体 + DDD 分包**：
1. 按业务边界拆模块：`session/requirement/ticket/planning/execution/...`，避免“全局 service 大杂烩”。
2. 每个模块内部统一四层：`api -> application -> domain <- infrastructure`。
3. 跨模块不直接拿对方 repository 或 entity，统一通过 `application.port.in`（用例接口）或领域事件协作。

这样做的核心目标不是“看起来像 DDD”，而是：
1. 状态机归属清晰（谁能改哪张表一眼看清）。
2. 并发与门禁逻辑不会散落在多个 service 里。
3. 后续拆分为微服务时，不需要推倒重来。

---

## 2. 分层规则（硬约束）

### 2.1 模块内依赖方向

1. `api` 只依赖本模块 `application.port.in` 与请求响应 DTO。
2. `application` 依赖本模块 `domain`，并通过 `port.out` 访问外部资源。
3. `domain` 只放业务规则与状态迁移，不依赖 MyBatis/Spring/Web。
4. `infrastructure` 实现 `application.port.out`，负责 MyBatis、Git、文件系统、外部客户端。

### 2.2 跨模块依赖规则

1. 允许：`A.application -> B.application.port.in`（调用对方用例接口）。
2. 允许：`A.domain.event -> process`（发布事件给流程编排）。
3. 禁止：`A` 直接访问 `B.infrastructure`。
4. 禁止：`A` 直接操作 `B` 的表对应 mapper。
5. 禁止：全局 `BaseService`、`万能Manager`、`God Orchestrator`。

### 2.3 不建无意义类（明确约束）

1. 不为每张表强行建 `xxxDomainService`；只有存在跨实体规则才建。
2. 不做“DTO 与 Entity 一模一样”的双份镜像类（除非是 API 输入输出边界）。
3. 不提前建“抽象工厂/策略”占位类；先按 MVP 流程需要落地最小集。

---

## 3. 模块级类结构（MVP 最小集）

说明：
1. 下列类名是建议命名，不要求逐字一致。
2. 每个模块只列 **当前流程闭环需要** 的类。

### 3.1 `session`（会话生命周期）

`api`
1. `SessionCommandController`（create/pause/resume/complete）

`application`
1. `SessionCommandService`
2. `port.in.SessionCommandUseCase`
3. `port.out.SessionRepository`
4. `port.out.DeliveryProofPort`（校验是否可 complete）

`domain`
1. `Session`
2. `SessionStatus`

`infrastructure`
1. `persistence.SessionMapper`
2. `persistence.MybatisSessionRepository`
3. `external.GitDeliveryProofAdapter`

### 3.2 `requirement`（需求文档与确认）

`api`
1. `RequirementDocController`
2. `RequirementAgentController`

`application`
1. `RequirementDocCommandService`
2. `RequirementAgentDraftService`
3. `port.in.RequirementDocUseCase`
4. `port.in.RequirementAgentUseCase`
5. `port.out.RequirementDocRepository`
6. `port.out.RequirementDocVersionRepository`
7. `port.out.DomainEventPublisher`
8. `port.out.RequirementDraftGeneratorPort`

`domain`
1. `RequirementDoc`
2. `RequirementDocVersion`
3. `RequirementDocStatus`
4. `event.RequirementConfirmedEvent`
5. `policy.RequirementDocContentPolicy`

`infrastructure`
1. `persistence.RequirementDocMapper`
2. `persistence.RequirementDocVersionMapper`
3. `persistence.MybatisRequirementDocRepository`
4. `persistence.MybatisRequirementDocVersionRepository`
5. `external.BailianRequirementDraftGenerator`

### 3.3 `ticket`（工单与用户决策面）

`api`
1. `TicketController`

`application`
1. `TicketCommandService`
2. `TicketQueryService`
3. `port.in.TicketCommandUseCase`
4. `port.in.TicketQueryUseCase`
5. `port.out.TicketRepository`
6. `port.out.TicketEventRepository`

`domain`
1. `Ticket`
2. `TicketEvent`
3. `TicketType`
4. `TicketStatus`
5. `TicketEventType`

`infrastructure`
1. `persistence.TicketMapper`
2. `persistence.TicketEventMapper`
3. `persistence.MybatisTicketRepository`
4. `persistence.MybatisTicketEventRepository`

### 3.4 `workforce`（Worker 与 Toolpack 能力边界）

`application`
1. `WorkerCapabilityService`
2. `port.in.WorkerCapabilityUseCase`
3. `port.out.WorkerRepository`
4. `port.out.ToolpackRepository`
5. `port.out.WorkerToolpackRepository`

`domain`
1. `Worker`
2. `WorkerStatus`
3. `Toolpack`
4. `WorkerToolpackBinding`

`infrastructure`
1. `persistence.WorkerMapper`
2. `persistence.ToolpackMapper`
3. `persistence.WorkerToolpackMapper`

### 3.5 `planning`（模块与任务计划态）

`api`
1. `PlanningController`（create module/create task）

`application`
1. `PlanningCommandService`
2. `port.in.PlanningCommandUseCase`
3. `port.in.TaskStateMutationUseCase`（供 execution/mergegate 调用）
4. `port.out.WorkModuleRepository`
5. `port.out.WorkTaskRepository`
6. `port.out.WorkerEligibilityPort`（查询是否存在可接单 worker）

`domain`
1. `WorkModule`
2. `WorkTask`
3. `TaskStatus`
4. `TaskTemplateId`

`infrastructure`
1. `persistence.WorkModuleMapper`
2. `persistence.WorkTaskMapper`
3. `persistence.MybatisWorkModuleRepository`
4. `persistence.MybatisWorkTaskRepository`

### 3.6 `execution`（run 生命周期、心跳、事件、finish）

`api`
1. `WorkerRunController`（worker claim/heartbeat/events/finish）

`application`
1. `RunCommandService`
2. `port.in.RunCommandUseCase`
3. `port.in.RunInternalUseCase`（供 mergegate/process 调用）
4. `port.out.TaskRunRepository`
5. `port.out.TaskRunEventRepository`
6. `port.out.ContextSnapshotReadPort`（校验 latest READY 快照并返回 snapshot_id）
7. `port.out.TaskAllocationPort`（向 planning 申请/回收任务占用）
8. `port.out.WorkspacePort`（向 workspace 申请/释放工作区）
9. `port.out.DomainEventPublisher`

`domain`
1. `TaskRun`
2. `TaskRunEvent`
3. `RunStatus`
4. `RunKind`
5. `ContextSnapshotId`（值对象）
6. `RunEventType`
7. `Lease`
8. `RunFinishedPayload`（对应 RUN_FINISHED.data_json）
9. `event.RunNeedsDecisionEvent`
10. `event.RunNeedsClarificationEvent`
11. `event.RunFinishedEvent`

`infrastructure`
1. `persistence.TaskRunMapper`
2. `persistence.TaskRunEventMapper`
3. `persistence.MybatisTaskRunRepository`
4. `persistence.MybatisTaskRunEventRepository`

### 3.7 `workspace`（git worktree 分配与回收）

`application`
1. `WorkspaceService`
2. `port.in.WorkspaceUseCase`
3. `port.out.GitWorkspaceRepository`
4. `port.out.GitClientPort`

`domain`
1. `GitWorkspace`
2. `GitWorkspaceStatus`

`infrastructure`
1. `persistence.GitWorkspaceMapper`
2. `persistence.MybatisGitWorkspaceRepository`
3. `external.JGitClientAdapter`（或 CLI Git adapter）

### 3.8 `mergegate`（DELIVERED -> DONE 门禁）

`api`
1. `MergeGateController`（start）
2. `MergeGateExceptionHandler`

`application`
1. `MergeGateService`
2. `port.in.MergeGateUseCase`
3. `MergeGateCompletionService`（VERIFY 成功后 fast-forward main + DONE）
4. `port.in.MergeGateCompletionUseCase`
5. `port.out.TaskStateMutationPort`（planning）
6. `port.out.RunCreationPort`（execution，创建 VERIFY run）
7. `port.out.GitClientPort`
8. `port.out.IntegrationLaneLockPort`（串行门禁锁）

`domain`
1. `MergeCandidate`（值对象）
2. `MergeGateResult`（值对象）

`infrastructure`
1. `external.ExecutionRunCreationAdapter`（调用 `execution.port.in.RunInternalUseCase#createVerifyRun`）
2. `external.PlanningTaskStateMutationAdapter`
3. `external.InMemoryIntegrationLaneLockAdapter`
4. `external.RedisIntegrationLaneLockAdapter`
5. `external.CliGitClientAdapter`

### 3.9 `contextpack`（上下文与 task skill 编译）

`api`
1. `ContextCompileController`

`application`
1. `ContextCompileService`
2. `port.in.ContextCompileUseCase`
3. `port.out.ContextFactsQueryPort`
4. `port.out.TaskContextSnapshotRepository`
5. `port.out.ArtifactStorePort`

`domain`
1. `RoleContextPack`
2. `TaskContextPack`
3. `TaskSkill`
4. `TaskContextSnapshot`
5. `TaskContextSnapshotStatus`
6. `SourceFingerprint`（值对象）

`infrastructure`
1. `external.FileArtifactStoreAdapter`（写 `.agentx/context/...`）
2. `persistence.TaskContextSnapshotMapper`
3. `persistence.MybatisTaskContextSnapshotRepository`

### 3.10 `delivery`（交付 tag 与可完成性校验）

`application`
1. `DeliveryService`
2. `port.in.DeliveryProofUseCase`
3. `port.out.GitTagPort`

`domain`
1. `DeliveryTag`（值对象）

### 3.11 `process`（跨模块流程编排）

`api`
1. `ArchitectAutomationController`（架构工单自动处理与任务规划触发）
2. `WorkforceAutomationController`（auto-provision、lease-recovery、runtime auto-run、worker-pool cleanup 运维触发）

`application`
1. `RequirementConfirmedProcessManager`（创建 ARCH_REVIEW ticket）
2. `RunNeedsInputProcessManager`（NEED_* -> DECISION/CLARIFICATION ticket）
3. `RunFinishedProcessManager`（IMPL SUCCEEDED -> task DELIVERED）
4. `MergeGateCompletionProcessManager`（VERIFY SUCCEEDED -> DONE）
5. `ContextRefreshProcessManager`（触发快照 `READY -> STALE -> 重新编译`）
6. `WorkerAutoProvisionService`（`WAITING_WORKER` 自动补员与缺失 toolpack 提请）
7. `WorkerRuntimeAutoRunService`（READY Worker 自动 claim + 执行 + 回写 run）
8. `port.out.WorkerTaskExecutorPort`（Worker Runtime 执行器）
9. `port.out.RuntimeEnvironmentPort`（创建前准备运行环境）
10. `port.out.RuntimeEnvironmentMaintenancePort`（运行环境清理维护）

`infrastructure`
1. `external.LocalRuntimeEnvironmentAdapter`（`local | docker` 双模式运行环境准备与维护，默认 docker）
2. `external.WorkerAutoProvisionScheduler`
3. `external.RunLeaseWatchdogScheduler`
4. `external.RuntimeEnvironmentCleanupScheduler`
5. `external.RunDomainEventListener`（消费 `RunNeeds* / RunFinished` 并驱动 ticket + task 状态迁移）
6. `external.LocalWorkerTaskExecutor`
7. `external.WorkerRuntimeScheduler`

依赖方式：
1. 只依赖各模块 `application.port.in` 与领域事件。
2. 不直接依赖任何 `infrastructure`。

### 3.12 `query`（读模型）

`api`
1. `ProgressQueryController`

`application`
1. `ProgressQueryService`
2. `port.in.ProgressQueryUseCase`
3. `port.out.ProgressReadRepository`

`domain`
1. `SessionProgressView`
2. `TicketInboxView`
3. `TaskBoardView`
4. `RunTimelineView`

`infrastructure`
1. `persistence.ProgressQueryMapper`（聚合 SQL）

---

## 4. 模块之间怎么依赖（关键链路）

### 4.1 依赖主链（MVP）

1. `requirement` 发布 `RequirementConfirmedEvent` -> `process` 调用 `ticket` 创建 `ARCH_REVIEW`。
2. `execution` 处理 `NEED_*` 事件并发布领域事件 -> `process` 调用 `ticket` 创建 `DECISION/CLARIFICATION`。
3. `execution` 处理 `finish`，写 `RUN_FINISHED` 事件 -> `process` 调用 `planning` 推进 `ASSIGNED -> DELIVERED`（IMPL 成功时）。
4. `mergegate` 调用 `execution` 创建 VERIFY run；VERIFY 成功后由 `mergegate/process` 调 `planning` 推进 `DELIVERED -> DONE`。
5. `session.complete` 调 `delivery` 校验主线 `delivery/<timestamp>` tag 再进入 `COMPLETED`。
6. `process` 周期性触发 `WorkerRuntimeAutoRunService`，让 READY worker 自动执行 claim/run/finish 主链路。

### 4.2 允许依赖矩阵（简化版）

1. `session` -> `delivery.port.in`
2. `planning` -> `workforce.port.in`
3. `execution` -> `planning.port.in` + `workspace.port.in`
4. `mergegate` -> `execution.port.in` + `planning.port.in` + `workspace.port.in`
5. `process` -> `requirement/ticket/planning/execution/mergegate` 的 `port.in`
6. `contextpack` -> `query.port.out`（只读事实）
7. `execution` -> `contextpack.port.in`（run 创建/恢复前读取 latest READY 快照）

---

## 5. 实体类与表映射（逐一对应）

| 模块 | 实体类 | 对应表 |
| --- | --- | --- |
| session | `Session` | `sessions` |
| requirement | `RequirementDoc` | `requirement_docs` |
| requirement | `RequirementDocVersion` | `requirement_doc_versions` |
| ticket | `Ticket` | `tickets` |
| ticket | `TicketEvent` | `ticket_events` |
| workforce | `Toolpack` | `toolpacks` |
| workforce | `Worker` | `workers` |
| workforce | `WorkerToolpackBinding` | `worker_toolpacks` |
| planning | `WorkModule` | `work_modules` |
| planning | `WorkTask` | `work_tasks` |
| contextpack | `TaskContextSnapshot` | `task_context_snapshots` |
| execution | `TaskRun` | `task_runs` |
| execution | `TaskRunEvent` | `task_run_events` |
| workspace | `GitWorkspace` | `git_workspaces` |

说明：
1. `mergegate`、`delivery` 以值对象和流程对象为主，v0 不新增专用表。
2. `contextpack` 新增 `task_context_snapshots`，用于上下文编译进度、新鲜度与保留策略治理。
3. `RUN_FINISHED` 的 `work_report/delivery_commit/artifact_refs_json` 通过 `task_run_events.data_json` 落库（见模块 07/09 与 schema 注释）。

---

## 6. 建议先落地的第一批类（避免一次性铺太开）

第一批（打通主链）：
1. `session/requirement/ticket/planning/execution/workspace/mergegate` 的 `api + application + domain + persistence` 最小类。
2. `process` 先实现两条：`RequirementConfirmedProcessManager`、`RunNeedsInputProcessManager`。
3. `query` 先做 `TicketInboxView` 与 `TaskBoardView`。

第二批（增强可观测）：
1. `contextpack` 编译链（先 file adapter）。
2. `delivery` 与 `session.complete` 的 tag 校验联动。
3. `mergegate` 串行门禁锁实现。

以上顺序保证：先闭环、再优化，不会产生大量“现在用不到的空类”。
