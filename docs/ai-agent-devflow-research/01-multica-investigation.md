# Multica 项目调查文档

调研日期：2026-04-27
本地仓库：外部调研时使用的 Multica checkout
官方仓库：https://github.com/multica-ai/multica
官方站点：https://multica.ai
官方文档：https://multica.ai/docs/how-multica-works

## 1. 一句话定位

Multica 是一个面向“人类 + AI agent 团队”的开源 managed agents 平台。它不直接替代 Claude Code、Codex、OpenCode、Gemini CLI 这类 coding agent，也不把所有代码执行搬到 Multica 云端，而是在这些本地/外部 coding agent 之上提供团队协作控制面：工作区、issue 看板、agent 身份、任务队列、runtime 注册、进度追踪、评论协作、技能沉淀和自部署能力。

更精确地说，Multica 解决的是“如何把 coding agent 变成团队里的可分配成员”：

- 人类像给同事派活一样把 issue 分配给 agent。
- Server 负责协作状态、权限、队列和实时事件。
- Daemon 在用户机器上领取任务并调用本地 AI 编程工具。
- Agent 在本地工作区读写代码、跑测试，再通过 Multica CLI/API 写回评论和状态。
- Skills 把团队经验变成可复用知识包，注入后续任务执行环境。

这一点在 `README.md`、`README.zh-CN.md`、`apps/docs/content/docs/how-multica-works.zh.mdx` 和官网文案中保持一致：Multica 的目标不是做一个新模型，而是做“coding agents 的项目管理和执行协调层”。

## 2. 总体架构

Multica 的官方架构可以抽象成四层：

```text
Next.js Web / Electron Desktop / CLI
        |
        v
Go Backend + WebSocket + PostgreSQL
        |
        v
Local Daemon on user machine
        |
        v
Claude Code / Codex / OpenCode / Gemini / Cursor Agent / ...
```

关键边界：

- **Web/Desktop/CLI**：人类用户的操作入口，负责创建 issue、分配 agent、查看进度、管理 runtime 和 skill。
- **Go Backend**：工作区、成员、agent、issue、comment、task queue、实时 WebSocket 事件、权限校验都在这里。
- **PostgreSQL**：保存协作事实、任务生命周期、session/workdir 信息、消息流、skill、runtime、订阅等状态。
- **Local Daemon**：运行在用户自己的机器上，检测本地可用 AI 编程 CLI，注册 runtime，轮询任务，准备执行环境，调用 provider backend。
- **AI Coding Tool**：真正写代码的一环，例如 Claude Code、Codex、OpenCode、Gemini、Cursor Agent 等。

Multica 最重要的安全和产品边界是：server 不直接执行 agent 任务，也不直接拿用户代码目录和本地 API key。代码执行和本地工具链留在用户机器上；server 只协调任务、状态、评论、事件和配置。

## 3. 概念模型

| 概念 | 作用 | 设计含义 |
|---|---|---|
| Workspace | 团队/租户边界 | issue、成员、agent、skill、runtime 都按 workspace 隔离 |
| Member | 人类成员 | 有 owner/admin/member 权限 |
| Agent | AI 工作者身份 | 可以被分配 issue、发评论、被 @、成为 project lead |
| Issue | 工作项 | 类似 Linear/Jira issue，是人类和 agent 协作的核心对象 |
| Task | agent 的一次执行 | issue、comment mention、chat 或 autopilot 触发后进入队列 |
| Runtime | `daemon × provider × workspace` | 决定 agent 任务在哪里、用哪个 coding CLI 执行 |
| Daemon | 本地执行桥梁 | 注册 runtime、轮询 claim task、执行并上报结果 |
| Skill | 知识包 | `SKILL.md` + 支持文件，挂到 agent 后注入执行环境 |
| Comment | 协作线程 | 人类和 agent 都可写，@agent 可轻量触发任务 |
| Autopilot | 自动化入口 | 定时或手动触发 agent 执行长期指令 |
| Inbox/Subscriber | 通知与订阅 | 面向人类成员，agent 不收 inbox 通知 |

一个非常值得借鉴的设计是 **actor 多态模型**。文档和代码都体现出 `actor_type + actor_id` 的思路：人类 member 和 agent 都是 actor，因此评论、issue 创建、活动流、订阅、project lead 等能力可以共享一套协作模型，而不是给 agent 写一套旁路逻辑。

## 4. Issue 到 Agent 执行的主流程

Multica 的核心流转可以压缩成一条链：

```text
Web/CLI 创建或更新 issue/comment
  -> Go API handler 校验权限、工作区、assignee、agent runtime
  -> TaskService 写 agent_task_queue
  -> Daemon 轮询并 claim task
  -> Daemon 准备 workdir/session/skills/runtime config
  -> Provider backend 调用 Claude/Codex/OpenCode 等本地 CLI
  -> Agent 通过 multica CLI/API 写回评论和 issue 状态
  -> Daemon complete/fail
  -> TaskService 广播实时事件、记录结果、必要时重试或兜底评论
```

典型“分配 issue 给 agent”的详细步骤：

1. 用户在 Web UI 或 CLI 创建/更新 issue，并把 assignee 设置为 agent。
2. `Handler.CreateIssue` 或 `Handler.UpdateIssue` 校验 workspace、assignee、agent 是否 archived、private agent 是否有权限、runtime 是否 ready。
3. 如果 issue 不是 `backlog` 且 agent ready，调用 `TaskService.EnqueueTaskForIssue` 创建 `queued` task。
4. Daemon 的 `pollLoop` 按 runtime 轮询 `/tasks/claim`。
5. Server 侧 `ClaimTaskByRuntime` 校验 daemon/runtime/workspace，并通过 SQL `FOR UPDATE SKIP LOCKED` claim 一条 task。
6. Claim response 返回 issue 上下文、agent instructions、skills、custom env/args、MCP config、model、workspace repos、trigger comment、prior session/workdir。
7. Daemon `handleTask` 调用 `StartTask`，进入 `running`，再 `runTask`。
8. `execenv.Prepare` 创建或复用执行目录，写 `.agent_context/issue_context.md`、provider skill 目录、runtime meta skill、Codex home 等。
9. Provider backend 执行对应 coding agent。Claude backend 处理流式 JSON、MCP config 和 resume session；Codex backend 启动 `codex app-server --listen stdio://` 并维护 thread/session。
10. Agent 在执行过程中按 runtime 指令通过 `multica issue status`、`multica issue comment add` 等 CLI 写回状态和评论。
11. Daemon drain provider message，批量上报 `/messages`，收到 session 后通过 `/session` 早期 pin，最后按结果调用 `/complete` 或 `/fail`。
12. `TaskService.CompleteTask` 或 `FailTask` 记录结果、广播事件、刷新 agent 状态，并在需要时自动重试或生成兜底评论。

## 5. 任务状态机

Issue 状态：

```text
backlog | todo | in_progress | in_review | done | blocked | cancelled
```

Task 状态：

```text
queued -> dispatched -> running -> completed
                                -> failed
                                -> cancelled
```

关键规则：

- `backlog` 是停车场：issue 分配给 agent 但仍处于 backlog 时不会立即入队。
- 非 backlog issue 分配给 ready agent 会入队。
- Assignee 从 Agent A 改为 Agent B 时，会取消该 issue 上活跃 task，再按需给 B 入队。
- 取消分配不入队新 task。
- 同一 agent 同一 issue 上的 pending task 会被串行化，避免重复执行。
- 不同 agent 可以在同一 issue 上并行执行，例如一个是 assignee，一个是 @mention 触发。
- 基础设施失败可自动重试，例如 runtime offline、runtime recovery、timeout；agent 自身业务错误不自动重试。
- 文档描述的自动重试上限是 2 次，Autopilot 不自动重试。

## 6. 四种触发方式

| 触发方式 | 流程 | 适用场景 | 特点 |
|---|---|---|---|
| 分配 issue | assignee 改为 agent 后入队 | 正式交付任务 | 最重，agent 成为负责人，可改 issue 状态和字段 |
| 评论 @agent | 评论解析 mention 后入队 | 让 agent 看一眼、补充分析、并行审查 | 不改 assignee/status，可一条评论 @ 多个 agent |
| Chat | 独立聊天 session 入队 | 不绑定 issue 的问答或草拟 | 固定上下文是聊天历史 |
| Autopilot | 定时/手动触发 | 周期性 standing order | 更像 automation，不走同样的自动重试策略 |

评论触发链里有几个小而重要的设计：

- 跳过 self-trigger，避免 agent 自己 @ 自己导致循环。
- `@all` 不触发 agent。
- private agent 的可见性和归属需要校验。
- 对同一 issue/agent 的 pending task 做去重。
- comment-triggered task 的回复需要挂到正确 parent comment，避免 session 复用时把回复挂错线程。

## 7. 代码层关键路径

入口层：

- Web 创建 issue：`packages/views/modals/create-issue.tsx`
- 前端 mutations：`packages/core/issues/mutations.ts`
- API client：`packages/core/api/client.ts`
- 后端路由：`server/cmd/server/router.go`
- CLI issue 命令：`server/cmd/multica/cmd_issue.go`
- Agent 运行时 CLI 上下文：`server/cmd/multica/cmd_agent.go`、`server/internal/cli/client.go`

后端 issue/comment/task：

- Issue handler：`server/internal/handler/issue.go`
- Comment handler：`server/internal/handler/comment.go`
- Task service：`server/internal/service/task.go`
- Task lifecycle handler：`server/internal/handler/task_lifecycle.go`
- DB queries：`server/pkg/db/queries/issue.sql`、`server/pkg/db/queries/agent.sql`

Daemon/runtime：

- Daemon 主循环：`server/internal/daemon/daemon.go`
- Daemon API handler：`server/internal/handler/daemon.go`
- 执行环境：`server/internal/daemon/execenv/execenv.go`
- Runtime meta skill：`server/internal/daemon/execenv/runtime_config.go`
- 评论回复指令：`server/internal/daemon/execenv/reply_instructions.go`

Provider backend：

- Provider 抽象：`server/pkg/agent/agent.go`
- Claude backend：`server/pkg/agent/claude.go`
- Codex backend：`server/pkg/agent/codex.go`
- 其他 provider：`server/pkg/agent/openclaw.go`、`opencode.go`、`gemini.go`、`cursor.go`、`kimi.go` 等。

数据库迁移：

- 初始 issue/task schema：`server/migrations/001_init.up.sql`
- runtime 独立化：`server/migrations/004_agent_runtime_loop.up.sql`
- task session/workdir：`server/migrations/020_task_session.up.sql`
- task messages：`server/migrations/026_task_messages.up.sql`
- trigger comment：`server/migrations/028_task_trigger_comment.up.sql`
- lease/retry：`server/migrations/055_task_lease_and_retry.up.sql`

实时事件：

- 事件定义：`server/pkg/protocol/events.go`
- 活动流监听：`server/cmd/server/activity_listeners.go`
- 前端实时同步：`packages/core/realtime/use-realtime-sync.ts`

## 8. Skill 机制

Multica 的 Skill 是 agent 的专业知识包，采用 Anthropic Agent Skills 风格：一个 `SKILL.md` 加可选脚本、模板、参考资料。它的价值是把团队经验从“某次 prompt”提升成“可挂载、可复用、可版本化的执行知识”。

设计要点：

- Skill 可以是 workspace skill，也可以先从 local skill 导入。
- Skill 创建或导入后需要挂到具体 agent 才生效。
- 一个 agent 可挂多个 skill，一个 skill 可被多个 agent 使用。
- 修改 skill 只影响新 task，正在执行的 task 使用旧版本。
- Skill 是静态知识包，MCP 是工具通道，两者边界明确。

在代码执行层，daemon claim task 时会收到 agent 绑定的 skills，`execenv` 将它们写入 provider 可理解的 skill 目录或上下文中，使不同 provider 尽可能共享同一套能力沉淀。

## 9. Runtime 与安全边界

Multica 的 runtime 设计是它区别于普通项目管理工具的核心：

- Runtime 是 `daemon × provider × workspace` 的组合。
- 同一台机器上装了多个 provider，加入多个 workspace，会注册多个 runtime。
- Daemon 每 3 秒轮询任务，每 15 秒心跳。
- Runtime 心跳超时后视为离线，离线 agent 不应继续被派新活。
- Claim response 里做 workspace isolation check，避免 runtime 领取到不属于自己 workspace 的任务。
- Agent CLI 在 daemon context 下拒绝 fallback 到用户全局 workspace，降低跨 workspace 写错风险。

这套设计的优点是用户代码和 API key 仍在本地，云端/自部署 server 主要承载协作状态。但它也意味着 Multica 的“托管”不是 Claude Managed Agents 那种完全云端沙箱托管，而是 **server 托管协作，daemon 托管执行入口，本地工具托管真实代码变更**。

## 10. 设计亮点

1. **把 agent 做成团队 actor，而不是后台 job**
   Agent 能被分配、发评论、被 @、出现在 project/board 中，这比“后台任务机器人”更接近真实协作。

2. **issue 与 task 分离**
   Issue 是用户可理解的工作项；task 是 agent 的执行实例。一个 issue 可以因为分配、评论、重试产生多个 task，避免把协作状态和执行状态揉成一个字段。

3. **Backlog 停车场**
   分配 agent 不一定立即开工，`backlog` 明确表示先停放，防止误触发高成本 agent 运行。

4. **本地 daemon 模式保护代码和凭证**
   Server 协调，daemon 执行，用户代码和本地 CLI 凭证不需要上传到 Multica server。

5. **评论线程语义很细**
   @mention、self-trigger 防护、parent comment 校验、pending task 去重，这些都是多人多 agent 协作里容易踩坑的地方。

6. **并发控制多层防线**
   Daemon 级最大并发、agent 级最大并发、SQL `FOR UPDATE SKIP LOCKED`、同 agent 同 issue 串行化共同降低重复执行和资源打爆风险。

7. **Provider 抽象务实**
   把 Claude/Codex/OpenCode/Gemini/Cursor 等 CLI 纳入统一 backend 接口，Multica 自己专注调度、上下文和协作。

8. **失败恢复路径较完整**
   包含 runtime offline/stale sweeper、orphan recovery、基础设施失败自动 retry、agent status reconcile、complete 兜底评论。

9. **Skill 作为团队能力复利**
   每次解决方案可以沉淀成 skill，使平台不只是一次次派活，而是积累组织能力。

## 11. 潜在不足

1. **Issue 状态与 task 状态可能脱节**
   后端 Start/Complete/Fail 明确不自动改 issue 状态，issue 状态依赖 agent 遵守 runtime 指令调用 `multica issue status`。如果 agent 忘记更新，UI 会出现 task 已完成但 issue 仍停在旧状态。

2. **PR/branch 结构化建模偏弱**
   服务端存在 `pr_url` 字段痕迹，但 daemon client 与 handler 字段不完全对齐；branch/PR 更像 final comment 的约定输出，尚不是端到端一等交付物。

3. **长流程需求拆解能力较轻**
   Multica 很适合 issue 级派活，但不像 AgentX 那样原生包含 requirement confirmation、architect planning、Task DAG、Merge Gate 等研发流程控制。

4. **云端隔离执行能力不如 Managed Agents**
   本地 daemon 模式保护隐私，但对团队而言依赖成员机器在线、工具安装正确、daemon 稳定运行。

5. **某些一致性边界仍可打磨**
   例如附件链接发生在 issue 创建事务之后、显式 null unassign 的边界、失败后 issue 状态广播等。

6. **Lease heartbeat 字段像是未来能力**
   `last_heartbeat_at` 已写入，但 stale 判断仍主要按 dispatched/started 时间，这部分可以继续演进。

## 12. 可学习的设计要点

如果我们要学习 Multica，最应该吸收的是：

- 用 issue/task 双模型区分“人类工作项”和“agent 执行实例”。
- 用 actor 多态让 agent 真正进入协作系统，而不是旁路机器人。
- 用 local daemon 连接已有 coding CLI，避免一开始自研 agent 本体。
- 用 task queue + claim + heartbeat + retry 建立可靠执行控制面。
- 用 WebSocket/event bus 实时反馈 agent 进度。
- 用 Skill 系统沉淀团队经验。
- 用 workspace isolation、private agent、runtime readiness 控制权限和安全。
- 用 Backlog、mention、chat、autopilot 提供多种启动力度。

## 13. 结论

Multica 是一个成熟度较高的“issue 驱动本地 agent runtime”平台。它的强项是把 agent 轻量接入团队工作流：任务分派、运行时协调、评论协作、实时追踪和技能沉淀做得比较完整。它的短板不是队列或协作层，而是更深的工程交付层：需求确认、任务 DAG、PR/branch 结构化产出、自动验证和 Merge Gate 还没有成为一等对象。

因此，Multica 最适合学习“如何把现有 coding agents 管起来”，而不是学习“如何完整自动化一个软件项目从 PRD 到交付的全过程”。后者需要结合 AgentX 和 Claude Managed Agents 的设计继续补齐。
