# workspace 模块

## 职责

`workspace` 管 git worktree 和运行工作区：

- 分配 worktree
- 释放 worktree
- 标记 broken workspace
- 更新 task branch 指向

它是 execution 和 git 仓库之间的桥。

## 入站入口

没有单独对用户暴露的控制面 API。
它的真实调用入口主要来自：

- [RunCommandService](../../src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java)
- [LocalWorkerTaskExecutor](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java)

## 主要表

- `git_workspaces`

## 关键代码入口

- 应用服务:
  [WorkspaceService](../../src/main/java/com/agentx/agentxbackend/workspace/application/WorkspaceService.java)
  - `allocate`
  - `release`
  - `markBroken`
  - `updateTaskBranch`
- git worktree 操作:
  [JGitClientAdapter](../../src/main/java/com/agentx/agentxbackend/workspace/infrastructure/external/JGitClientAdapter.java)
  - `createRunBranchAndWorktree`
  - `removeWorktree`
  - `updateTaskBranch`

## 在全链路里的位置

没有 workspace，就没有隔离执行。

一个 run 真正开始前通常会发生：

1. 读取 session repo
2. 创建 `run/*` 分支
3. 创建 worktree 目录
4. 把 worktree 路径写入 `task_runs.worktree_path`

## 想查什么就看哪里

- 为什么这个 run 的 worktree 路径是这个
  - 看 `WorkspaceService.allocate`
- 为什么 session 完成后 worktree 不见了
  - 看 runtime cleanup 和 release 流程
- 为什么 task branch 指到某个 commit
  - 看 `updateTaskBranch`

## 调试入口

- SQL: `select * from git_workspaces where run_id = '<RUN_ID>';`
- 文件系统: `runtime-projects/default-repo/sessions/<session-id-lowercase>/repo/worktrees`
- git: `git -C runtime-projects/default-repo/sessions/<session-id-lowercase>/repo log --all --decorate --oneline`

## 工程优化思路

### 近期整理

- 把 worktree 路径生成规则、清理规则和 broken 规则写得更显式。
- 给 broken workspace 增加恢复/清理指引。

### 可维护性与可观测性

- 为 allocate/release/markBroken 补幂等测试和冲突测试。
- 把 workspace 事件汇总到统一日志中，减少只能靠 git/文件系统猜测。

### 中长期演进

- 将 workspace 生命周期做成独立的可观测子系统，支持保留策略和回放。
- 把 worktree 清理与 delivery 保留策略统一治理，而不是分散在不同调度器里。
