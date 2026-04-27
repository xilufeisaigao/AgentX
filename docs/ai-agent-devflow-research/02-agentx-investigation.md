# AgentX 项目调查文档

调研日期：2026-04-27
本地项目：当前 AgentX 仓库
重点目录：`docs`

## 1. 一句话定位

AgentX 是一个面向长流程软件交付的 Agent 控制面系统。它不是 prompt 拼接脚本，也不是单纯的 AI 聊天工具，而是把软件开发需求从确认、架构拆解、任务规划、上下文编译、Worker 执行、验证、Merge Gate 到最终交付串成一个可审计、可恢复、可查询的闭环。

如果说 Multica 的核心是“把 issue 分配给 agent，让 agent 像队友一样工作”，AgentX 的核心则是“把一条开发需求变成受控软件交付流水线，让多个 agent/worker/人工决策在明确状态机下协同推进”。

## 2. 文档体系

AgentX 的 docs 不是普通产品文档，而是一套面向项目讲解、面试表达和真实实现锚点的知识库。

主要入口：

- `docs/README.md`：总索引和统一口径。
- `docs/architecture/03-end-to-end-chain.md`：端到端主链路。
- `docs/architecture/04-runtime-artifacts.md`：运行时产物和证据链。
- `docs/architecture/05-core-flow-refactor.md`：核心流程抽象和未来收敛方向。
- `docs/current-state/02-runtime-audit-2026-03-17.md`：运行审计，说明主链路已跑通。
- `docs/schema/agentx_schema_v0.sql`：控制面表结构。
- `docs/openapi/agentx-control-plane.v0.yaml`：控制面 API。
- `docs/reference/truth-sources.md`：事实源和读模型规则。
- `docs/interview/agentx-00-项目总述与简历写法.md` 到 `agentx-07-交付闭环与MergeGate.md`：面试化设计材料。

文档中的统一口径是：

1. AgentX 不是 prompt 拼接脚本，而是面向长流程软件交付的 Agent 控制面。
2. 当前真实落地的是模块化单体、事件驱动编排、上下文快照、Worker 执行层、Merge Gate 和 HITL。
3. 上下文治理不是聊天记忆，而是确认需求、工单事件、运行证据和代码现实的分层记忆。
4. 当前还不是重型分布式平台，后续增强点在可观测性、索引持久化、执行隔离和可靠性。

## 3. 总体架构

AgentX 的主链路可以表示为：

```text
User
  -> Session
  -> Requirement Draft
  -> Requirement Confirmed
  -> ARCH_REVIEW Ticket
  -> Architect Agent
  -> Task DAG
  -> Context Snapshot
  -> Worker Claim
  -> Run + Git Worktree
  -> Merge Gate
  -> Verify Run
  -> DONE
  -> Session Completed
  -> Clone Repo / Delivery Evidence
```

它不是围绕单个 issue 字段变化构建，而是围绕多个一等对象构建：

- `sessions`：根关联 ID，承载一次需求/项目交付。
- `requirement_docs`：需求文档和确认版本。
- `tickets`：人机协同、架构审查、澄清、阻塞处理。
- `work_modules` / `work_tasks` / `work_task_dependencies`：architect 生成的任务图。
- `task_context_snapshots`：执行前编译出的上下文快照。
- `workers` / `task_runs` / `task_run_events`：执行层和运行历史。
- Merge Gate / delivery tag：把交付候选变成主线事实和可验证证据。

## 4. 模块边界

AgentX 文档明确了控制面的模块边界：

| 模块 | 职责 |
|---|---|
| session | 管会话生命周期，是 root correlation boundary |
| requirement | 管需求文档、版本和确认 |
| planning | 管模块、任务和依赖 |
| context | 管上下文刷新、编译和 snapshot |
| execution | 管 worker、run、heartbeat、事件和结果 |
| ticket | 管人机协同、澄清、架构审查、阻塞处理 |
| mergegate | 管 rebase、verify、fast-forward、delivery tag |
| query | 管 progress、task board、ticket inbox、run timeline 等读模型 |
| process | 管跨模块编排，不拥有核心业务状态 |

这个边界很重要。AgentX 把“业务事实”留在领域表和状态机中，把 process manager 作为编排层，而不是让某个超大 orchestrator 掌握所有状态。当前文档也诚实指出，`process` 和 runtime adapter 仍有膨胀风险，这是后续需要收敛的地方。

## 5. 端到端流转

### 5.1 创建 session

用户通过 API 创建 session。系统写入 `sessions`，发布 `SessionCreatedEvent`，随后自动创建 bootstrap 模块和 init task。

Session 是所有后续对象的根边界：

- tickets 归属于 session。
- tasks 归属于 session。
- runs 归属于 session。
- context snapshot 归属于 session 和 task。
- 交付证据也通过 session 串起来。

### 5.2 需求草案和确认

用户或 requirement agent 生成需求草案，写入：

- `requirement_docs`
- `requirement_doc_versions`

需求不是随便一段聊天上下文，而是有版本、有状态、有确认版本的一等事实。典型状态是：

```text
DRAFT -> IN_REVIEW -> CONFIRMED
```

只有确认后的 requirement 才触发架构和规划流程。这解决了很多 agent 项目的常见问题：需求还没稳定就开始写代码，导致后续返工和上下文漂移。

### 5.3 架构审查 ticket

Requirement 确认后，`RequirementConfirmedProcessManager` 创建 `ARCH_REVIEW` ticket，把需求移交给 architect 流程。

这里的关键思想是：不确定性和人机协同都走 ticket，而不是让 worker 在执行中直接和用户随意聊天。Ticket 是 AgentX 中承接澄清、决策、架构审查和阻塞的协作对象。

### 5.4 Architect 生成 Task DAG

Architect agent 处理 `ARCH_REVIEW` ticket，生成：

- `work_modules`
- `work_tasks`
- `work_task_dependencies`

这一步把需求从自然语言转成可执行任务图。依赖关系要求同一 session 边界、不能自依赖、必须是 DAG。

这和 Multica 的 issue 分配明显不同：Multica 通常把一个 issue 直接交给 agent；AgentX 会先做需求确认和架构拆解，再给 worker 执行具体 task。

### 5.5 Context Snapshot

执行前，context 层刷新并编译 `task_context_snapshots`。Run 不直接吃 session 全量聊天文本，而是绑定最近一次 `READY` 且未 stale 的 context snapshot。

Context snapshot 是 AgentX 的关键设计亮点。它把执行输入变成可追踪、可复用、可失效、可重建的事实：

- 需求变化会让旧 snapshot 变 stale。
- ticket 决策变化会让旧 snapshot 变 stale。
- run/merge 等运行证据变化也可能触发重新编译。
- 每次 run 绑定 snapshot，后续追查“当时 agent 看到了什么”有明确证据。

这比单纯把聊天历史塞给模型更工程化。

### 5.6 Worker Claim 与 Run

Worker 调度层扫描可执行任务，匹配 toolpack，claim task，创建 run，并进入 lease/heartbeat 生命周期。

Run 是不可变历史单元：

- 重试不是复用旧 run，而是创建新 run。
- Run 绑定 worker、context snapshot、base commit、branch、worktree。
- Run 通过 heartbeat/lease 防卡死。
- Run 事件写入 `task_run_events`。

### 5.7 Git Worktree 执行

Run 创建后分配 git worktree，在真实工作区执行 IMPL。运行时产物大致分为：

- `/agentx/repo`：git 相关产物。
- `/agentx/runtime-data`：context、runtime env、toolpack 等。
- `task/*` 分支、`run/*` 分支、`delivery/*` tag：长期证据。

AgentX 很强调“代码现实”而不是只看模型输出。Git 分支、worktree、tag 和 run events 共同组成可审计证据链。

### 5.8 Merge Gate 和 Verify

Run 成功后不是直接宣称完成，而是进入 Merge Gate：

1. 对交付候选做 rebase。
2. 创建 VERIFY run。
3. Verify 成功后 fast-forward main。
4. 打 `delivery/*` tag。
5. Task 从 `DELIVERED` 进入 `DONE`。
6. Session 满足完成条件后 complete，并发布 clone repo。

Merge Gate 是 AgentX 区别于很多 agent demo 的关键。它把“agent 说写完了”升级为“主线代码验证通过且留下交付证据”。

## 6. 三条状态机

AgentX 的任务流转不是一个万能状态字段，而是拆成三条状态机：

| 状态机 | 表/对象 | 表达什么 |
|---|---|---|
| 计划态 | `work_tasks` | task 是否已规划、依赖是否满足、是否等待 worker、是否交付候选、是否 done |
| 执行态 | `task_runs` | 某一次执行是否 running、成功、失败、取消、等待 foreman |
| 协同态 | `tickets` | 需求确认、架构审查、澄清、人工决策、阻塞处理 |

这种拆分的好处是：

- 计划失败不等于执行失败。
- 执行失败不等于需求失败。
- 人工澄清不污染 task/run 状态。
- 重试可以保留旧 run 历史。
- 前端可以从 query 视图聚合出 progress/task board/ticket inbox/run timeline。

## 7. Query 和控制台视图

AgentX 的前端/控制台不应直接把某张表当成全部状态源，而是通过查询聚合形成读模型：

- `progress`
- `task-board`
- `ticket-inbox`
- `run-timeline`
- `canCompleteSession`
- `phase`
- `deliveryTagPresent`

这些字段来自规则计算和多表聚合。这个设计很适合长流程系统，因为真实进度往往不是某个单表字段能表达的，而是 requirement、ticket、task、run、merge gate、delivery tag 的综合结果。

## 8. 设计亮点

1. **Session 作为根边界**
   所有 requirement、ticket、task、run、delivery evidence 都挂在 session 下，便于审计、恢复和查询。

2. **Requirement 确认后再开工**
   把需求草案和确认版本变成一等对象，减少“没想清楚就写代码”的混乱。

3. **Architect 生成 Task DAG**
   先规划依赖和模块，再交给 worker 执行，适合复杂项目，而不是把大需求直接丢给一个 agent。

4. **Ticket 承接不确定性**
   澄清、人工决策、架构审查走 ticket，避免 worker 执行层和用户自由聊天导致流程不可控。

5. **Context Snapshot 固化执行输入**
   每次 run 绑定 snapshot，可追踪当时上下文，支持 stale 和重新编译。

6. **Run 不可变**
   每次执行和重试都有独立 run_id，历史可审计，不覆盖旧证据。

7. **Git Worktree + Branch + Tag 形成交付证据**
   代码结果不是一段文本，而是 worktree、branch、run event、delivery tag 的组合。

8. **Merge Gate 把候选交付变成主线事实**
   Verify 成功后才 fast-forward main 并 DONE，适合真实软件交付。

9. **Query 视图隔离读模型复杂度**
   前端看到 progress 和 task board，背后由规则聚合，不把复杂状态暴露给 UI。

## 9. 与 Multica 的关键差异

| 维度 | AgentX | Multica |
|---|---|---|
| 核心对象 | Session / Requirement / Ticket / Task DAG / Run / Merge Gate | Workspace / Issue / Agent / Task / Runtime |
| 触发方式 | 确认需求后进入架构和任务拆解 | issue 分配、@mention、chat、autopilot |
| 任务粒度 | 一个需求可拆成 DAG 多任务 | 一个 issue 通常直接触发 agent task |
| 上下文 | Context snapshot 一等对象 | Issue 描述、评论、trigger comment、session/workdir |
| 执行证据 | run events、worktree、branch、delivery tag | task messages、comments、session/workdir |
| 交付闭环 | Merge Gate + Verify + DONE | Agent 自行更新 issue 状态和评论 |
| 团队协作 | 更像研发流水线控制面 | 更像 agent 版 Linear/Jira |

## 10. 当前边界和风险

AgentX 文档对当前边界比较诚实：

- 主链路已跑通，但还不是重型分布式平台。
- Worker 更接近 `worker + toolpack + runtime environment + local executor`，还不是强隔离动态子 agent 容器。
- 可观测性、索引持久化、执行隔离和可靠性仍需增强。
- `process` 模块和 runtime adapter 有膨胀风险，需要持续收敛一等角色。
- 文档更偏面试和设计解释，若要生产化还需要补充运维、权限、安全、失败恢复和多租户细节。

## 11. 可学习的设计要点

AgentX 最值得吸收的是：

- 用 session 作为一次需求/项目的根边界。
- 用 requirement doc version 管需求确认。
- 用 architect ticket 把“需求”转换成“计划”。
- 用 Task DAG 表达复杂项目依赖。
- 用 context snapshot 解决长流程上下文治理。
- 用 run 作为不可变执行历史。
- 用 ticket 承接 HITL，而不是把人工反馈混进执行状态。
- 用 Merge Gate + Verify 做真实交付闭环。
- 用 query 聚合给前端提供可理解进度。

## 12. 结论

AgentX 是三个项目里最适合学习“AI 自动化软件开发流程设计”的一个，因为它关心的不是单个 agent 怎么写代码，而是需求如何稳定、任务如何拆解、上下文如何治理、执行如何留下证据、失败如何恢复、交付如何进入主线。

它的短板是工程平台成熟度和通用生态弱于 Multica 和 Claude Managed Agents，但它在“PRD 到交付”的流程建模上最完整。对于我们后续设计自己的 agentic software delivery platform，AgentX 可以提供流程骨架，Multica 可以提供团队协作和 runtime 接入方式，Claude Managed Agents 可以提供托管 harness/sandbox/session event log 的基础设施范式。
