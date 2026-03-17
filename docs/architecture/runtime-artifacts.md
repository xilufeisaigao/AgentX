# AgentX 运行时产物

这一页专门讲“东西落在哪”，方便你排查时不在代码和容器之间迷路。

## 运行时挂载

backend 容器当前有两块关键挂载：

1. host bind mount
   - `<repo-root>/runtime-projects/default-repo`
   - 容器内路径: `/agentx/repo`
2. Docker volume
   - `agentx_runtime_data`
   - 容器内路径: `/agentx/runtime-data`

结论：

- git 相关产物主要看 `/agentx/repo`
- context、runtime env、toolpack 等运行时文件主要看 `/agentx/runtime-data`

## backend 启动时会做什么

入口脚本：

- [docker/entrypoint.sh](../../docker/entrypoint.sh)

它会：

1. 确保 `/agentx/repo` 存在
2. 如果没有 `.git`，初始化仓库
3. 配置 git user
4. 写 baseline `README.md`
5. 生成第一次提交 `init workspace baseline`
6. 导出 `AGENTX_EXECUTION_DEFAULT_BASE_COMMIT`

这意味着运行时 repo 的 baseline 不是手工准备的，而是容器自己兜底初始化的。

## Session repo 布局

实际样本：

- `runtime-projects/default-repo/sessions/<session-id-lowercase>/repo`

里面能看到：

- `.git/`
- `src/`
- `pom.xml`
- `README.md`
- `worktrees/`

## Git refs 里会保留什么

从 session repo 的 git log 可以看到：

- `main`
- `task/TASK-*`
- `run/RUN-*`
- `delivery/<timestamp>`
- `origin/main`

如何理解：

- `task/*` 表示任务分支视角
- `run/*` 表示某次具体执行
- `delivery/*` tag 表示可交付点
- `origin/main` 是工作区 baseline 参考

所以要查“某次改动是哪个 run 造成的”，先去看 `run/*` 分支。

## Worktree 生命周期

worktree 是执行期产物，不保证永久保留。

当前样本里：

- `repo/worktrees/<session-id>/...`
  曾经存在 run worktree
- session 完成后，大部分 worktree 已被清理

这符合当前设计取向：

- 长久证据保留在 git refs 和数据库事件中
- 临时工作目录会被 runtime cleanup 回收

## Context 产物

容器内已确认的真实目录：

- `/agentx/runtime-data/context/context/task-context-packs/TASK-...`
- `/agentx/runtime-data/context/context/task-skills/TASK-...`

典型文件：

- `CTXS-*.json`
- `CTXS-*.md`

如何理解：

- `task_context_snapshots` 是数据库里的索引和状态面
- `task-context-packs` / `task-skills` 是文件系统里的实际编译结果

排查某个 task 为什么拿到某段上下文时，数据库和文件要一起看。

## Runtime env 与 toolpack 产物

容器内可以看到：

- `/agentx/runtime-data/runtime-env/global-toolpacks/...`
- `/agentx/runtime-data/runtime-env/projects/<session-id>/<fingerprint>/environment.json`

这些产物对应：

- worker 需要哪些 runtime 能力
- 某个 session/worker 被分配到了什么环境资源
- 数据库账号或其他运行时注入信息

## Delivery clone 发布路径

关键实现：

- [GitBareCloneRepositoryAdapter](../../src/main/java/com/agentx/agentxbackend/delivery/infrastructure/external/GitBareCloneRepositoryAdapter.java)

实际行为：

- 将 session repo 发布到 `runtime-projects/default-repo/remotes`
- 元数据写入 `.metadata`
- 对外通过 `git://127.0.0.1:19418/...` 暴露

这意味着：

- 用户拿到的 clone 仓库不是当前工作目录的直接共享，而是发布后的 bare repo。
- clone publish 有独立保留期和清理机制。

## 数据库侧关键表

运行时产物并不只在文件系统。
排查时最常用的表有：

1. `sessions`
2. `requirement_docs`
3. `requirement_doc_versions`
4. `tickets`
5. `ticket_events`
6. `work_modules`
7. `work_tasks`
8. `work_task_dependencies`
9. `task_context_snapshots`
10. `task_runs`
11. `task_run_events`
12. `git_workspaces`

要点：

- 状态变化主要在这些表里。
- 最终用户看到的“进度页”是这些表再加上 readiness 规则拼出来的。

## 最常见的排查顺序

### 问题 1: 为什么任务没跑起来

先看：

1. `task-board`
2. `workers`
3. `task_context_snapshots`
4. `task_runs`

### 问题 2: 为什么 run 失败了

先看：

1. `run-timeline`
2. `task_run_events.data_json`
3. 对应 `run/*` 分支
4. 如果 worktree 还在，再看 worktree 内容

### 问题 3: 为什么 session 还不能 complete

先看：

1. `progress`
2. `SessionCompletionReadinessService`
3. `deliveryTagPresent`
4. 是否仍有未完成 task / active run / waiting user ticket

## 配合使用的命令

常用命令放在：

- [../reference/common-commands.md](../reference/common-commands.md)
