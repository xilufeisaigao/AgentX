# 模块 08：上下文管理与技能编译（Context Packs + Task Skill）

更新时间：2026-03-12

范围说明：
本模块定义 AgentX 在“多角色 + 多任务 + 可审计交付”场景下的上下文治理方案，重点解决两类问题：
1. 多角色上下文如何保持一致且不过载（避免靠聊天记忆、避免上下文漂移）
2. Worker 的 `task_skill` 如何生成、归一化、去重与可追溯（避免每个 Worker 重复发明“怎么做”）

本模块不要求你现在决定数据库与向量库落地方式；它只把“应该有哪些上下文包、如何生成、硬约束是什么”定死。

实现说明：
1. 本文仍然保持“设计约束优先、实现细节后置”的写法。
2. 截至 2026-03，工程实现已经在 `docs/12-context-plan-v1.md` 中落地为 `LangChain4j semantic indexing + lexical fallback`，但不改变本设计文档的控制面边界。
3. 当前真实代码已经进一步把同一套 Repo Context 检索能力复用到 worker runtime：worker 除了拿到 `task_context_pack` / `task_skill` 外，还会额外拿到 `task_evidence_snapshot` 与统一生成的 `workspace_snapshot`。

术语引用：
1. 角色与职责：见 `docs/02-concepts-and-roles.md`
2. 工单/提请与事件链：见 `docs/03-project-design-module.md`
3. 工具包（Toolpacks）与技能（Skill）拼接：见 `docs/04-foreman-worker-module.md`
4. Worker 任务包（Task Package）与 `task_context/task_skill`：见 `docs/05-worker-execution-and-monitoring.md`
5. Git 基线、合并门禁与 `DELIVERED/DONE`：见 `docs/06-git-worktree-workflow.md`
6. `.agentx/` 证据目录与交付规范：见 `docs/07-definition-of-done.md`

非目标（明确排除，避免跑偏）：
1. 不把“记忆”做成聊天记录的长期存档（聊天不是事实来源）
2. 不讨论任何具体向量库/检索库的选型与代码落地（后续实现阶段再定）
3. 不把上下文处理 Agent 变成“万能 Agent”（它不参与业务决策，也不写代码）

---

## 1. 核心结论（写死的规则）

1. 本系统的“事实来源”只能来自：确认版需求/架构规格、工单事件链、run 事件链与工作报告、Git commit（模块 03/05/06/07）
2. 任何角色的输入上下文必须来自“上下文包（Context Pack）”，而不是直接把历史聊天塞给模型
3. `task_context` 与 `task_skill` 必须分离：
   - `task_context` = 事实与引用（refs）
   - `task_skill` = 如何做的可执行指导（可复用、可去重、可版本化）
4. 引入一个专门的“上下文处理 Agent（Context Processor）”负责编译上下文包与 `task_skill`：
   - 只允许摘要/归并/引用/重排，不允许创造新事实
   - 输出必须可追溯（每条关键信息必须能指向来源 ref）
5. INIT 的上下文与权限在初始化阶段是例外，但必须一次性收敛：
   - INIT 完成后系统解锁点写死为 INIT 任务 `DONE`（模块 06）
   - INIT 之外禁止 `write_scope=/`（模块 05/06）

---

## 2. 为什么需要单独的上下文治理（白话）

不做上下文治理，系统会出现两个不可控问题：
1. **上下文漂移**：同一个项目在不同 Agent/不同 run 的描述逐渐不一致，最终出现“看起来都合理但互相矛盾”的事实分叉
2. **重复发明**：每个 Worker 都会重复生成一套“怎么做”，而且互相不一致，导致工程行为不可复现

上下文治理的目标不是“让模型更聪明”，而是把它变成可控的工程工人：
1. 看相同事实 -> 得到可审计结论
2. 做相同操作 -> 产出可复现结果

---

## 3. 关键概念（不要把它当术语）

### 3.1 Fact Source（事实源）

事实源指“可以被系统承认并回放的东西”，例如：
1. `requirement_doc.confirmed_version`（确认版需求）
2. ADR/架构规格（架构师产物）
3. `tickets + ticket_events`（提请与决策）
4. `task_runs + task_run_events + work_report`（执行记录与报告）
5. Git commit（`base_commit/delivery_commit/merge_candidate_commit`）

聊天记录不是事实源；它最多是“输入噪声”，不能成为系统依据。

### 3.2 Ref（引用）

Ref 的作用是让上下文变短但仍可追溯。最小 ref 语义示例：
1. `req:REQ-xxx@v2`（确认版需求版本）
2. `ticket:TCK-xxx`（某个提请/工单）
3. `run:RUN-xxx`（某次 run 与报告）
4. `git:<commit>:<path>`（模块 07 的 `artifact_ref`）

### 3.3 Context Pack（上下文包）

上下文包就是“把 refs 编译成下一步可执行输入”的结构化产物。
它的目标是：
1. 给对的人看对的内容（角色隔离）
2. 足够短（避免 token 爆炸）
3. 可追溯（每条结论有来源）

### 3.4 Skill Fragment vs Task Skill

1. Skill Fragment：可复用的小片段（例如“Spring Boot 3.x 项目如何跑测试”）
2. Task Skill：针对某个任务/某次 run 的最终可执行指导（由多个 fragment 组合归一化而来）

---

## 4. 新角色：上下文处理 Agent（Context Processor）

### 4.1 它负责什么

上下文处理 Agent 是一个“编译器式”的 Agent，它做三类工作：
1. 编译 Role Context Pack：给不同角色生成“可读、短、可追溯”的上下文
2. 编译 Task Context Pack：为某个任务生成 `task_context`（refs 为主）
3. 编译 Task Skill：把“技能碎片 + 组合碎片 + 任务模板 + 工具包约束”归一化成最终 `task_skill`

### 4.2 它不负责什么（写死边界）

1. 不做产品决策：需要取舍必须走 `DECISION/CLARIFICATION`（模块 03）
2. 不写代码、不跑命令：它不绑定 toolpacks，不参与执行阶段
3. 不修改事实源：它只能读取事实源并生成摘要/索引/上下文包

### 4.3 它的硬约束（防自由发挥）

1. 不允许新增事实：
   - 发现缺口只能输出 `need_clarification`，由总工发起提请
2. 不允许静默消解冲突：
   - 若输入 refs 中存在矛盾（例如两个 ADR 冲突），必须输出 `need_decision`
3. 输出必须可追溯：
   - Role Pack/Task Pack/Task Skill 必须包含 `source_refs`

---

## 5. 上下文包类型与最小结构（草案）

说明：
这不是数据库 schema，而是“上下文包”的稳定契约格式。

### 5.1 Role Context Pack（按角色编译）

```yaml
role_context_pack:
  pack_id: "CTX-..."
  session_id: "SES-..."
  role: "requirement_agent | architect_agent | foreman | worker | context_processor"
  generated_at: "timestamp"

  source_refs:
    - "req:REQ-...@confirmed_v2"
    - "ticket:TCK-..."
    - "adr:ADR-..."
    - "run:RUN-..."
    - "git:<commit>:<path>"

  summary:
    goal: "一句话目标（来自确认需求）"
    hard_constraints:
      - "必须满足的约束（引用来源）"
    current_state:
      - "当前处于哪个阶段/门禁（引用来源）"
    open_questions:
      - "尚未解决的问题（引用来源）"

  next_actions:
    - "下一步建议动作（不做决策，只列需要谁去做）"
```

说明：
1. Role Pack 的 `summary` 只能从事实源提取，不允许扩写推理
2. `next_actions` 只能指向“流程动作”（例如发起提请/编译任务包/进入门禁），不能代替用户做价值取舍

### 5.2 Task Context Pack（生成 `task_context`）

```yaml
task_context_pack:
  task_id: "TASK-..."
  run_kind: "IMPL | VERIFY"

  requirement_ref: "req:REQ-...@confirmed_v2"
  architecture_refs:
    - "adr:ADR-..."
    - "spec:ARCH-..."

  module_ref: "module:MOD-..."
  prior_run_refs:
    - "run:RUN-..."     # 若是重试/增量

  repo_baseline_ref: "git:<base_commit>"
  # 可选：若本次任务与特定决策强绑定
  decision_refs:
    - "ticket:TCK-... (DECISION)"
```

### 5.3 Task Skill（生成 `task_skill`）

```yaml
task_skill:
  skill_id: "TSKILL-..."
  task_id: "TASK-..."
  generated_at: "timestamp"

  source_fragments:
    - "skill:JAVA21.v1"
    - "skill:MAVEN.v1"
    - "skill:SPRINGBOOT3.v2"
    - "combo:JAVA21+MAVEN.v1"

  toolpack_assumptions:
    - "toolpack:java@21"
    - "toolpack:maven@3.x"

  conventions:
    - "代码/目录/命名约定（只写对本任务重要的）"

  recommended_commands:
    - "mvn -q -DskipTests=false test"
    - "mvn -q package"

  pitfalls:
    - "常见坑与规避方式"

  stop_rules:
    - "遇到哪些情况必须 NEED_DECISION/NEED_CLARIFICATION"

  expected_outputs:
    - "变更摘要"
    - "命令清单与结果摘要"
    - "delivery_commit（若 run_kind=IMPL）"
```

---

## 6. `task_skill` 的编译、去重与冲突处理（写死流程）

上下文处理 Agent 生成 `task_skill` 时，必须按固定步骤执行：
1. 收集输入：required_toolpacks + 技术栈版本 + 任务模板（T0~T5）+ 项目约束 refs
2. 选择片段：从 skill fragments/combos 中选出“最小足够集合”
3. 冲突检测：
   - 片段之间矛盾（例如要求 Java8 与 Java21 同时成立）-> `need_decision`
   - 缺关键片段（例如没有对应构建工具）-> `need_clarification`
4. 归一化输出：生成一份短且结构固定的 `task_skill`
5. 产物引用：将 `source_fragments` 写入 `task_skill`，便于追溯“这份指导从哪拼出来的”

说明（为什么要写死）：
1. 不写死流程，`task_skill` 会变成“每次随缘生成的提示词”，无法审计
2. 写死流程后，你才可能做稳定的增量优化（只替换某个 fragment，而不是全局漂移）

---

## 7. 上下文刷新触发点与快照状态机（什么时候必须重新编译）

上下文处理 Agent 需要在以下事件发生后重新编译相关上下文包：
1. 需求确认版本变化（confirmed_version 更新）
2. `DECISION/CLARIFICATION` 工单完成（用户补信息/做选择后）
3. 任意 run 进入 `SUCCEEDED/FAILED/CANCELLED`（会产生新的工作报告与证据）
4. 合并门禁结束且任务进入 `DONE`（`main` 事实发生变化）
5. INIT 解锁（INIT 任务进入 `DONE`，模块 06）

为了解决“上下文组装进度不可见”的问题，v0 增加任务级快照对象（`task_context_snapshots`）并写死状态机：
1. `PENDING`：已触发编译，等待调度
2. `COMPILING`：上下文处理 Agent 正在编译
3. `READY`：编译完成，可用于 run 下发
4. `FAILED`：编译失败，需重试或人工处理
5. `STALE`：事实源已变化，旧快照失效（不可继续用于新 run）

最小状态迁移：
1. `PENDING -> COMPILING -> READY`
2. `COMPILING -> FAILED`
3. `READY -> STALE`（触发条件即本节的 1~5）
4. `FAILED/STALE -> COMPILING`（重编译）

---

## 8. 上下文可观测与启动门禁（硬前置）

为防止“任务做一半才发现上下文未组装”，把上下文就绪写成控制面硬约束：
1. 创建 run 前，必须存在 `task_context_snapshots(status=READY)`，并且与任务当前事实源指纹一致（非 `STALE`）
2. 该快照必须被显式绑定到 run（`task_runs.context_snapshot_id`），作为审计锚点
3. 若快照不存在/失败/过期，控制面禁止下发 Task Package（返回 `PRECONDITION_FAILED`）
4. Worker 收到 `NEED_*` 后进入 `WAITING_FOREMAN`；总工 triage 若引入新事实（例如用户补充信息）：
   - 先重编译快照到 `READY`
   - 再决定“恢复同 run”或“新建 run”

同 run 恢复的硬条件（避免审计漂移）：
1. 仅当上下文事实指纹未变化时允许恢复同 run
2. 若事实指纹已变化，必须新建 run（新 run 绑定新 `context_snapshot_id`）

---

## 9. 证据目录与保留策略（只定义最小规则）

本模块不新增目录体系，只复用模块 07 的约定并补充最小落点：
1. AgentX 过程证据统一放 `.agentx/`（模块 07）
2. 上下文包与快照建议落在 `.agentx/context/`（可增量，不要求一次铺满）
3. 对外交付的确认版需求导出到 `.agentx/requirements/`（模块 07）

上下文保留策略（v0 写死）：
1. `task_context_snapshots` 元数据（状态、引用、指纹、时间戳）默认长期保留，不作为日常 GC 目标
2. `.agentx/context/` 下的快照正文在 session 为 `ACTIVE/PAUSED` 时禁止自动删除
3. session 进入 `COMPLETED` 后，正文至少保留 180 天；超期可归档/清理
4. 即使正文被归档/清理，也必须保留快照元数据与来源指纹，保证“至少可审计、可定位”

可查询性约定（直接回答“是否一直可查”）：
1. 元数据与来源链应始终可查
2. 正文在保留期内保证在线可查；超保留期后通过归档检索或恢复查看（不承诺永久在线）

---

## 10. “记忆框架”与 LangChain4j（可选实现方向，不绑定）

结论（避免误解）：
1. 记忆框架通常解决“怎么调用模型、怎么做检索、怎么做结构化输出”
2. 它们解决不了我们的“流程门禁、权限边界、可审计交付”这些控制面问题

### 10.1 LangChain4j 可能带来的收益

对本系统最可能有价值的是三类能力：
1. 统一 LLM 接入：替你屏蔽模型供应商差异（便于切换/AB）
2. 结构化输出：让 Context Pack、Task Skill、提请 payload 更稳定（减少格式漂移）
3. RAG 拼装：当 `.agentx/` 与 `docs/` 变大后，辅助从证据库中检索相关片段再编译上下文包

### 10.2 LangChain4j 不会替你解决的

1. Toolpacks 权限与 `write_scope/read_scope` 的硬约束（模块 04/05）
2. 租约/心跳/并发领任务原子性（模块 05/06）
3. Git 门禁：rebase -> VERIFY merge candidate -> fast-forward merge（模块 06/07）

因此，框架的定位应当被写死为：**实现“上下文处理 Agent 的工具箱”，而不是控制面本身**。

---

## 11. 交互场景（最小闭环，帮助你验证是否可用）

本节只列“必须解释清楚的场景”，避免写故事。

### X1：需求确认后，生成面向架构师/总工的稳定输入

触发：需求文档进入 `CONFIRMED`（模块 03）  
动作：
1. 上下文处理 Agent 生成 `Role Context Pack(role=architect_agent)` 与 `Role Context Pack(role=foreman)`
2. pack 的 `source_refs` 至少包含：确认版需求 ref + 当前 OPEN 工单列表
3. 若发现需求文本存在关键缺口（例如技术栈未定），上下文处理 Agent 输出 `need_clarification`，由总工发起提请

### X2：总工准备派发某个任务，先编译 task_context 与 task_skill

触发：某个 `work_task` 进入 `READY_FOR_ASSIGN`（模块 04/06）  
动作：
1. 上下文处理 Agent 编译 `Task Context Pack`（主要是 refs）
2. 上下文处理 Agent 编译 `Task Skill`（基于 skill fragments/combos + toolpacks + 任务模板）
3. 编译结果落为 `task_context_snapshots`，状态推进到 `READY`（失败则 `FAILED`）
4. 若 `task_skill` 与 `required_toolpacks` 不一致（例如要求 Java21 但 worker 无 Java21 toolpack），上下文处理 Agent 输出 `need_decision`，由总工修正任务标注或提请用户放行变更

### X3：Worker run 启动前的“上下文完整性”检查

触发：控制面准备创建 `task_run` 并下发 Task Package（模块 05/06）  
最小检查项（上下文处理 Agent 可承担该编译校验责任）：
1. 必须存在最新 `READY` 的 `context_snapshot_id`，且指纹未过期（非 `STALE`）
2. `task_context.requirement_ref` 必须存在且指向 confirmed_version
3. `run_kind=VERIFY` 时 `verify_commands` 必须存在，且 `write_scope` 必须为空（模块 05/07）
4. `task_skill` 必须包含 stop rules（防脑补）与推荐命令清单（可复现）
5. `task_runs.context_snapshot_id` 必须绑定到本次下发快照（审计锚点）

### X4：run 结束后刷新快照，避免后续角色继续基于旧事实

触发：任意 run 进入 `SUCCEEDED/FAILED/CANCELLED`（模块 05）或任务进入 `DONE`（模块 06/07）  
动作：
1. 上下文处理 Agent 更新对应角色的 `Role Context Pack`（至少刷新 current_state/open_questions）
2. 把关键证据引用挂接到 `.agentx/`（例如工作报告、VERIFY 结论、交付 tag 等），并在 pack 中加入 `artifact_ref`

### X5：发生冲突或矛盾时的唯一处理方式（禁止“默默修正”）

触发：上下文处理 Agent 在编译时发现输入冲突（例如两个规格互相矛盾）  
动作：
1. 输出 `need_decision` 或 `need_clarification`（取决于需要“补信息”还是需要“取舍”）
2. 由总工把它转成 `DECISION/CLARIFICATION` 工单进入用户决策面（模块 03）
3. 在工单完成前，不允许继续生成“假定冲突已解决”的新上下文包
