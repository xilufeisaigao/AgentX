# 模块 09：控制面接口契约（Control Plane API Contract）

更新时间：2026-02-23

范围说明：
本模块定义 AgentX 控制面（Control Plane）的 HTTP API 契约（v0），用于支撑：
1. 用户通过最小前端/脚本完成“需求确认、提请响应、查看进度”
2. 需求/架构/上下文处理/总工等 Agent 通过接口读写“项目账本”（工单、事件链、任务、run）
3. Worker 通过接口完成“领任务、心跳续租、进度事件、阻塞提请、提交工作报告”

你已确认的 v0 技术选型（写死作为前置约束）：
1. DB：MySQL 8.x（InnoDB）
2. 迁移：Flyway（执行 v0 schema 与后续增量迁移）
3. 数据访问：MyBatis（关键路径手写 SQL；暂不引入 MyBatis-Plus）

与 OpenAPI 的关系：
1. 本文档是“人类可读的契约说明”（语义 + 状态机 + 触发条件）
2. 同步维护一份 OpenAPI 3.0 文档：`docs/openapi/agentx-control-plane.v0.yaml`
   - 用于后续 AI/脚本自动调用与客户端生成
   - OpenAPI 只描述接口形状；关键语义仍以本文档为准（避免 YAML 失真）

术语引用：
1. 工单/提请：见 `docs/03-project-design-module.md`
2. Worker/Toolpacks/Task/Module：见 `docs/04-foreman-worker-module.md`
3. run/事件链/NEED_*：见 `docs/05-worker-execution-and-monitoring.md`
4. 并发领任务 + Git worktree + 合并门禁：见 `docs/06-git-worktree-workflow.md`
5. DoD 与交付规范：见 `docs/07-definition-of-done.md`
6. 上下文包 + `task_skill` 编译：见 `docs/08-context-management-module.md`
7. 表结构真相：见 `docs/schema/agentx_schema_v0.sql`

非目标（明确排除，避免跑偏）：
1. 不定义 UI（只定义接口）
2. 不引入复杂鉴权体系（v0 假设在可信环境运行；如需鉴权后续增量）
3. 不在接口层引入“业务编排逻辑”（编排属于控制面实现；脚本只是客户端）

---

## 1. 核心结论（先把规则定死）

1. API 采用版本前缀：`/api/v0/...`，v0 内不做破坏性变更（必要时新增字段/新增接口）
2. 控制面是“项目账本”的唯一写入口：所有状态机推进必须通过 API 完成并落库（不可手工改库）
3. 事件链优先：过程细节写入 `ticket_events/task_run_events`；状态字段只表达“下一步是谁/是否可继续”
4. Worker 只能通过 API 上报 `NEED_DECISION/NEED_CLARIFICATION`，不得直接面向用户输出提请
5. 任何 `NEED_*` 进入 `WAITING_FOREMAN` 后，必须先由总工 triage；仅当总工无法内部闭环时，才创建用户可见的 `DECISION/CLARIFICATION` 工单
6. 合并门禁与 DoD 的关键语义必须由服务端强制：
   - `DELIVERED != DONE`
   - VERIFY 必须绑定 merge candidate（模块 06/07）
7. INIT 独占：在 INIT 任务 `DONE` 前，全局只允许一个 active run（模块 06），API 必须强制此约束
8. 上下文门禁：run 创建/恢复前必须绑定最新 `READY` 的 `task_context_snapshot`；缺失或过期一律返回 `PRECONDITION_FAILED`
9. Session 创建后必须自动引导 bootstrap：自动创建 `bootstrap` 模块 + 唯一 `tmpl.init.v0` 任务，并预编译该任务的 IMPL/VERIFY `READY` 快照
10. Git 仓库隔离：每个 session 使用独立仓库根（`sessions/<session_id>/repo`）；`task/*`、`run/*`、`main` 都限定在该 session 仓库内
11. `DELIVERED` 任务进入 merge gate 采用“事件驱动立即触发 + GC 兜底补偿”双路径，避免长时间滞留
12. VERIFY 失败策略：
   - 基础设施失败：允许同一 merge candidate 自动重试（最多 2 次）
   - 业务失败：原任务从 `DELIVERED` 回退到可调度状态继续 debug（不自动拆分 verify-failed bugfix 任务）
13. Git 证据链要求：
   - rebase 产出的 merge candidate 必须写入 `refs/agentx/candidate/<task>/<attempt>`
   - merge 成功后 `main` 必须至少存在一个注释 `delivery/<YYYYMMDD-HHmm>` tag

---

## 2. 协议约定（HTTP 层）

### 2.1 Content-Type 与编码

1. 请求与响应统一使用 JSON：`Content-Type: application/json`
2. 字符集统一 UTF-8

### 2.2 错误响应约定（v0 最小）

统一错误结构（建议 v0 写死，便于脚本与 Agent 消费）：

```json
{
  "error": {
    "code": "CONFLICT | NOT_FOUND | VALIDATION | PRECONDITION_FAILED | INTERNAL",
    "message": "human readable summary",
    "details": {
      "hint": "optional"
    }
  }
}
```

HTTP 状态码最小映射：
1. `400`：参数校验失败（VALIDATION）
2. `404`：资源不存在（NOT_FOUND）
3. `409`：并发冲突/状态不允许（CONFLICT）
4. `412`：前置条件不满足（PRECONDITION_FAILED，例如 INIT 未 DONE 却请求并发派发）
5. `500`：服务端内部错误（INTERNAL）

---

## 3. 对象契约（只列 v0 必要字段）

说明：
接口对象必须能映射到 `docs/schema/agentx_schema_v0.sql`，禁止“为了方便先加字段”。

### 3.1 Session

```yaml
session:
  session_id: "SES-..."
  title: "..."
  status: "ACTIVE | PAUSED | COMPLETED"
  created_at: "timestamp"
  updated_at: "timestamp"
```

### 3.2 Requirement Doc + Version

```yaml
requirement_doc:
  doc_id: "REQ-..."
  session_id: "SES-..."
  current_version: 3
  confirmed_version: 2
  status: "DRAFT | IN_REVIEW | CONFIRMED"
  title: "..."
  created_at: "timestamp"
  updated_at: "timestamp"
```

```yaml
requirement_doc_version:
  doc_id: "REQ-..."
  version: 3
  content: "markdown (REQ-DOC-v1/REQ-DOC-v1-zh)"
  created_by_role: "user | requirement_agent"
  created_at: "timestamp"
```

`requirement_doc_version.content` 约束（v0 冻结）：
1. 必须是 markdown 文本
2. 必须满足 `REQ-DOC-v1` 或 `REQ-DOC-v1-zh` 模板：
   - `schema_version: req_doc_v1`（英文章节）
   - `schema_version: req_doc_v1_zh`（中文等价章节）
3. 详细模板见：`docs/11-requirement-doc-standard.md`

```yaml
requirement_agent_draft_response:
  doc_id: "REQ-..."        # 仅在草稿已落库时存在
  version: 2               # 仅在草稿已落库时存在
  status: "IN_REVIEW"      # 仅在草稿已落库时存在
  content: "markdown (REQ-DOC-v1/REQ-DOC-v1-zh) | null"
  persisted: true|false
  provider: "mock | bailian"
  model: "qwen3.5-plus-2026-02-15"
  phase: "DISCOVERY_CHAT | READY_TO_DRAFT | NEED_MORE_INFO | HANDOFF_CREATED | DRAFT_CREATED | DRAFT_REVISED"
  assistant_message: "string | null"
  ready_to_draft: true|false
  missing_information: ["string", "..."]
```

`requirement-agent/drafts` 语义（v0）：
1. 同一接口承载“需求澄清聊天 + 草稿生成/修订”。
2. 当 `doc_id` 为空时，服务端先做澄清判定，不会强制每轮都生成文档。
3. 用户明确触发（如输入“确认需求”）且 `ready_to_draft=true` 后，服务端才生成首版 REQ-DOC-v1/REQ-DOC-v1-zh。
4. 若触发过早且信息不足，返回 `phase=NEED_MORE_INFO`，并给出 `missing_information`。
5. 首版文档产出后，前端可直接展示 `content` 进入“可编辑区”；用户直接编辑时走 `POST /api/v0/requirement-docs/{docId}/versions`。
6. 若请求被判定为架构层变化，返回 `phase=HANDOFF_CREATED`，并由后端自动创建 `HANDOFF` 工单给 `architect_agent`。
7. 澄清阶段对话历史由后端写入 Redis（按 `sessionId` 分桶，TTL 控制），生成首版文档时必须拼接该历史上下文，避免仅基于“确认需求”触发词生成空泛文档。
8. Redis 历史仅适用于 `doc_id` 为空的 discovery 阶段；`doc_id` 非空（增量修订）时不得依赖 Redis 作为记忆源。
9. 增量修订的事实来源是 `requirement_doc_versions` 最新版本正文（必要时结合 `confirmed_version` 与 `tickets/ticket_events`），而不是完整聊天记录。
10. Redis discovery 历史清理策略：`DRAFT_CREATED(persisted=true)` 后清理；`HANDOFF_CREATED` 后清理；其余情况由 TTL 回收。
11. `POST /api/v0/requirement-docs/{docId}/confirm` 的语义是“冻结当前版本为价值基线”，不是“永久锁死文档”。
12. 文档已确认后，若用户提出价值层增量，后端应创建新版本并回到 `IN_REVIEW`，需再次确认后更新 `confirmed_version`。
13. 文档已确认后，若用户提出架构层变化，后端应创建 `HANDOFF` 工单给 `architect_agent`，而非直接改写需求文档版本。
14. `PUT /api/v0/requirement-docs/{docId}/content` 仅是“前端完整编辑提交”的便捷入口，语义等价于 `POST /versions(created_by_role=user)`，仍必须新增版本，禁止原地覆盖历史版本。
15. `GET /api/v0/sessions/{sessionId}` 返回“可恢复会话的最小快照”（session + 当前 requirement 文档），不返回完整历史聊天。

### 3.3 Ticket + TicketEvent

```yaml
ticket:
  ticket_id: "TCK-..."
  session_id: "SES-..."
  type: "HANDOFF | ARCH_REVIEW | DECISION | CLARIFICATION"
  status: "OPEN | IN_PROGRESS | WAITING_USER | DONE | BLOCKED"
  title: "..."
  created_by_role: "user | requirement_agent | architect_agent"
  assignee_role: "requirement_agent | architect_agent"
  requirement_doc_id: "REQ-..."   # optional
  requirement_doc_ver: 2          # optional
  payload_json: "{}"              # must be valid JSON text
  claimed_by: "agent_instance_id" # optional
  lease_until: "timestamp"        # optional
  created_at: "timestamp"
  updated_at: "timestamp"
```

```yaml
ticket_event:
  event_id: "EVT-..."
  ticket_id: "TCK-..."
  event_type: "STATUS_CHANGED | COMMENT | DECISION_REQUESTED | USER_RESPONDED | ARTIFACT_LINKED"
  actor_role: "user | requirement_agent | architect_agent"
  body: "human readable summary"
  data_json: "{}"                 # optional valid JSON text
  created_at: "timestamp"
```

### 3.4 Work Module + Work Task

```yaml
work_module:
  module_id: "MOD-..."
  session_id: "SES-..."
  name: "..."
  description: "..."
  created_at: "timestamp"
  updated_at: "timestamp"
```

```yaml
work_task:
  task_id: "TASK-..."
  module_id: "MOD-..."
  title: "..."
  task_template_id: "tmpl.init.v0 | tmpl.impl.v0 | tmpl.verify.v0 | tmpl.bugfix.v0 | tmpl.refactor.v0 | tmpl.test.v0"
  status: "PLANNED | WAITING_WORKER | READY_FOR_ASSIGN | ASSIGNED | DELIVERED | DONE"
  required_toolpacks_json: "[\"TP-...\", \"TP-...\"]"
  active_run_id: "RUN-..."        # optional
  created_by_role: "architect_agent"
  created_at: "timestamp"
  updated_at: "timestamp"
```

### 3.5 Task Context Snapshot（上下文编译账本）

```yaml
task_context_snapshot:
  snapshot_id: "CTXS-..."
  task_id: "TASK-..."
  run_kind: "IMPL | VERIFY"
  status: "PENDING | COMPILING | READY | FAILED | STALE"
  trigger_type: "REQUIREMENT_CONFIRMED | TICKET_DONE | RUN_FINISHED | MERGE_DONE | INIT_DONE | MANUAL_REFRESH"
  source_fingerprint: "sha256:..."
  task_context_ref: "file:.agentx/context/task_context_packs/....json|null"
  task_skill_ref: "file:.agentx/context/task_skills/....md|null"
  error_code: "string|null"
  error_message: "string|null"
  compiled_at: "timestamp|null"
  retained_until: "timestamp"
  created_at: "timestamp"
  updated_at: "timestamp"
```

说明：
1. `READY` 是 run 启动/恢复前的硬前置条件。
2. 进入 `STALE` 后不可用于新 run；需要重新编译出新的 `READY` 快照。

### 3.6 Task Run + TaskRunEvent

```yaml
task_run:
  run_id: "RUN-..."
  task_id: "TASK-..."
  worker_id: "WRK-..."
  status: "RUNNING | WAITING_FOREMAN | SUCCEEDED | FAILED | CANCELLED"
  run_kind: "IMPL | VERIFY"
  context_snapshot_id: "CTXS-..."
  lease_until: "timestamp"
  last_heartbeat_at: "timestamp"
  started_at: "timestamp"
  finished_at: "timestamp|null"
  task_skill_ref: "git:<commit>:<path>|file:<path>|null"
  toolpacks_snapshot_json: "{}"
  base_commit: "abcdef..."
  branch_name: "run/<run_id>"
  worktree_path: "worktrees/<session_id>/<run_id>/"
  created_at: "timestamp"
  updated_at: "timestamp"
```

```yaml
task_run_event:
  event_id: "EVT-..."
  run_id: "RUN-..."
  event_type: "RUN_STARTED | HEARTBEAT | PROGRESS | NEED_CLARIFICATION | NEED_DECISION | ARTIFACT_LINKED | RUN_FINISHED"
  body: "human readable summary"
  data_json: "{}"                 # optional valid JSON text
  created_at: "timestamp"
```

补充（与 schema v0 对齐）：
1. `task_runs` 不新增 `work_report/delivery_commit` 专用列。
2. `runs/{runId}/finish` 的 `result_status/work_report/delivery_commit/artifact_refs_json` 统一写入 `task_run_events(event_type=RUN_FINISHED).data_json`，重资产内容通过 `ARTIFACT_LINKED` 引用。
3. `task_runs.context_snapshot_id` 必须指向 `status=READY` 的快照；若快照 `STALE/FAILED/PENDING`，run 创建必须被拒绝。

### 3.7 Query Read Models（前端聚合查询）

前端工作台需要一组“页面可直接消费”的聚合查询，而不是只拼底层写接口。

这些读模型当前约定：
1. 仅用于读取，不承载状态推进。
2. 响应字段以 `camelCase` 返回，便于当前前端 demo 直接消费。
3. 语义仍以底层账本为准；例如 `canCompleteSession=false` 时，前端必须以服务端 gate 为准。

最小返回范围：

1. `GET /api/v0/sessions/{sessionId}/progress`
   - session 基本信息
   - requirement 摘要
   - task / ticket / run 计数
   - latest run 摘要
   - delivery 摘要
   - `phase / blockerSummary / primaryAction / canCompleteSession / completionBlockers`
2. `GET /api/v0/sessions/{sessionId}/ticket-inbox`
   - 当前 session 的 ticket 列表
   - latest event 摘要
   - run/task 来源锚点
   - `requestKind / question / needsUserAction`
3. `GET /api/v0/sessions/{sessionId}/task-board`
   - 按 module 分组的 tasks
   - 依赖 task ids
   - latest context snapshot 状态
   - latest run / latest verify / latest delivery 摘要
4. `GET /api/v0/sessions/{sessionId}/run-timeline`
   - 最近 run event 时间线
   - 关联的 task/module/worker
   - `eventType / eventBody / eventDataJson`

---

## 4. Worker 任务包（Task Package）契约（API 下发）

Worker 领到任务后，服务端必须下发一个 Task Package（模块 05 的契约），建议 v0 统一为：

```yaml
task_package:
  run_id: "RUN-..."
  task_id: "TASK-..."
  module_id: "MOD-..."
  context_snapshot_id: "CTXS-..."

  run_kind: "IMPL | VERIFY"
  task_template_id: "tmpl.impl.v0"

  required_toolpacks: ["TP-..."]
  task_skill_ref: "git:<commit>:<path>|file:<path>"
  task_context_ref: "file:.agentx/context/task-context-packs/<task>/<kind>/<snapshot>.json"

  task_context:
    requirement_ref: "req:REQ-...@confirmed_v2"
    architecture_refs: ["adr:ADR-..."]
    prior_run_refs: ["run:RUN-..."]
    repo_baseline_ref: "git:<base_commit>"

  read_scope: ["./"]              # default: whole worktree
  write_scope: ["src/", "pom.xml"] # VERIFY must be []
  verify_commands: ["..."]         # VERIFY must be non-empty

  stop_rules:
    - "遇到缺信息 -> NEED_CLARIFICATION"
    - "遇到取舍/冲突 -> NEED_DECISION"

  expected_outputs:
    - "work_report"
    - "artifact_refs"
    - "delivery_commit (IMPL only)"

  git:
    base_commit: "abcdef..."
    branch_name: "run/<run_id>"
    worktree_path: "worktrees/<session_id>/<run_id>/"
```

硬约束：
1. `run_kind=VERIFY` 时：`write_scope=[]` 且 `verify_commands` 必填（模块 05/07）
2. `repo_baseline_ref` 必须与 run 的 `base_commit` 一致（模块 06）
3. `context_snapshot_id` 必须对应 `task_context_snapshots.status=READY`，且指纹未过期（非 `STALE`）
4. 若下发 `task_context_ref`，它必须指向本次 `context_snapshot_id` 对应快照编译出的 task_context_pack（审计锚点）

---

## 5. API 列表（按角色分组）

说明：
以下仅列 v0 必须的 API（可闭环），不做“未来可能用到”的接口堆砌。

### 5.1 用户/脚本（纯客户端，不含编排逻辑）

1. `POST /api/v0/sessions`：创建 session
2. `GET /api/v0/sessions`：查询历史 session 列表，并返回每个 session 的当前需求文档快照（若存在）
3. `GET /api/v0/sessions/{sessionId}`：加载指定 session 的当前快照（用于“继续该会话”）
4. `POST /api/v0/sessions/{sessionId}/pause|resume|complete`：推进 session 状态
5. `POST /api/v0/sessions/{sessionId}/requirement-docs`：创建需求文档
6. `POST /api/v0/requirement-docs/{docId}/versions`：提交新的需求版本（可由 user 编辑）
7. `PUT /api/v0/requirement-docs/{docId}/content`：前端直接提交完整 markdown 覆盖当前内容（后端按新版本落库）
8. `POST /api/v0/requirement-docs/{docId}/confirm`：用户确认需求版本（触发 ARCH_REVIEW ticket）
9. `GET /api/v0/sessions/{sessionId}/tickets?status=WAITING_USER`：拉取需要用户响应的提请
10. `POST /api/v0/tickets/{ticketId}/events`：用户响应（写 USER_RESPONDED 事件并推进 ticket 状态）
11. `GET /api/v0/sessions/{sessionId}/progress`：读取 session 工作台总览聚合视图
12. `GET /api/v0/sessions/{sessionId}/ticket-inbox`：读取当前 session 的提请收件箱聚合视图
13. `GET /api/v0/sessions/{sessionId}/task-board`：读取按模块分组的任务看板聚合视图
14. `GET /api/v0/sessions/{sessionId}/run-timeline`：读取最近 run 事件时间线聚合视图

说明（v0 进度视图范围）：
1. 用户侧“查看进度”的最小闭环是提请收件箱与关键状态（session/ticket/task 状态）。
2. `progress / ticket-inbox / task-board / run-timeline` 是面向工作台页面的前端 read-model，不替代底层写接口。
3. run 级细粒度执行事件原本主要面向 Agent/总工消费；v0 现已补充 `run-timeline` 作为用户级只读可观测视图，但仍不允许前端直接改写 run 状态。

### 5.2 Agent（需求/架构/上下文处理/总工）

1. `POST /api/v0/sessions/{sessionId}/tickets`：创建工单（HANDOFF/DECISION/CLARIFICATION/ARCH_REVIEW）
2. `POST /api/v0/tickets/{ticketId}/claim`：接单租约（claimed_by + lease）
3. `POST /api/v0/tickets/{ticketId}/events`：追加事件（提请、输出、artifact 链接）
4. `POST /api/v0/sessions/{sessionId}/modules`：创建模块（总工拆模块）
5. `POST /api/v0/modules/{moduleId}/tasks`：创建任务并标注模板与 required_toolpacks（可选 `depends_on_task_ids`）
6. `POST /api/v0/tasks/{taskId}/dependencies`：新增任务依赖（`depends_on_task_id` + `required_upstream_status`）
7. `POST /api/v0/foreman/tasks/{taskId}/merge-gate/start`：进入合并门禁并创建 VERIFY run（模块 06）
8. `GET /api/v0/tasks/{taskId}/context-status`：查看任务上下文编译状态（用于总工判断是否可分配/可恢复）
9. `POST /api/v0/sessions/{sessionId}/requirement-agent/drafts`：需求 Agent 澄清聊天 + 生成/修订 REQ-DOC-v1/REQ-DOC-v1-zh 草稿（可 dry-run）

### 5.3 Worker（执行层）

1. `POST /api/v0/workers/{workerId}/claim`：领任务（Worker Pull；可能返回 204 表示无任务）
2. `POST /api/v0/runs/{runId}/heartbeat`：心跳续租（更新 lease）
3. `POST /api/v0/runs/{runId}/events`：追加进度/阻塞/产物引用（NEED_* 等；NEED_* 只负责触发 WAITING_FOREMAN，不直接面向用户）
4. `POST /api/v0/runs/{runId}/finish`：提交工作报告并结束 run（成功/失败/取消）

### 5.4 Context Processor（编译上下文包与 task_skill）

1. `POST /api/v0/context/role-pack:compile`
2. `POST /api/v0/context/task-context-pack:compile`
3. `POST /api/v0/context/task-skill:compile`

说明：
1. 这些接口可由控制面内部调用，也可暴露给 Agent（用于可观测与调试）
2. 上下文处理 Agent 禁止创造新事实；发现冲突只能通过 tickets 提请（模块 08）
3. 编译接口必须写入 `task_context_snapshots` 状态链（`PENDING/COMPILING/READY/FAILED/STALE`）
4. 保留策略：快照元数据长期保留；正文产物在 session `COMPLETED` 后按保留期归档/清理（模块 08）

### 5.5 控制面自动化（内部运维入口）

1. `POST /api/v0/workforce/auto-provision`：扫描 `WAITING_WORKER` 任务并自动补员（可选 `max_tasks`）。
2. `POST /api/v0/execution/lease-recovery`：回收心跳过期 run（可选 `max_runs`）。
3. `POST /api/v0/workforce/runtime/auto-run`：触发一次“READY worker 自动 claim + 执行 + 回写”（可选 `max_workers`）。
4. `POST /api/v0/workforce/cleanup`：执行一次 worker 池清理（可选 `max_disable`，仅对 `READY` 且非 active run worker 生效）。
5. `GET /api/v0/runtime/llm-config`、`POST /api/v0/runtime/llm-config:test`、`POST /api/v0/runtime/llm-config:apply`：运行时 LLM 配置读取/连通性测试/应用（应用后无需重启）。

说明：
1. 这些接口主要用于控制面调度器与运维脚本触发，不是终端用户入口。
2. v0 默认仍建议由调度器驱动（`WorkerAutoProvisionScheduler/RunLeaseWatchdogScheduler/WorkerRuntimeScheduler`），手工调用用于排障与灰度。
3. 清理策略必须满足：绝不清理 `RUNNING/WAITING_FOREMAN` 正在执行中的 worker，仅允许将候选 worker 状态切到 `DISABLED`（不直接物理删除）。
4. 运行时 LLM 配置默认 `mock`，仅在用户显式应用配置后切换到真实 LLM（例如 `bailian`）。

---

## 6. 关键流程（接口如何闭环）

### P0：Bootstrap INIT 独占（解锁点 = DONE）

1. Session 创建后，系统自动创建 `bootstrap` 模块与唯一 INIT 任务（`tmpl.init.v0`）
2. Worker 通过 `POST /workers/{id}/claim` 领取 INIT run 并执行
3. INIT run `SUCCEEDED` -> task 进入 `DELIVERED`
4. 总工调用 `POST /foreman/tasks/{taskId}/merge-gate/start`，触发 VERIFY run
5. VERIFY run `SUCCEEDED` 时，服务端尝试 fast-forward `main` 并将 INIT task 置为 `DONE`
6. INIT task `DONE` 才允许系统解除独占（模块 06）

### P1：需求确认触发 ARCH_REVIEW

1. 用户 `POST /requirement-docs/{docId}/confirm`
2. 服务端更新 `confirmed_version` 并自动创建 `ARCH_REVIEW` ticket（assignee_role=architect_agent）
3. 架构师 claim ticket 并处理；必要时发起 DECISION/CLARIFICATION

### P2：实现任务交付 -> 门禁验证 -> DONE

1. 总工创建 IMPL task（`tmpl.impl.v0`），系统评估进入 READY_FOR_ASSIGN
2. Worker claim 前，控制面确认该 task 存在最新 `READY` 快照；否则拒绝分配并要求先编译上下文
3. Worker claim 获得 run 与 worktree（run 绑定 `context_snapshot_id`）
4. Worker 执行并 `finish(SUCCEEDED)`，报告中包含 `delivery_commit`
5. 服务端将 task 标为 `DELIVERED` 并更新 `task/<task_id>` 到交付候选
6. 服务端在 `DELIVERED` 后立即触发 merge gate start（若车道繁忙则后续由 GC 兜底重试）
7. VERIFY 通过后服务端快进 main，并 `DELIVERED -> DONE`

---

## 7. 与 OpenAPI 同步的要求（防止两套文档分叉）

1. OpenAPI 的 schema 必须来自本模块第 3/4 节对象契约
2. 任何新增接口必须同时更新：
   - `docs/09-control-plane-api-contract.md`
   - `docs/openapi/agentx-control-plane.v0.yaml`
3. 如两者冲突，以本文档为准（因为本文档承载状态机语义与触发条件）

