# execution 模块

## 职责

`execution` 管 run 生命周期：

- claim task
- 创建 run
- heartbeat
- 追加 run event
- finish / fail run
- 创建 verify run
- 恢复过期 lease

这是“任务真正跑起来”的核心状态机。

## 入站入口

- API:
  [WorkerRunController](../../src/main/java/com/agentx/agentxbackend/execution/api/WorkerRunController.java)
  - `claim`
  - `heartbeat`
  - `appendEvent`
  - `finish`

自动驱动入口：

- [WorkerRuntimeAutoRunService](../../src/main/java/com/agentx/agentxbackend/process/application/WorkerRuntimeAutoRunService.java)
  - `runOnce`
- [RunLeaseWatchdogScheduler](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RunLeaseWatchdogScheduler.java)

## 主要表

- `task_runs`
- `task_run_events`

## 关键代码入口

- 核心服务:
  [RunCommandService](../../src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java)
  - `claimTask`
  - `pickupRunningVerifyRun`
  - `heartbeat`
  - `appendEvent`
  - `finishRun`
  - `createVerifyRun`
  - `failRun`
  - `failWaitingRunForUserResponse`
  - `recoverExpiredRuns`
- 持久化:
  [MybatisTaskRunRepository](../../src/main/java/com/agentx/agentxbackend/execution/infrastructure/persistence/MybatisTaskRunRepository.java)
  - `existsActiveRunBySessionId`
  - `findExpiredActiveRuns`
  - `markFailedIfLeaseExpired`

## 在全链路里的位置

execution 连接了 planning、workspace、worker executor 和 process：

1. 从 planning 里挑 ready task
2. 创建 run 并绑定 context snapshot
3. 交给 workspace 分配 worktree
4. 交给 executor 真实执行
5. 通过 run event 把结果再回送给 process 做编排

当前实现里有两个容易查错的细节：

1. `RunCommandService` 会先按 `worktrees/<SESSION_ID>/<RUN_ID>` 生成候选路径，但真正持久化到 `task_runs.worktree_path` 的值以后端 `WorkspaceService.allocate` 返回的实际 worktree 路径为准，回写入口在 `claimTask` / `createVerifyRun` 里的 `syncAllocatedWorktreePath`。
2. VERIFY 命令有明确优先级：
   `resolveVerifyCommands` 先读 task skill 推荐命令；
   如果没有，再根据真实 worktree 内容做仓库探测；
   还没有命中时，才退回 toolpack 默认命令。

## 想查什么就看哪里

- 为什么某个 task 没有 run
  - 看 `claimTask`
  - 再看 planning readiness 和 worker eligibility
- 为什么某个 run 被标记为失败
  - 看 `task_run_events`
  - 看 `failRun` / `finishRun`
- 为什么 verify run 会再次出现
  - 看 `createVerifyRun`
  - 再看 mergegate 触发链
- 为什么 `task_runs.worktree_path` 不是一开始拼出来的那个路径
  - 看 `claimTask` / `createVerifyRun`
  - 再看 `syncAllocatedWorktreePath`
  - 最后看 `WorkspaceService.allocate`
- 为什么 VERIFY 最后跑的是 `mvn` / `gradle` / `pytest`
  - 看 `pickupRunningVerifyRun`
  - 看 `resolveVerifyCommands`
  - 看 `detectRepositoryVerifyCommands`
  - 最后看 `fallbackVerifyCommands`

## 调试入口

- API: `GET /api/v0/sessions/{sessionId}/run-timeline`
- API: `POST /api/v0/execution/lease-recovery`
- SQL: `select * from task_runs where run_id = '<RUN_ID>';`
- SQL: `select * from task_run_events where run_id = '<RUN_ID>' order by created_at;`

## 工程优化思路

### 近期整理

- 统一 `task_run_events.data_json` 的结构，减少不同失败来源的字段漂移。
- 区分“执行失败”“验证失败”“等待人工输入失败”。

### 可维护性与可观测性

- 补 run 生命周期状态图与覆盖测试。
- 为每次 run 打通 `session_id -> task_id -> run_id -> worker_id` 的统一日志链路。

### 中长期演进

- 把 IMPL / VERIFY / WAITING_USER 等 run 子类型从流程分支进一步演进为更明确的策略对象。
- 让 run 结果具备更稳定的工件输出协议，而不只是自由文本 `work_report`。
