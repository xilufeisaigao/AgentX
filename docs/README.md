# AgentX 文档索引

这套文档以 2026-03-17 的真实运行状态为基准重写。
目标不是重复旧设计稿，而是帮助你用最短路径回答三个问题：

1. 这个项目现在到底是怎么跑起来的。
2. 最小闭环是否真的已经在 Docker 里跑通。
3. 我想追某个行为、某个接口、某个表、某个方法时，应该先看哪里。

旧版设计文档已经归档到 [archive/legacy-design-20260317](archive/legacy-design-20260317/)。
它们仍有历史价值，但不再作为当前实现的首读材料。

## 先读什么

1. [00-learning-progress.md](00-learning-progress.md)
   记录当前学到哪里，给新窗口的 agent 用来承接“继续学习”。
2. [01-learning-path.md](01-learning-path.md)
   适合重新接管项目时的阅读顺序和每天推进节奏。
3. [current-state/02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
   真实 Docker 运行面、真实 session、真实 git 产物、真实失败恢复证据。
4. [architecture/03-end-to-end-chain.md](architecture/03-end-to-end-chain.md)
   从 `POST /api/v0/sessions` 到 `delivery/clone-repo` 的整条链路。
5. [architecture/04-runtime-artifacts.md](architecture/04-runtime-artifacts.md)
   代码、worktree、context pack、bare clone、Docker volume 都落在哪。
6. [05-code-index.md](05-code-index.md)
   按问题找类、方法、接口和排查入口。
7. [modules/06-module-map.md](modules/06-module-map.md)
   进入模块化学习前，先看一遍模块顺序和阅读建议。
8. [19-study-session-log.md](19-study-session-log.md)
   每天说“结束学习”后，由 agent 追加当天复盘。
9. [20-concept-and-interview-bank.md](20-concept-and-interview-bank.md)
   沉淀高频概念、反复提问和复习用面试题。

## 真相源

优先级按这个顺序看：

1. 表结构真相: [schema/agentx_schema_v0.sql](schema/agentx_schema_v0.sql)
2. API 形状真相: [openapi/agentx-control-plane.v0.yaml](openapi/agentx-control-plane.v0.yaml)
3. 运行与流程真相: [architecture/03-end-to-end-chain.md](architecture/03-end-to-end-chain.md)
4. 当前闭环证据: [current-state/02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
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

1. [modules/07-process.md](modules/07-process.md)
2. [modules/08-query.md](modules/08-query.md)
3. [modules/09-session.md](modules/09-session.md)
4. [modules/10-requirement.md](modules/10-requirement.md)
5. [modules/11-planning.md](modules/11-planning.md)
6. [modules/12-contextpack.md](modules/12-contextpack.md)
7. [modules/13-workforce.md](modules/13-workforce.md)
8. [modules/14-execution.md](modules/14-execution.md)
9. [modules/15-workspace.md](modules/15-workspace.md)
10. [modules/16-mergegate.md](modules/16-mergegate.md)
11. [modules/17-delivery.md](modules/17-delivery.md)
12. [modules/18-ticket.md](modules/18-ticket.md)

## Docker 入口

日常操作直接看：

- [deployment/docker-runtime.md](deployment/docker-runtime.md)
- [reference/common-commands.md](reference/common-commands.md)

如果你只想先验证“现在还能不能闭环”，直接看：

- [current-state/02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)

## AI 学习辅助

如果你想让一个全新的 agent 按统一方式讲解某个模块，可以直接让它使用项目专属 skill `agentx-module-teacher`。

仓库内技能源码位置：

- [.codex/skills/agentx-module-teacher/SKILL.md](../.codex/skills/agentx-module-teacher/SKILL.md)

这个 skill 会要求 agent：

1. 先给今日学习目标和今日流程图。
2. 每次只推进一个很小的学习轮次，通常只讲 1 到 3 个函数。
3. 默认用英文主讲，较难词和专业术语加中文括注，复杂段落后补完整中文翻译。
4. 如果涉及新概念，先补基础解释，再讲项目代码。
5. 直接把关键代码贴在对话里讲，而不是只丢文件路径。
6. 额外再贴一份带教学注释的代码，方便你逐行理解。
7. 在你继续追问时，沿着当前调用链继续下钻。
8. 你说“结束学习”时，自动更新 [19-study-session-log.md](19-study-session-log.md) 和 [20-concept-and-interview-bank.md](20-concept-and-interview-bank.md)。

你后面如果只想说一句“开始学习”或“继续学习”，新的 agent 也应该先读：

1. [00-learning-progress.md](00-learning-progress.md)
2. [.codex/skills/agentx-module-teacher/SKILL.md](../.codex/skills/agentx-module-teacher/SKILL.md)
