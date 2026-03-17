# AgentX 代码索引

这份索引按“我现在想搞清楚什么”来组织，不按包名罗列。

## 我想看 session 是怎么开始的

- API 入口:
  [SessionCommandController](../src/main/java/com/agentx/agentxbackend/session/api/SessionCommandController.java)
  - `createSession`
  - `pauseSession`
  - `resumeSession`
  - `completeSession`
- 应用服务:
  [SessionCommandService](../src/main/java/com/agentx/agentxbackend/session/application/SessionCommandService.java)
  - `createSession`
  - `completeSession`
- 自动 bootstrap:
  [SessionCreatedEventListener](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/SessionCreatedEventListener.java)
  - `onSessionCreated`
  [SessionBootstrapInitProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/SessionBootstrapInitProcessManager.java)
  - `handle`

## 我想看 requirement draft 和 confirm

- draft API:
  [RequirementAgentController](../src/main/java/com/agentx/agentxbackend/requirement/api/RequirementAgentController.java)
  - `generateDraft`
- draft 服务:
  [RequirementAgentDraftService](../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementAgentDraftService.java)
  - `generateDraft`
- doc API:
  [RequirementDocController](../src/main/java/com/agentx/agentxbackend/requirement/api/RequirementDocController.java)
  - `createRequirementDoc`
  - `createVersion`
  - `confirm`
- doc 服务:
  [RequirementDocCommandService](../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementDocCommandService.java)
  - `createRequirementDoc`
  - `createVersion`
  - `confirm`

## 我想看为什么 requirement confirm 后会自动进入规划

- 事件监听:
  [RequirementConfirmedEventListener](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RequirementConfirmedEventListener.java)
  - `onRequirementConfirmed`
- ticket 创建:
  [RequirementConfirmedProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/RequirementConfirmedProcessManager.java)
  - `handle`
- architect 自动轮询:
  [ArchitectAutoProcessScheduler](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ArchitectAutoProcessScheduler.java)
  - `poll`
- architect 处理:
  [ArchitectTicketAutoProcessorService](../src/main/java/com/agentx/agentxbackend/process/application/ArchitectTicketAutoProcessorService.java)
  - `processOpenArchitectTickets`
- 规划落库:
  [ArchitectWorkPlanningService](../src/main/java/com/agentx/agentxbackend/process/application/ArchitectWorkPlanningService.java)
  - `planAndPersist`

## 我想看任务和依赖是怎么建出来的

- API:
  [PlanningController](../src/main/java/com/agentx/agentxbackend/planning/api/PlanningController.java)
- 应用服务:
  [PlanningCommandService](../src/main/java/com/agentx/agentxbackend/planning/application/PlanningCommandService.java)
  - `createModule`
  - `createTask`
  - `addTaskDependency`
  - `claimReadyTaskForWorker`
  - `markDelivered`
  - `markDone`
  - `refreshWaitingTasks`

## 我想看 context pack 是怎么编译的

- API:
  [ContextCompileController](../src/main/java/com/agentx/agentxbackend/contextpack/api/ContextCompileController.java)
- 应用服务:
  [ContextCompileService](../src/main/java/com/agentx/agentxbackend/contextpack/application/ContextCompileService.java)
  - `compileRolePack`
  - `compileTaskContextPack`
  - `compileTaskSkill`
  - `refreshTaskContextsBySession`
  - `refreshTaskContextByTask`
- 事实读取:
  [MybatisContextFactsQueryAdapter](../src/main/java/com/agentx/agentxbackend/contextpack/infrastructure/persistence/MybatisContextFactsQueryAdapter.java)
  - `findRequirementBaselineBySessionId`
  - `findTaskPlanningByTaskId`
  - `listRecentArchitectureTickets`
  - `listRecentTaskRuns`

## 我想看 worker 为什么会被 provision / 选中

- worker 能力:
  [WorkerCapabilityService](../src/main/java/com/agentx/agentxbackend/workforce/application/WorkerCapabilityService.java)
  - `registerToolpack`
  - `registerWorker`
  - `bindToolpacks`
  - `hasEligibleWorker`
  - `isWorkerEligible`
- 默认 worker/toolpack 启动:
  [DefaultToolpackBootstrap](../src/main/java/com/agentx/agentxbackend/workforce/infrastructure/external/DefaultToolpackBootstrap.java)
  - `bootstrap`
- 自动 provision:
  [WorkerAutoProvisionService](../src/main/java/com/agentx/agentxbackend/process/application/WorkerAutoProvisionService.java)
  - `provisionForWaitingTasks`
- 自动执行:
  [WorkerRuntimeAutoRunService](../src/main/java/com/agentx/agentxbackend/process/application/WorkerRuntimeAutoRunService.java)
  - `runOnce`

## 我想看 run 是怎么 claim、heartbeat、finish 的

- API:
  [WorkerRunController](../src/main/java/com/agentx/agentxbackend/execution/api/WorkerRunController.java)
  - `claim`
  - `heartbeat`
  - `appendEvent`
  - `finish`
- 应用服务:
  [RunCommandService](../src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java)
  - `claimTask`
  - `pickupRunningVerifyRun`
  - `heartbeat`
  - `appendEvent`
  - `finishRun`
  - `createVerifyRun`
  - `failRun`
  - `recoverExpiredRuns`
- 持久化:
  [MybatisTaskRunRepository](../src/main/java/com/agentx/agentxbackend/execution/infrastructure/persistence/MybatisTaskRunRepository.java)
  - `existsActiveRunBySessionId`
  - `findExpiredActiveRuns`
  - `markFailedIfLeaseExpired`

## 我想看 run 是怎么真正操作代码仓库的

- 工作区分配:
  [WorkspaceService](../src/main/java/com/agentx/agentxbackend/workspace/application/WorkspaceService.java)
  - `allocate`
  - `release`
  - `markBroken`
  - `updateTaskBranch`
- worktree/git:
  [JGitClientAdapter](../src/main/java/com/agentx/agentxbackend/workspace/infrastructure/external/JGitClientAdapter.java)
  - `createRunBranchAndWorktree`
  - `removeWorktree`
  - `updateTaskBranch`
- 本地执行器:
  [LocalWorkerTaskExecutor](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java)
  - `execute`

## 我想看为什么 DELIVERED 会变成 DONE

- 进入 merge gate:
  [DeliveredTaskMergeGateProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/DeliveredTaskMergeGateProcessManager.java)
  - `onTaskDelivered`
- merge gate 主服务:
  [MergeGateService](../src/main/java/com/agentx/agentxbackend/mergegate/application/MergeGateService.java)
  - `start`
- git merge/rebase:
  [CliGitClientAdapter](../src/main/java/com/agentx/agentxbackend/mergegate/infrastructure/external/CliGitClientAdapter.java)
  - `readMainHead`
  - `rebaseTaskBranch`
  - `fastForwardMain`
  - `ensureDeliveryTagOnMain`
- verify 成功后完成:
  [MergeGateCompletionProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/MergeGateCompletionProcessManager.java)
  - `onVerifySucceeded`
  [MergeGateCompletionService](../src/main/java/com/agentx/agentxbackend/mergegate/application/MergeGateCompletionService.java)
  - `completeVerifySuccess`

## 我想看失败恢复和需要人工输入时发生了什么

- run 完成后的编排:
  [RunFinishedProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/RunFinishedProcessManager.java)
  - `handle`
- verify 失败恢复:
  [VerifyFailureRecoveryProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/VerifyFailureRecoveryProcessManager.java)
  - `onVerifyFailed`
- 需要人类输入:
  [RunNeedsInputProcessManager](../src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java)
  - `handle(RunNeedsDecisionEvent)`
  - `handle(RunNeedsClarificationEvent)`

## 我想看前端读到的 progress / task board / ticket inbox 从哪来

- 查询 API:
  [ProgressQueryController](../src/main/java/com/agentx/agentxbackend/query/api/ProgressQueryController.java)
  - `getSessionProgress`
  - `getTicketInbox`
  - `getTaskBoard`
  - `getRunTimeline`
- 查询服务:
  [ProgressQueryService](../src/main/java/com/agentx/agentxbackend/query/application/ProgressQueryService.java)
  - `getSessionProgress`
  - `getTicketInbox`
  - `getTaskBoard`
  - `getRunTimeline`
- complete readiness:
  [SessionCompletionReadinessService](../src/main/java/com/agentx/agentxbackend/session/application/query/SessionCompletionReadinessService.java)
  - `getCompletionReadiness`

## 我想看 runtime LLM 配置为什么和 `.env.docker` 不一样

- API:
  [RuntimeLlmConfigController](../src/main/java/com/agentx/agentxbackend/process/api/RuntimeLlmConfigController.java)
- 服务:
  [RuntimeLlmConfigService](../src/main/java/com/agentx/agentxbackend/process/application/RuntimeLlmConfigService.java)
- 持久化:
  [LocalFileRuntimeLlmConfigStore](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalFileRuntimeLlmConfigStore.java)

## 我想看 clone repo 是怎么发布的

- API:
  [DeliveryCloneController](../src/main/java/com/agentx/agentxbackend/delivery/api/DeliveryCloneController.java)
  - `publishCloneRepo`
  - `getActiveCloneRepo`
- 应用服务:
  [DeliveryClonePublishService](../src/main/java/com/agentx/agentxbackend/delivery/application/DeliveryClonePublishService.java)
  - `publish`
  - `findActive`
  - `cleanupExpiredPublications`
- 外部适配器:
  [GitBareCloneRepositoryAdapter](../src/main/java/com/agentx/agentxbackend/delivery/infrastructure/external/GitBareCloneRepositoryAdapter.java)
  - `publish`
  - `findActive`
  - `cleanupExpired`

## 我想看 scheduler 和 event listener 全貌

优先看这些类：

- [ArchitectAutoProcessScheduler](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ArchitectAutoProcessScheduler.java)
- [WorkerAutoProvisionScheduler](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerAutoProvisionScheduler.java)
- [WorkerRuntimeScheduler](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/WorkerRuntimeScheduler.java)
- [RunLeaseWatchdogScheduler](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RunLeaseWatchdogScheduler.java)
- [RuntimeGarbageCollectionScheduler](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RuntimeGarbageCollectionScheduler.java)
- [RequirementConfirmedEventListener](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RequirementConfirmedEventListener.java)
- [RunDomainEventListener](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RunDomainEventListener.java)
- [ContextRefreshEventListener](../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ContextRefreshEventListener.java)

## 配合搜索的命令

直接看：

- [reference/common-commands.md](reference/common-commands.md)
- [modules/README.md](modules/README.md)
