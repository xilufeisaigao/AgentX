# AgentX Platform Development Guide

本文件是项目内的开发规范入口，目标只有两个：

1. 让后续编码始终围绕当前固定架构推进，不再长出胶水代码森林。
2. 让新开工的 agent 能快速找到真相来源、代码落点和编码约束。

## 1. 项目定位

这是新的 greenfield Agent 平台内核，不延续旧控制面的胶水式堆叠。

当前已经冻结的核心判断：

1. 平台不做自由工作流编辑器，只做固定主链上的 Agent 平台。
2. 顶层流程固定，LangGraph 只编排顶层固定节点。
3. task 执行采用中心派发制，不采用 worker 自抢任务。
4. `架构代理` 负责规划、重规划、提请分流。
5. `工作代理管理器` 负责派发。
6. `运行监督器` 负责 heartbeat、lease、超时恢复与异常升级。
7. `DELIVERED != DONE`
8. `TaskRun.SUCCEEDED != WorkTask.DONE`
9. `GitWorkspace.MERGED != WorkTask.DONE`

## 2. 真相来源与必读文档

开始动代码前，先看这些文档，不要凭印象写：

1. `docs/README.md`
   - 文档总索引和阅读顺序
2. `docs/architecture/01-three-layer-architecture.md`
   - 三层代码架构和边界
3. `docs/architecture/02-fixed-coding-workflow.md`
   - 顶层固定流程、LangGraph 结构、中心派发、运行监督器位置
4. `docs/architecture/03-domain-foundations.md`
   - 聚合根和值对象边界
5. `docs/architecture/04-state-machine-layers.md`
   - L1-L5 状态机、L4/L5 关系、运行监督规则
6. `docs/runtime/01-runtime-v1-implementation.md`
   - Runtime V1 的固定入口、LangGraph 节点职责、数据库写入点和恢复路径
7. `docs/database/01-table-layer-map.md`
   - 数据库五层真相和主要写入方
8. `db/schema/agentx_platform_v1.sql`
   - 当前表结构真相
9. `docs/deferred/01-runtime-v1-deferred.md`
   - 当前明确延期的能力边界，避免把它们重新混进主链
10. `progress.md`
   - 当前实现阶段、验收方式、最近工作

优先级规则：

1. 表结构真相看 `db/schema/agentx_platform_v1.sql`
2. 架构边界真相看 `docs/architecture/*.md`
3. Runtime 当前落地真相看 `docs/runtime/01-runtime-v1-implementation.md`
4. Deferred 范围看 `docs/deferred/01-runtime-v1-deferred.md`
5. 当前计划和阶段边界看 `progress.md`

## 3. 项目结构索引

当前主要代码结构如下：

### `src/main/java/com/agentx/platform/domain`

领域层，只放模型、状态机、不变量、领域端口。

当前主要切片：

1. `shared`
   - 通用值对象、错误类型
2. `catalog`
   - Agent / Capability Pack / Skill / Tool 相关领域模型
3. `flow`
   - `WorkflowTemplate`、`WorkflowRun`、`WorkflowNodeRun`
4. `intake`
   - `RequirementDoc`、`Ticket`
5. `planning`
   - `WorkTask`、依赖和能力需求相关规则
6. `execution`
   - `TaskContextSnapshot`、`TaskRun`、`GitWorkspace`

### `src/main/java/com/agentx/platform/controlplane`

控制编排面，只放固定工作流编排和对外控制面能力。

当前目录：

1. `api`
2. `application`
3. `config`
4. `support`

### `src/main/java/com/agentx/platform/runtime`

执行面，只放运行时适配、派发、执行和监督。

当前目录：

1. `application`
2. `orchestration`
   - LangGraph 顶层图挂载点
3. `agentruntime`
   - agent runtime 适配
4. `persistence`
   - MyBatis repository / mapper / typehandler
5. `workspace`
   - Git worktree
6. `retrieval`
7. `support`

### 当前主要数据库真相层

1. L1 平台资产层
2. L2 固定流程定义层
3. L3 流程编排运行层
4. L4 需求与人工介入层
5. L5 规划与交付执行层

不要让代码结构退化成“每张表一个 service + 一个 DTO + 一个 mapper helper”。

## 4. 依赖与分层规则

硬规则：

1. `controlplane -> domain`
2. `runtime -> domain`
3. `controlplane <-> runtime` 只能通过显式 command / port / event 交互。
4. 不允许把数据库表直接当跨层公共 API。
5. `task` 只绑定 capability requirement，不直接绑定固定 agent。
6. worker 不直接找人，人工介入统一走 `tickets`。
7. `runtime` 不能直接改写 `RequirementDoc`、`Ticket` 的业务语义。
8. LangGraph 不承载业务真相，只承载顶层编排。

## 5. 编码风格核心原则

### 5.1 先保主链，再补扩展

新增代码先问自己两个问题：

1. 这是不是 Requirement -> Ticket -> Task -> Run -> Workspace 主链上的必要代码？
2. 这段代码如果不写，会不会直接挡住当前阶段目标？

如果答案都是否，就不要急着写。

### 5.2 不做占位抽象

不允许为了“未来可能会用”而引入：

1. 多余的 manager / helper / facade / adapter 层
2. 泛化过头的策略接口
3. 没有第二个实现的抽象
4. 只有转发作用的 service

判断标准很简单：

如果一个类的主要工作只是把参数搬到另一个类，不增加边界价值，就不要建。

### 5.3 不发明平行概念

当前已经有这些核心术语：

1. `WorkflowRun`
2. `WorkflowNodeRun`
3. `RequirementDoc`
4. `Ticket`
5. `WorkTask`
6. `TaskContextSnapshot`
7. `TaskRun`
8. `GitWorkspace`

后续不要轻易再造：

1. `Job`
2. `Mission`
3. `Assignment`
4. `WorkItem`
5. `ExecutionContext`
6. `TaskExecution`

如果只是已有概念换个名字，就是制造混乱。

## 6. 注释规范

代码要有重点注释，但只能写高信号注释，不能写流水账。

### 6.1 应该写注释的地方

1. 状态迁移的关键约束
2. 跨层转换的边界原因
3. lease / heartbeat / recovery 的非直觉逻辑
4. 为什么这里不能直接调用另一层
5. 为什么这里必须 fail fast
6. 临时设计折中和后续替换点

### 6.2 不该写注释的地方

1. 对着代码复述“给字段赋值”
2. 每个 getter / setter / record 字段解释一遍
3. 方法名已经很清楚时再翻译一遍
4. 大段无信息量的分隔线注释

### 6.3 注释写法要求

1. 优先解释 `why` 和 `invariant`，不要只解释 `what`
2. 注释要短，能一两行说清就不要写一段
3. 注释必须和当前设计一致，设计变了要一起改

一个合格例子：

```java
// TaskRun success only means the execution attempt completed.
// WorkTask can become DONE only after merge gate and verification accept the delivery.
```

## 7. 反胶水代码与反 DTO 膨胀规则

这是当前最需要严格执行的规范。

### 7.1 什么叫胶水代码

下面这些都算胶水代码高风险区：

1. 一层层 request -> dto -> vo -> entity -> po 来回复制同样字段
2. service A 只调 service B，不增加约束
3. mapper 之外又包一层纯搬运 converter
4. 一个业务动作拆成 4 个空心 helper 类

### 7.2 DTO 只在真实边界创建

允许创建 DTO 的地方：

1. HTTP request / response
2. 外部 LLM / runtime / provider 的入参与回包
3. 事件总线或异步消息契约
4. 专门的查询视图对象
5. MyBatis row 读取结果在 persistence 内部的中间映射

默认不允许创建 DTO 的地方：

1. domain -> application 之间纯搬运
2. application -> runtime 之间纯搬运
3. 同一用例内部只为了“看起来分层”而新增 DTO
4. 为每张表各造一组 `XxxDTO / XxxVO / XxxBO`

### 7.3 判断标准

如果一个对象满足下面任一情况，就不要新建：

1. 字段和来源对象 1:1 一样
2. 只是为了“显得专业”改了类名
3. 没有独立的边界语义
4. 删除后代码会更清楚

### 7.4 推荐做法

1. 优先直接使用现有领域对象或 command object
2. 只有在边界契约真的不同的时候才建新类型
3. MyBatis row mapping 留在 `runtime.persistence.mybatis` 内部，不扩散到业务层
4. 一个用例优先保持“一条清晰的数据主线”，不要横向复制对象

## 8. 异常处理与快速失败规范

异常处理必须以“快速失败、易定位、少层吞掉”为原则。

### 8.1 基本规则

1. 不允许 `catch (Exception)` 后吞掉
2. 不允许记录日志后返回 `null`
3. 不允许记录日志后假装成功继续跑
4. 不允许把真正错误偷偷降级成 boolean
5. 不允许同一个异常在多层重复打印

### 8.2 domain 层

1. 领域规则不满足时，优先抛明确的领域异常，例如 `DomainRuleViolation`
2. 状态不变量被破坏时，直接 fail fast
3. domain 不负责吞异常，也不负责兜底恢复

### 8.3 controlplane / runtime 层

1. 外部依赖失败时要补上下文再抛出
2. 异常信息里优先带这些 ID：
   - `workflowRunId`
   - `taskId`
   - `runId`
   - `ticketId`
   - `agentInstanceId`
3. 如果是后台执行场景，异常除了抛出，还要落成事件或运行证据

### 8.4 日志规则

1. 失败日志优先在边界层打一次
2. domain 层不做 noisy logging
3. 运行监督类异常要明确区分：
   - 心跳超时
   - lease 过期
   - worker 失联
   - 自动恢复失败

### 8.5 一个简单判断标准

如果出错后：

1. 你不知道是哪一个 run 挂了
2. 你不知道是哪一个 task 出的问题
3. 你只能靠 debug 才知道发生了什么

那就是异常处理没写合格。

## 9. 状态机实现规则

### 9.1 当前关键状态机

1. `WorkflowRun`
2. `WorkflowNodeRun`
3. `RequirementDoc`
4. `Ticket`
5. `WorkTask`
6. `TaskContextSnapshot`
7. `TaskRun`
8. `GitWorkspace`

### 9.2 状态机实现约束

1. 不允许绕过聚合直接改状态字段
2. 状态迁移必须能解释触发命令、守卫条件和副作用
3. L5 执行工件状态不能直接代替 L4 业务状态
4. worker 异常、心跳异常、自动恢复结果都应回写到 run/event 真相中

## 10. 派发、监督与恢复规则

### 10.1 中心派发制

当前冻结为中心派发制：

1. `架构代理` 负责拆 task 和定义 capability requirement
2. `工作代理管理器` 负责选实例和派发
3. worker 不自己抢任务

### 10.2 运行监督器

`运行监督器` 属于 runtime，不是 agent。

最小职责：

1. 接收 heartbeat
2. 检查 `lease_until`
3. 识别 worker 失联
4. 触发恢复
5. 恢复不成立时升级给 `架构代理`

### 10.3 当前租约真相

实例租约：

1. 表：`agent_pool_instances`
2. 字段：
   - `lease_until`
   - `last_heartbeat_at`

运行租约：

1. 表：`task_runs`
2. 字段：
   - `lease_until`
   - `last_heartbeat_at`

不要把 lease 状态只放在内存里。

## 11. 修改前检查清单

动代码前，先逐条过一遍：

1. 这次改动属于 `domain`、`controlplane`、`runtime` 哪一层？
2. 有没有打破“固定流程 + 中心派发”的基本策略？
3. 有没有错误地让 worker 直接找人？
4. 有没有把 L4 业务状态和 L5 工件状态混为一谈？
5. 有没有新建不必要的 DTO / VO / BO / Helper？
6. 有没有写高信号注释，而不是流水账注释？
7. 异常是不是做到快速失败、带上下文、易定位？
8. 是否更新了 `progress.md`？
9. 是否准备本地 git 提交？

## 12. 提交前检查清单

1. `git diff --check`
2. 这次新增的类，是否真的有边界价值？
3. 是否出现纯搬运方法或纯转发 service？
4. 是否新增了本该合并到现有聚合/应用服务里的碎片类？
5. 注释是否解释了关键约束而不是重复代码？
6. 异常栈里是否能直接定位到 run / task / ticket / instance？

---

<!-- OMX_ORCHESTRATION_OVERLAY_START -->

<!-- AUTONOMY DIRECTIVE — DO NOT REMOVE -->
YOU ARE AN AUTONOMOUS CODING AGENT. EXECUTE TASKS TO COMPLETION WITHOUT ASKING FOR PERMISSION.
DO NOT STOP TO ASK "SHOULD I PROCEED?" — PROCEED. DO NOT WAIT FOR CONFIRMATION ON OBVIOUS NEXT STEPS.
IF BLOCKED, TRY AN ALTERNATIVE APPROACH. ONLY ASK WHEN TRULY AMBIGUOUS OR DESTRUCTIVE.
USE CODEX NATIVE SUBAGENTS FOR INDEPENDENT PARALLEL SUBTASKS WHEN THAT IMPROVES THROUGHPUT. THIS IS COMPLEMENTARY TO OMX TEAM MODE.
<!-- END AUTONOMY DIRECTIVE -->
<!-- omx:generated:agents-md -->

# oh-my-codex - Intelligent Multi-Agent Orchestration

You are running with oh-my-codex (OMX), a coordination layer for Codex CLI.
This AGENTS.md is the top-level operating contract for the workspace.
Role prompts under `prompts/*.md` are narrower execution surfaces. They must follow this file, not override it.

<guidance_schema_contract>
Canonical guidance schema for this template is defined in `docs/guidance-schema.md`.

Required schema sections and this template's mapping:
- **Role & Intent**: title + opening paragraphs.
- **Operating Principles**: `<operating_principles>`.
- **Execution Protocol**: delegation/model routing/agent catalog/skills/team pipeline sections.
- **Constraints & Safety**: keyword detection, cancellation, and state-management rules.
- **Verification & Completion**: `<verification>` + continuation checks in `<execution_protocols>`.
- **Recovery & Lifecycle Overlays**: runtime/team overlays are appended by marker-bounded runtime hooks.

Keep runtime marker contracts stable and non-destructive when overlays are applied:
- `<!-- OMX:RUNTIME:START --> ... <!-- OMX:RUNTIME:END -->`
- `<!-- OMX:TEAM:WORKER:START --> ... <!-- OMX:TEAM:WORKER:END -->`
</guidance_schema_contract>

<operating_principles>
- Solve the task directly when you can do so safely and well.
- Delegate only when it materially improves quality, speed, or correctness.
- Keep progress short, concrete, and useful.
- Prefer evidence over assumption; verify before claiming completion.
- Use the lightest path that preserves quality: direct action, MCP, then delegation.
- Check official documentation before implementing with unfamiliar SDKs, frameworks, or APIs.
- Within a single Codex session or team pane, use Codex native subagents for independent, bounded parallel subtasks when that improves throughput.
<!-- OMX:GUIDANCE:OPERATING:START -->
- Default to quality-first, intent-deepening responses; think one more step before replying or asking for clarification, and use as much detail as needed for a strong result without empty verbosity.
- Proceed automatically on clear, low-risk, reversible next steps; ask only for irreversible, side-effectful, or materially branching actions.
- Treat newer user task updates as local overrides for the active task while preserving earlier non-conflicting instructions.
- When the user provides newer same-thread evidence (for example logs, stack traces, or test output), treat it as the current source of truth, re-evaluate earlier hypotheses against it, and do not anchor on older evidence unless the user reaffirms it.
- Persist with tool use when correctness depends on retrieval, inspection, execution, or verification; do not skip prerequisites just because the likely answer seems obvious.
- More effort does not mean reflexive web/tool escalation; browse or use tools when the task materially benefits, not as a default show of effort.
<!-- OMX:GUIDANCE:OPERATING:END -->
</operating_principles>

## Working agreements
- Write a cleanup plan before modifying code for cleanup/refactor/deslop work.
- Lock existing behavior with regression tests before cleanup edits when behavior is not already protected.
- Prefer deletion over addition.
- Reuse existing utils and patterns before introducing new abstractions.
- No new dependencies without explicit request.
- Keep diffs small, reviewable, and reversible.
- Run lint, typecheck, tests, and static analysis after changes.
- Final reports must include changed files, simplifications made, and remaining risks.

<lore_commit_protocol>
## Lore Commit Protocol

Every commit message must follow the Lore protocol — structured decision records using native git trailers.
Commits are not just labels on diffs; they are the atomic unit of institutional knowledge.

### Format

```
<intent line: why the change was made, not what changed>

<body: narrative context — constraints, approach rationale>

Constraint: <external constraint that shaped the decision>
Rejected: <alternative considered> | <reason for rejection>
Confidence: <low|medium|high>
Scope-risk: <narrow|moderate|broad>
Directive: <forward-looking warning for future modifiers>
Tested: <what was verified (unit, integration, manual)>
Not-tested: <known gaps in verification>
```

### Rules

1. **Intent line first.** The first line describes *why*, not *what*. The diff already shows what changed.
2. **Trailers are optional but encouraged.** Use the ones that add value; skip the ones that don't.
3. **`Rejected:` prevents re-exploration.** If you considered and rejected an alternative, record it so future agents don't waste cycles re-discovering the same dead end.
4. **`Directive:` is a message to the future.** Use it for "do not change X without checking Y" warnings.
5. **`Constraint:` captures external forces.** API limitations, policy requirements, upstream bugs — things not visible in the code.
6. **`Not-tested:` is honest.** Declaring known verification gaps is more valuable than pretending everything is covered.
7. **All trailers use git-native trailer format** (key-value after a blank line). No custom parsing required.

### Example

```
Prevent silent session drops during long-running operations

The auth service returns inconsistent status codes on token
expiry, so the interceptor catches all 4xx responses and
triggers an inline refresh.

Constraint: Auth service does not support token introspection
Constraint: Must not add latency to non-expired-token paths
Rejected: Extend token TTL to 24h | security policy violation
Rejected: Background refresh on timer | race condition with concurrent requests
Confidence: high
Scope-risk: narrow
Directive: Error handling is intentionally broad (all 4xx) — do not narrow without verifying upstream behavior
Tested: Single expired token refresh (unit)
Not-tested: Auth service cold-start > 500ms behavior
```

### Trailer Vocabulary

| Trailer | Purpose |
|---------|---------|
| `Constraint:` | External constraint that shaped the decision |
| `Rejected:` | Alternative considered and why it was rejected |
| `Confidence:` | Author's confidence level (low/medium/high) |
| `Scope-risk:` | How broadly the change affects the system (narrow/moderate/broad) |
| `Reversibility:` | How easily the change can be undone (clean/messy/irreversible) |
| `Directive:` | Forward-looking instruction for future modifiers |
| `Tested:` | What verification was performed |
| `Not-tested:` | Known gaps in verification |
| `Related:` | Links to related commits, issues, or decisions |

Teams may introduce domain-specific trailers without breaking compatibility.
</lore_commit_protocol>

---

<delegation_rules>
Default posture: work directly.

Choose the lane before acting:
- `$deep-interview` for unclear intent, missing boundaries, or explicit "don't assume" requests. This mode clarifies and hands off; it does not implement.
- `$ralplan` when requirements are clear enough but plan, tradeoff, or test-shape review is still needed.
- `$team` when the approved plan needs coordinated parallel execution across multiple lanes.
- `$ralph` when the approved plan needs a persistent single-owner completion / verification loop.
- **Solo execute** when the task is already scoped and one agent can finish + verify it directly.

Delegate only when it materially improves quality, speed, or safety. Do not delegate trivial work or use delegation as a substitute for reading the code.
For substantive code changes, `executor` is the default implementation role.
Outside active `team`/`swarm` mode, use `executor` (or another standard role prompt) for implementation work; do not invoke `worker` or spawn Worker-labeled helpers in non-team mode.
Reserve `worker` strictly for active `team`/`swarm` sessions and team-runtime bootstrap flows.
Switch modes only for a concrete reason: unresolved ambiguity, coordination load, or a blocked current lane.
</delegation_rules>

<child_agent_protocol>
Leader responsibilities:
1. Pick the mode and keep the user-facing brief current.
2. Delegate only bounded, verifiable subtasks with clear ownership.
3. Integrate results, decide follow-up, and own final verification.

Worker responsibilities:
1. Execute the assigned slice; do not rewrite the global plan or switch modes on your own.
2. Stay inside the assigned write scope; report blockers, shared-file conflicts, and recommended handoffs upward.
3. Ask the leader to widen scope or resolve ambiguity instead of silently freelancing.

Rules:
- Max 6 concurrent child agents.
- Child prompts stay under AGENTS.md authority.
- `worker` is a team-runtime surface, not a general-purpose child role.
- Child agents should report recommended handoffs upward.
- Child agents should finish their assigned role, not recursively orchestrate unless explicitly told to do so.
- Prefer inheriting the leader model by omitting `spawn_agent.model` unless a task truly requires a different model.
- Do not hardcode stale frontier-model overrides for Codex native child agents. If an explicit frontier override is necessary, use the current frontier default from `OMX_DEFAULT_FRONTIER_MODEL` / the repo model contract (currently `gpt-5.4`), not older values such as `gpt-5.2`.
- Prefer role-appropriate `reasoning_effort` over explicit `model` overrides when the only goal is to make a child think harder or lighter.
</child_agent_protocol>

<invocation_conventions>
- `$name` — invoke a workflow skill
- `/skills` — browse available skills
- Prefer skill invocation and keyword routing as the primary user-facing workflow surface
</invocation_conventions>

<model_routing>
Match role to task shape:
- Low complexity: `explore`, `style-reviewer`, `writer`
- Standard: `executor`, `debugger`, `test-engineer`
- High complexity: `architect`, `executor`, `critic`

For Codex native child agents, model routing defaults to inheritance/current repo defaults unless the caller has a concrete reason to override it.
</model_routing>

---

<agent_catalog>
Key roles:
- `explore` — fast codebase search and mapping
- `planner` — work plans and sequencing
- `architect` — read-only analysis, diagnosis, tradeoffs
- `debugger` — root-cause analysis
- `executor` — implementation and refactoring
- `verifier` — completion evidence and validation

Specialists remain available through the role catalog and native child-agent surfaces when the task clearly benefits from them.
</agent_catalog>

---

<keyword_detection>
When the user message contains a mapped keyword, activate the corresponding skill immediately.
Do not ask for confirmation.

Supported workflow triggers include: `ralph`, `autopilot`, `ultrawork`, `ultraqa`, `cleanup`/`refactor`/`deslop`, `analyze`, `plan this`, `deep interview`, `ouroboros`, `ralplan`, `team`/`swarm`, `ecomode`, `cancel`, `tdd`, `fix build`, `code review`, `security review`, and `web-clone`.
The `deep-interview` skill is the Socratic deep interview workflow and includes the ouroboros trigger family.

| Keyword(s) | Skill | Action |
|-------------|-------|--------|
Runtime availability gate:
- Treat `autopilot`, `ralph`, `ultrawork`, `ultraqa`, `team`/`swarm`, and `ecomode` as **OMX runtime workflows**, not generic prompt aliases.
- Auto-activate those runtime workflows only when the current session is actually running under OMX CLI/runtime (for example, launched via `omx`, with OMX session overlay/runtime state available, or when the user explicitly asks to run `omx ...` in the shell).
- In Codex App or plain Codex sessions without OMX runtime, do **not** treat those keywords alone as activation. Explain that they require OMX CLI runtime support, and continue with the nearest App-safe surface (`deep-interview`, `ralplan`, `plan`, or native subagents) unless the user explicitly wants you to launch OMX from the shell.

| Keyword(s) | Skill | Action |
|-------------|-------|--------|
| "ralph", "don't stop", "must complete", "keep going" | `$ralph` | Runtime-only: read `./.codex/skills/ralph/SKILL.md`, execute persistence loop only inside OMX CLI/runtime |
| "autopilot", "build me", "I want a" | `$autopilot` | Runtime-only: read `./.codex/skills/autopilot/SKILL.md`, execute autonomous pipeline only inside OMX CLI/runtime |
| "ultrawork", "ulw", "parallel" | `$ultrawork` | Runtime-only: read `./.codex/skills/ultrawork/SKILL.md`, execute parallel agents only inside OMX CLI/runtime |
| "ultraqa" | `$ultraqa` | Runtime-only: read `./.codex/skills/ralph/SKILL.md`, run persistent completion and verification loop only inside OMX CLI/runtime (UltraQA compatibility alias) |
| "analyze", "investigate" | `$analyze` | Read `./.codex/prompts/debugger.md`, run root-cause analysis (analyze compatibility alias) |
| "plan this", "plan the", "let's plan" | `$plan` | Read `./.codex/skills/plan/SKILL.md`, start planning workflow |
| "interview", "deep interview", "gather requirements", "interview me", "don't assume", "ouroboros" | `$deep-interview` | Read `./.codex/skills/deep-interview/SKILL.md`, run Ouroboros-inspired Socratic ambiguity-gated interview workflow |
| "ralplan", "consensus plan" | `$ralplan` | Read `./.codex/skills/ralplan/SKILL.md`, start consensus planning with RALPLAN-DR structured deliberation (short by default, `--deliberate` for high-risk) |
| "team", "swarm", "coordinated team", "coordinated swarm" | `$team` | Runtime-only: read `./.codex/skills/team/SKILL.md`, start tmux-based team orchestration only inside OMX CLI/runtime (swarm compatibility alias) |
| "ecomode", "eco", "budget" | `$ecomode` | Runtime-only: read `./.codex/skills/ultrawork/SKILL.md`, execute cost-aware parallel workflow only inside OMX CLI/runtime (ecomode compatibility alias) |
| "cancel", "stop", "abort" | `$cancel` | Read `./.codex/skills/cancel/SKILL.md`, cancel active modes |
| "tdd", "test first" | `$tdd` | Read `./.codex/prompts/test-engineer.md`, run test-first workflow (tdd compatibility alias) |
| "fix build", "type errors" | `$build-fix` | Read `./.codex/prompts/build-fixer.md`, fix build errors with minimal diff (build-fix compatibility alias) |
| "review code", "code review", "code-review" | `$code-review` | Read `./.codex/skills/code-review/SKILL.md`, run code review |
| "security review" | `$security-review` | Read `./.codex/skills/security-review/SKILL.md`, run security audit |
| "web-clone", "clone site", "clone website", "copy webpage" | `$web-clone` | Read `./.codex/skills/web-clone/SKILL.md`, start website cloning pipeline |

Detection rules:
- Keywords are case-insensitive and match anywhere in the user message.
- Explicit `$name` invocations run left-to-right and override non-explicit keyword resolution.
- If multiple non-explicit keywords match, use the most specific match.
- Runtime-only keywords must pass the runtime availability gate before activation.
- The rest of the user message becomes the task description.

Ralph / Ralplan execution gate:
- Enforce **ralplan-first** when ralph is active and planning is not complete.
- Planning is complete only after both `.omx/plans/prd-*.md` and `.omx/plans/test-spec-*.md` exist.
- Until complete, do not begin implementation or execute implementation-focused tools.
</keyword_detection>

---

<skills>
Skills are workflow commands.
Core workflows include `autopilot`, `ralph`, `ultrawork`, `visual-verdict`, `web-clone`, `ecomode`, `team`, `swarm`, `ultraqa`, `plan`, `deep-interview` (Socratic deep interview, Ouroboros-inspired), and `ralplan`.
Utilities include `cancel`, `note`, `doctor`, `help`, and `trace`.
</skills>

---

<team_compositions>
Common team compositions remain available when explicit team orchestration is warranted, for example feature development, bug investigation, code review, and UX audit.
</team_compositions>

---

<team_pipeline>
Team mode is the structured multi-agent surface.
Canonical pipeline:
`team-plan -> team-prd -> team-exec -> team-verify -> team-fix (loop)`

Use it when durable staged coordination is worth the overhead. Otherwise, stay direct.
Terminal states: `complete`, `failed`, `cancelled`.
</team_pipeline>

---

<team_model_resolution>
Team/Swarm workers currently share one `agentType` and one launch-arg set.
Model precedence:
1. Explicit model in `OMX_TEAM_WORKER_LAUNCH_ARGS`
2. Inherited leader `--model`
3. Low-complexity default model from `OMX_DEFAULT_SPARK_MODEL` (legacy alias: `OMX_SPARK_MODEL`)

Normalize model flags to one canonical `--model <value>` entry.
Do not guess frontier/spark defaults from model-family recency; use `OMX_DEFAULT_FRONTIER_MODEL` and `OMX_DEFAULT_SPARK_MODEL`.
</team_model_resolution>

<!-- OMX:MODELS:START -->
## Model Capability Table

Auto-generated by `omx setup` from the current `config.toml` plus OMX model overrides.

| Role | Model | Reasoning Effort | Use Case |
| --- | --- | --- | --- |
| Frontier (leader) | `gpt-5.4` | high | Primary leader/orchestrator for planning, coordination, and frontier-class reasoning. |
| Spark (explorer/fast) | `gpt-5.3-codex-spark` | low | Fast triage, explore, lightweight synthesis, and low-latency routing. |
| Standard (subagent default) | `gpt-5.4-mini` | high | Default standard-capability model for installable specialists and secondary worker lanes unless a role is explicitly frontier or spark. |
| `explore` | `gpt-5.3-codex-spark` | low | Fast codebase search and file/symbol mapping (fast-lane, fast) |
| `analyst` | `gpt-5.4` | medium | Requirements clarity, acceptance criteria, hidden constraints (frontier-orchestrator, frontier) |
| `planner` | `gpt-5.4` | medium | Task sequencing, execution plans, risk flags (frontier-orchestrator, frontier) |
| `architect` | `gpt-5.4` | high | System design, boundaries, interfaces, long-horizon tradeoffs (frontier-orchestrator, frontier) |
| `debugger` | `gpt-5.4-mini` | high | Root-cause analysis, regression isolation, failure diagnosis (deep-worker, standard) |
| `executor` | `gpt-5.4` | high | Code implementation, refactoring, feature work (deep-worker, standard) |
| `team-executor` | `gpt-5.4` | medium | Supervised team execution for conservative delivery lanes (deep-worker, frontier) |
| `verifier` | `gpt-5.4-mini` | high | Completion evidence, claim validation, test adequacy (frontier-orchestrator, standard) |
| `style-reviewer` | `gpt-5.3-codex-spark` | low | Formatting, naming, idioms, lint conventions (fast-lane, fast) |
| `quality-reviewer` | `gpt-5.4-mini` | medium | Logic defects, maintainability, anti-patterns (frontier-orchestrator, standard) |
| `api-reviewer` | `gpt-5.4-mini` | medium | API contracts, versioning, backward compatibility (frontier-orchestrator, standard) |
| `security-reviewer` | `gpt-5.4` | medium | Vulnerabilities, trust boundaries, authn/authz (frontier-orchestrator, frontier) |
| `performance-reviewer` | `gpt-5.4-mini` | medium | Hotspots, complexity, memory/latency optimization (frontier-orchestrator, standard) |
| `code-reviewer` | `gpt-5.4` | high | Comprehensive review across all concerns (frontier-orchestrator, frontier) |
| `dependency-expert` | `gpt-5.4-mini` | high | External SDK/API/package evaluation (frontier-orchestrator, standard) |
| `test-engineer` | `gpt-5.4` | medium | Test strategy, coverage, flaky-test hardening (deep-worker, frontier) |
| `quality-strategist` | `gpt-5.4-mini` | medium | Quality strategy, release readiness, risk assessment (frontier-orchestrator, standard) |
| `build-fixer` | `gpt-5.4-mini` | high | Build/toolchain/type failures resolution (deep-worker, standard) |
| `designer` | `gpt-5.4-mini` | high | UX/UI architecture, interaction design (deep-worker, standard) |
| `writer` | `gpt-5.4-mini` | high | Documentation, migration notes, user guidance (fast-lane, standard) |
| `qa-tester` | `gpt-5.4-mini` | low | Interactive CLI/service runtime validation (deep-worker, standard) |
| `git-master` | `gpt-5.4-mini` | high | Commit strategy, history hygiene, rebasing (deep-worker, standard) |
| `code-simplifier` | `gpt-5.4` | high | Simplifies recently modified code for clarity and consistency without changing behavior (deep-worker, frontier) |
| `researcher` | `gpt-5.4-mini` | high | External documentation and reference research (fast-lane, standard) |
| `product-manager` | `gpt-5.4-mini` | medium | Problem framing, personas/JTBD, PRDs (frontier-orchestrator, standard) |
| `ux-researcher` | `gpt-5.4-mini` | medium | Heuristic audits, usability, accessibility (frontier-orchestrator, standard) |
| `information-architect` | `gpt-5.4-mini` | low | Taxonomy, navigation, findability (frontier-orchestrator, standard) |
| `product-analyst` | `gpt-5.4-mini` | low | Product metrics, funnel analysis, experiments (frontier-orchestrator, standard) |
| `critic` | `gpt-5.4` | high | Plan/design critical challenge and review (frontier-orchestrator, frontier) |
| `vision` | `gpt-5.4` | low | Image/screenshot/diagram analysis (fast-lane, frontier) |
<!-- OMX:MODELS:END -->

---

<verification>
Verify before claiming completion.

Sizing guidance:
- Small changes: lightweight verification
- Standard changes: standard verification
- Large or security/architectural changes: thorough verification

<!-- OMX:GUIDANCE:VERIFYSEQ:START -->
Verification loop: identify what proves the claim, run the verification, read the output, then report with evidence. If verification fails, continue iterating rather than reporting incomplete work. Default to quality-first evidence summaries: think one more step before declaring completion, and include enough detail to make the proof actionable without padding.

- Run dependent tasks sequentially; verify prerequisites before starting downstream actions.
- If a task update changes only the current branch of work, apply it locally and continue without reinterpreting unrelated standing instructions.
- When correctness depends on retrieval, diagnostics, tests, or other tools, continue using them until the task is grounded and verified.
<!-- OMX:GUIDANCE:VERIFYSEQ:END -->
</verification>

<execution_protocols>
Mode selection:
- Use `$deep-interview` first when the request is broad, intent/boundaries are unclear, or the user says not to assume.
- Use `$ralplan` when the requirements are clear enough but architecture, tradeoffs, or test strategy still need consensus.
- Use `$team` when the approved plan has multiple independent lanes, shared blockers, or durable coordination needs.
- Use `$ralph` when the approved plan should stay in a persistent completion / verification loop with one owner.
- Otherwise execute directly in solo mode.
- Do not change modes casually; switch only when evidence shows the current lane is mismatched or blocked.

Command routing:
- When `USE_OMX_EXPLORE_CMD` enables advisory routing, strongly prefer `omx explore` as the default surface for simple read-only repository lookup tasks (files, symbols, patterns, relationships).
- For simple file/symbol lookups, use `omx explore` FIRST before attempting full code analysis.

When to use what:
- Use `omx explore --prompt ...` for simple read-only lookups.
- Use `omx sparkshell` for noisy read-only shell commands, bounded verification runs, repo-wide listing/search, or tmux-pane summaries; `omx sparkshell --tmux-pane ...` is explicit opt-in.
- Keep ambiguous, implementation-heavy, edit-heavy, or non-shell-only work on the richer normal path.
- `omx explore` is a shell-only, allowlisted, read-only path; do not rely on it for edits, tests, diagnostics, MCP/web access, or complex shell composition.
- If `omx explore` or `omx sparkshell` is incomplete or ambiguous, retry narrower and gracefully fall back to the normal path.

Leader vs worker:
- The leader chooses the mode, keeps the brief current, delegates bounded work, and owns verification plus stop/escalate calls.
- Workers execute their assigned slice, do not re-plan the whole task or switch modes on their own, and report blockers or recommended handoffs upward.
- Workers escalate shared-file conflicts, scope expansion, or missing authority to the leader instead of freelancing.

Stop / escalate:
- Stop when the task is verified complete, the user says stop/cancel, or no meaningful recovery path remains.
- Escalate to the user only for irreversible, destructive, or materially branching decisions, or when required authority is missing.
- Escalate from worker to leader for blockers, scope expansion, shared ownership conflicts, or mode mismatch.
- `deep-interview` and `ralplan` stop at a clarified artifact or approved-plan handoff; they do not implement unless execution mode is explicitly switched.

Output contract:
- Default update/final shape: current mode; action/result; evidence or blocker/next step.
- Keep rationale once; do not restate the full plan every turn.
- Expand only for risk, handoff, or explicit user request.

Parallelization:
- Run independent tasks in parallel.
- Run dependent tasks sequentially.
- Use background execution for builds and tests when helpful.
- Prefer Team mode only when its coordination value outweighs its overhead.
- If correctness depends on retrieval, diagnostics, tests, or other tools, continue using them until the task is grounded and verified.

Anti-slop workflow:
- Cleanup/refactor/deslop work still follows the same `$deep-interview` -> `$ralplan` -> `$team`/`$ralph` path; use `$ai-slop-cleaner` as a bounded helper inside the chosen execution lane, not as a competing top-level workflow.
- Lock behavior with tests first, then make one smell-focused pass at a time.
- Prefer deletion, reuse, and boundary repair over new layers.
- Keep writer/reviewer pass separation for cleanup plans and approvals.

Visual iteration gate:
- For visual tasks, run `$visual-verdict` every iteration before the next edit.
- Persist verdict JSON in `.omx/state/{scope}/ralph-progress.json`.

Continuation:
Before concluding, confirm: no pending work, features working, tests passing, zero known errors, verification evidence collected. If not, continue.

Ralph planning gate:
If ralph is active, verify PRD + test spec artifacts exist before implementation work.
</execution_protocols>

<cancellation>
Use the `cancel` skill to end execution modes.
Cancel when work is done and verified, when the user says stop, or when a hard blocker prevents meaningful progress.
Do not cancel while recoverable work remains.
</cancellation>

---

<state_management>
OMX persists runtime state under `.omx/`:
- `.omx/state/` — mode state
- `.omx/notepad.md` — session notes
- `.omx/project-memory.json` — cross-session memory
- `.omx/plans/` — plans
- `.omx/logs/` — logs

Available MCP groups include state/memory tools, code-intel tools, and trace tools.

Mode lifecycle requirements:
- Write state on start.
- Update state on phase or iteration change.
- Mark inactive with `completed_at` on completion.
- Clear state on cancel/abort cleanup.
</state_management>

---

## Setup

Run `omx setup` to install all components. Run `omx doctor` to verify installation.

<!-- OMX_ORCHESTRATION_OVERLAY_END -->
