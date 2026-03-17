# mergegate 模块

## 职责

`mergegate` 管从 `DELIVERED` 到 `DONE` 的那段关键路径：

- 基于 `main` 做 rebase
- 形成 merge candidate
- 创建 VERIFY run
- VERIFY 成功后 fast-forward `main`
- 打 delivery tag

这是定义“交付是否真的通过验证”的地方。

## 入站入口

- API:
  [MergeGateController](../../src/main/java/com/agentx/agentxbackend/mergegate/api/MergeGateController.java)
  - `start`

更常见的真实入口是：

- [DeliveredTaskMergeGateProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/DeliveredTaskMergeGateProcessManager.java)
  - `onTaskDelivered`

## 主要表

`mergegate` 本身没有独占表。
它主要依赖：

- `task_runs`
- `task_run_events`
- `work_tasks`

以及 git 仓库中的 `main`、`task/*`、`run/*`、`delivery/*` refs。

## 关键代码入口

- 主服务:
  [MergeGateService](../../src/main/java/com/agentx/agentxbackend/mergegate/application/MergeGateService.java)
  - `start`
- git 操作:
  [CliGitClientAdapter](../../src/main/java/com/agentx/agentxbackend/mergegate/infrastructure/external/CliGitClientAdapter.java)
  - `readMainHead`
  - `rebaseTaskBranch`
  - `fastForwardMain`
  - `ensureDeliveryTagOnMain`
  - `recoverRepositoryIfNeeded`
- VERIFY 成功收口:
  [MergeGateCompletionService](../../src/main/java/com/agentx/agentxbackend/mergegate/application/MergeGateCompletionService.java)
  - `completeVerifySuccess`

## 在全链路里的位置

它卡在 IMPL 和真正 DONE 之间。

主流程：

1. IMPL run 交付 delivery commit
2. merge gate 基于 `main` 生成 merge candidate
3. 创建 VERIFY run
4. VERIFY 成功
5. 更新 `main`
6. 打 delivery tag
7. task 标记为 `DONE`

## 想查什么就看哪里

- 为什么 task 一直停在 `DELIVERED`
  - 看 [DeliveredTaskMergeGateProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/DeliveredTaskMergeGateProcessManager.java)
  - 再看 `run-timeline`
- 为什么 VERIFY run 被创建
  - 看 `MergeGateService.start`
  - 再看 `RunCommandService.createVerifyRun`
- 为什么 main 上已经有 commit 但 task 还没 DONE
  - 看 `MergeGateCompletionService.completeVerifySuccess`

## 调试入口

- API: `POST /api/v0/foreman/tasks/{taskId}/merge-gate/start`
- git: `git -C runtime-projects/default-repo/sessions/<session-id-lowercase>/repo log --graph --decorate --oneline --all`
- SQL: `select * from task_runs where task_id = '<TASK_ID>' order by started_at desc;`

## 工程优化思路

### 近期整理

- 把 merge candidate、base commit、delivery commit 的命名和日志统一。
- 明确 merge gate 各种失败原因的错误码和恢复动作。

### 可维护性与可观测性

- 为 rebase 冲突、verify 失败、fast-forward 失败分别补诊断日志。
- 在 query 层暴露更直观的 merge gate 状态。

### 中长期演进

- 将 merge gate 演进为显式状态机，而不是主要依赖过程式串联。
- 为不同任务类型引入差异化 verify 策略，而不是默认同一套命令路径。
