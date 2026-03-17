# AgentX 文档索引

这套文档以 2026-03-17 的真实运行状态为基准重写。
目标不是重复旧设计稿，而是帮助你用最短路径回答三个问题：

1. 这个项目现在到底是怎么跑起来的。
2. 最小闭环是否真的已经在 Docker 里跑通。
3. 我想追某个行为、某个接口、某个表、某个方法时，应该先看哪里。

旧版设计文档已经归档到 [archive/legacy-design-20260317](archive/legacy-design-20260317/)。
它们仍有历史价值，但不再作为当前实现的首读材料。

## 先读什么

1. [learning-path.md](learning-path.md)
   适合重新接管项目时的阅读顺序和每天推进节奏。
2. [current-state/runtime-audit-2026-03-17.md](current-state/runtime-audit-2026-03-17.md)
   真实 Docker 运行面、真实 session、真实 git 产物、真实失败恢复证据。
3. [architecture/end-to-end-chain.md](architecture/end-to-end-chain.md)
   从 `POST /api/v0/sessions` 到 `delivery/clone-repo` 的整条链路。
4. [architecture/runtime-artifacts.md](architecture/runtime-artifacts.md)
   代码、worktree、context pack、bare clone、Docker volume 都落在哪。
5. [code-index.md](code-index.md)
   按问题找类、方法、接口和排查入口。

## 真相源

优先级按这个顺序看：

1. 表结构真相: [schema/agentx_schema_v0.sql](schema/agentx_schema_v0.sql)
2. API 形状真相: [openapi/agentx-control-plane.v0.yaml](openapi/agentx-control-plane.v0.yaml)
3. 运行与流程真相: [architecture/end-to-end-chain.md](architecture/end-to-end-chain.md)
4. 当前闭环证据: [current-state/runtime-audit-2026-03-17.md](current-state/runtime-audit-2026-03-17.md)
5. 操作命令与排查动作: [reference/common-commands.md](reference/common-commands.md)

注意：

- `query` 返回的是聚合视图，不等于某一张表。
- `.env.docker` 只是默认值，运行时 LLM 配置可以被控制面覆盖。
- session 完成后的 git 证据主要保留在 session repo 的分支、tag 和 delivery clone 中，worktree 可能已被清理。

## 文档分层

- [current-state](current-state/)
  当前真实状态和已验证证据。
- [architecture](architecture/)
  讲全链路、运行时文件落点、调度器与事件如何协作。
- [modules](modules/)
  每个模块单独拆开写：职责、入口、表、代码位置、调试路径、工程优化方向。
- [reference](reference/)
  真相源说明、常用命令、日常排查动作。
- [deployment](deployment/)
  Docker 启动、停止、重置、运行环境说明。

## 模块入口

按源码真实模块拆分：

1. [modules/session.md](modules/session.md)
2. [modules/requirement.md](modules/requirement.md)
3. [modules/ticket.md](modules/ticket.md)
4. [modules/planning.md](modules/planning.md)
5. [modules/workforce.md](modules/workforce.md)
6. [modules/execution.md](modules/execution.md)
7. [modules/workspace.md](modules/workspace.md)
8. [modules/mergegate.md](modules/mergegate.md)
9. [modules/contextpack.md](modules/contextpack.md)
10. [modules/delivery.md](modules/delivery.md)
11. [modules/process.md](modules/process.md)
12. [modules/query.md](modules/query.md)

## Docker 入口

日常操作直接看：

- [deployment/docker-runtime.md](deployment/docker-runtime.md)
- [reference/common-commands.md](reference/common-commands.md)

如果你只想先验证“现在还能不能闭环”，直接看：

- [current-state/runtime-audit-2026-03-17.md](current-state/runtime-audit-2026-03-17.md)
