# 模块 05：Worker 工作与总工监控（任务下发、心跳/租约、进度事件、阻塞提请）

更新时间：2026-02-20

范围说明：
本模块在模块 04（Worker 池/工具包池/任务标注/Worker 创建）的基础上，补齐两件事：
1. Worker 具体如何“接任务-执行-汇报”（不讨论并发调度算法，但讨论执行生命周期）
2. 架构师（兼任总工）如何监控 Worker 的工作状态，并在必要时发起提请/阻断/重试

本模块的目标是让 Worker 的工作变成“可观察、可控、可追溯”的工业化流程，而不是黑盒。

术语引用：
1. 工单/提请：见 `docs/03-project-design-module.md`
2. Worker/Toolpack/Task/Module：见 `docs/04-foreman-worker-module.md`

非目标（明确排除，避免跑偏）：
1. 不讨论“如何把任务拆得更好”（拆分属于总工/架构模块）
2. 不讨论“如何并发调度多个 Worker”（调度策略后续单独模块）
3. 不讨论“如何合并交付/CI/CD”（后续模块）

---

## 1. 核心结论（先说清楚）

1. Worker 工作不是靠“长连接”，而是靠“租约（lease）+ 心跳（heartbeat）+ 事件链（events）”来保持可控
2. 总工监控不是盯日志，而是盯“任务运行状态 + 事件链 + 阻塞点（需要提请/补信息）”
3. Worker 不能直接向用户提问，所有阻塞必须通过总工转成工单/提请（Decision Surface）

---

## 2. Worker 的工作协议（Execution Contract）

### 2.1 Worker 运行一个任务需要的输入（Task Package）

当一个任务要交给 Worker 执行时，总工/系统必须提供一个“任务包”，最小包含：
1. `task_id`：任务标识（来自 work_tasks）
2. `module_id`：所属模块（用于后续模块级测试/验收归档）
3. `run_kind`：run 类型（`IMPL` | `VERIFY`，用于 DoD 门禁与权限约束，详见模块 06/07）
4. `context_snapshot_id`：上下文快照标识（`task_context_snapshots.status=READY`，运行审计锚点）
5. `required_toolpacks`：最小工具包集合（硬约束）
6. `task_skill`（或引用）：任务专用 skill（来自模块 04 的动态拼接流程，回答“怎么做”）
7. `task_context`（或引用）：任务上下文（回答“你要基于哪些事实与产物来做”）
8. `stop_rules`：停止与阻断规则（例如缺信息就停止并上报，不允许脑补）
9. `expected_outputs`：期望产物类型（例如：变更清单、执行命令清单、验证结果、风险提示）
10. `read_scope`：允许读取的文件范围（默认整个 worktree；若需收缩应由总工显式指定）
11. `write_scope`：允许写入的文件范围（路径前缀列表；`run_kind=VERIFY` 时必须为空）
12. `task_template_id`：任务交互模板标识（用于把“怎么交付/怎么验收/怎么停”标准化，见 2.3）
13. `verify_commands`（或引用）：验证命令清单（`run_kind=VERIFY` 时为必填；IMPL 时可作为自检）

关于 `task_context` 的设计说明（为什么需要，但不把它塞成一坨大文本）：
1. `task_context` 必须存在，否则 Worker 很容易“凭感觉补全”架构与约束，导致不可控漂移
2. `task_context` 推荐是“引用（refs）为主”，而不是把所有上下文直接内嵌进任务包
   - 原因：避免重复、避免超长上下文、保证可追溯（同一份事实只存一处）
3. `task_context` 的最小形态建议包含：
   - `requirement_ref`：需求文档确认版本引用（doc_id + confirmed_version）
   - `architecture_refs`：架构规格/ADR 的引用列表（仅引用，不展开细节）
   - `module_refs`：模块说明/接口约束（如有）
   - `prior_run_refs`：历史 run 的引用（如果是重试/增量）
   - `repo_baseline_ref`：代码基线引用（若进入实现阶段，可为 git commit；当前阶段可预留为空）
4. Worker 只允许把 `task_context` 引用的内容当作“事实来源”；如果发现缺口，必须上报 `NEED_CLARIFICATION/NEED_DECISION`，不允许脑补
5. `task_context/task_skill` 必须来自同一个 `context_snapshot_id`；不允许“旧 context + 新 skill”混搭

说明：
此处的关键是把“怎么做”与“能不能做”分离：
1. `task_skill` 负责“怎么做”
2. `required_toolpacks` 负责“能不能做/用什么做”

### 2.2 Worker 的最小输出（Work Report）

Worker 完成任务后必须输出“工作报告”，用于审计与后续角色消费，最小包含：
1. 做了什么：任务目标是否完成（yes/no/partial）
2. 改了哪些：产物引用（例如变更摘要、生成的文档/文件索引）
3. 运行了什么：执行过的命令清单与结果摘要（不要求全量日志都塞进数据库）
4. 验证了什么：运行了哪些验证步骤、结论是什么
5. 卡在哪里：如果失败/阻塞，明确失败原因与下一步需要的决策/信息
6. 交付到哪：若涉及 Git 交付，必须给出交付候选 commit（`delivery_commit`），用于后续 VERIFY 与合并门禁（见 `docs/06-git-worktree-workflow.md` 与 `docs/07-definition-of-done.md`）

### 2.3 常用任务交互模板库（v0）

目标：
把“任务怎么下发/怎么停/怎么交付/怎么验收”标准化成模板，减少 Worker 自由发挥空间。

模板与其它概念的关系（避免混淆）：
1. Toolpacks：硬约束，回答“能不能做/允许执行什么命令”
2. Skill：软指导，回答“怎么做更稳/更符合该技术栈”
3. Task Template：交互与证据约束，回答“怎么交付才算数/什么情况下必须停”

下面给 v0 的六个模板（先覆盖个人项目快速落地的常见场景；后续按需要增量）：

#### T0：INIT（初始化/脚手架）模板

模板标识：`tmpl.init.v0`  
run_kind：`IMPL`  
适用场景：
1. 新项目初始化：按技术栈生成/调整脚手架文件（允许操作根目录）
2. 把仓库推进到“后续可以进入并发开发”的基线状态（模块 06 的 Bootstrap 规则）
3. v0 约束：一个 session **只允许一个** INIT 任务（初始化完成后不再重复发 INIT）

任务包补充要求：
1. `write_scope` 允许覆盖仓库根目录（例如 `/` 或项目根路径前缀）
2. `expected_outputs` 必须包含：初始化产物清单、可复现的构建/验证命令清单、`delivery_commit`
3. `verify_commands` 必须给出最小可用的一组（例如 build + 最小测试/health check），作为后续 DoD 的默认基线

权限收敛规则（写死，防止“初始化权限常态化”）：
1. INIT 任务结束并确认后，后续所有任务默认禁止 `write_scope=/`
2. 如果后续确实要改根目录（例如构建工具迁移），必须触发 `NEED_DECISION`，由总工提请用户明确放行（模块 03/05）

停止规则：
1. 技术栈关键选择未确定（例如版本、构建工具、数据库类型）：触发 `NEED_DECISION`（由总工提请用户决策）
2. 初始化过程中发现“需求/约束不明确”：触发 `NEED_CLARIFICATION`
3. 初始化需要扩大外部权限（网络/系统依赖等）：触发 `NEED_DECISION`（让总工决定是否放行）

完成判定（INIT run 是否可 SUCCEEDED）：
1. 工作报告包含 `delivery_commit`
2. 工作报告包含一组可复制执行的 `verify_commands` 及关键输出摘要
3. 明确记录初始化时做过的关键取舍（例如版本选择），避免后续漂移

补充：INIT 的“解锁点”
1. INIT run `SUCCEEDED` 只代表“初始化交付候选已产生”
2. Session 从独占模式解锁的条件写死为：INIT 任务进入 `DONE`（模块 06），也就是该候选已通过 VERIFY + 合并门禁并进入 `main`

#### T1：IMPL（实现/改动）模板

模板标识：`tmpl.impl.v0`  
run_kind：`IMPL`  
适用场景：
1. 新增功能、修改逻辑、调整结构
2. “需要写文件”的任务（非纯验证）

任务包补充要求（除 2.1 的最小字段外）：
1. `write_scope` 必须明确（至少包含一个路径前缀），避免“顺手改全仓库”
2. `expected_outputs` 必须包含：变更摘要、命令清单、自检结论、`delivery_commit`

停止规则（必须触发 NEED_*，不允许脑补）：
1. 发现需求/约束冲突：触发 `NEED_DECISION`
2. 发现上下文缺口（缺接口/缺字段/缺依赖信息）：触发 `NEED_CLARIFICATION`
3. 发现 write_scope 不足以完成任务：触发 `NEED_DECISION`（申请扩大写入范围或拆分任务）

完成判定（IMPL run 是否可 SUCCEEDED）：
1. 工作报告包含 `delivery_commit`
2. 工作报告包含至少一次自检（可复用 `verify_commands` 或最小 smoke 验证）

#### T2：VERIFY（只读验证/验收）模板

模板标识：`tmpl.verify.v0`  
run_kind：`VERIFY`  
适用场景：
1. DoD 要求的独立验证（模块 07）
2. 合并门禁（模块 06）中的“验证最终可合并候选（merge candidate）”

任务包补充要求：
1. `write_scope` 必须为空（只读）
2. `base_commit` 必须绑定 merge candidate（不是 `main` HEAD，也不是旧的 `delivery_commit`）
3. `verify_commands` 必填（必须可复制粘贴执行）

停止规则：
1. 任何文件变更（例如检测到 diff 非空）：立即失败并上报（不要尝试“顺手修一下”）
2. 验证命令不明确/无法执行：触发 `NEED_CLARIFICATION`

完成判定（VERIFY run 是否可 SUCCEEDED）：
1. 工作报告包含：验证目标 commit、命令清单、关键输出摘要、结论（pass/fail）
2. 验证 run 结束时目录无变更（只读约束成立）

#### T3：BUGFIX（缺陷修复）模板

模板标识：`tmpl.bugfix.v0`  
run_kind：`IMPL`（BUGFIX 是任务语义，run 仍属于可写实现）  
适用场景：
1. 明确的 bug 修复（已有复现或可补复现）

任务包补充要求：
1. `expected_outputs` 必须包含：复现证据 + 修复证据
2. `task_context` 必须包含：bug 现象描述 + 复现输入/步骤（若缺则先走 CLARIFICATION）

停止规则：
1. 无法复现且缺少关键输入：`NEED_CLARIFICATION`
2. 复现方式存在多种且会影响修复方向：`NEED_DECISION`

完成判定：
1. 工作报告必须包含“修复前失败证据”（测试失败/脚本输出/日志摘要之一）
2. 工作报告必须包含“修复后通过证据”（同一复现路径通过）
3. 产出 `delivery_commit`

#### T4：REFACTOR（重构不改行为）模板

模板标识：`tmpl.refactor.v0`  
run_kind：`IMPL`  
适用场景：
1. 代码结构调整、命名整理、拆分模块
2. 目标是“行为不变”，只提升可维护性

任务包补充要求：
1. `expected_outputs` 必须包含：行为未变的证据（至少要求后续有 VERIFY run）
2. `write_scope` 需要更严格（尽量限制在目标模块目录）

停止规则：
1. 若重构需要引入行为变化（例如改接口契约）：`NEED_DECISION`（升级为 IMPL 需求变更）

完成判定：
1. 产出 `delivery_commit`
2. 工作报告明确“重构目的/范围/风险点”

#### T5：TEST（补测试/测试建设）模板

模板标识：`tmpl.test.v0`  
run_kind：`IMPL`（写测试本质仍是写入任务）  
适用场景：
1. 补单元测试、集成测试、回归测试脚本
2. 为未来 DoD 提供更强的验证武器

任务包补充要求：
1. `write_scope` 只允许测试目录（例如 `src/test/`，具体由技术栈 skill 给出）
2. `expected_outputs` 必须包含：新增测试清单 + 如何运行（verify_commands 或引用）

停止规则：
1. 无法确定被测行为/边界：`NEED_CLARIFICATION`

完成判定：
1. 新增测试可重复运行（同一 commit 多次运行结论一致）
2. 工作报告包含：新增测试说明、运行方式、覆盖范围与已知缺口

---

## 3. 租约与心跳（不靠长连接也能“持续连接”）

### 3.1 为什么需要租约（Lease）

没有租约，系统会遇到两个工业级问题：
1. Worker 卡死但任务永远显示“在跑”（不可恢复）
2. 重试/重新分配时可能出现“两个 Worker 同时干同一个任务”（不可控）

租约的作用是：让“任务归属”有一个时间边界，过期就允许系统回收并重新分配。

### 3.2 心跳（Heartbeat）怎么用

规则（最小版）：
1. Worker 在执行任务期间必须按固定周期发送心跳
2. 每次心跳会延长该任务运行的租约（lease_until）
3. 若超过阈值未收到心跳：
   - 控制面将该 Worker 视为 `UNHEALTHY`（派生健康态，不强制写入 `workers.status`）
   - 该任务运行标记为“租约过期”，允许总工触发重试/重新分配

---

## 4. 状态与触发（只保留最小集合）

### 4.1 Worker 状态（与 schema 对齐）

在模块 04 + `schema_v0` 下，`workers.status` 只保留最小三态：
1. `PROVISIONING`：Worker 创建后，环境/工具包准备中
2. `READY`：可接单
3. `DISABLED`：被禁用/回收

运行期可观测态（派生，不单独写入 `workers.status`）：
1. `BUSY`：存在 active run（例如 `task_runs.status` 为 `RUNNING/WAITING_FOREMAN` 且租约有效）
2. `UNHEALTHY`：心跳超时/租约回收/连续失败后，控制面判定“暂不可信”

触发（最小规则）：
1. 创建 Worker -> `PROVISIONING`
2. 能力准备 + 自检通过 -> `READY`
3. 人工/策略禁用 -> `DISABLED`
4. `BUSY/UNHEALTHY` 由 run 事件与租约判定派生，不要求更新 `workers.status`

说明：
1. 不要为“下载依赖中/编译中/测试中”新增 `workers.status`，用事件记录即可。
2. 监控面应同时展示“持久状态（workers.status）+ 派生健康/忙闲态（来自 runs）”。

### 4.2 任务运行状态（把“计划状态”和“运行状态”分离）

模块 04 的 `work_tasks.status` 解决“是否可接单”，但无法表达运行期的真实进度。
为了避免在 `work_tasks.status` 上堆一堆状态，本模块引入“任务运行（task_run）”作为单独对象。

任务运行状态（最小集合）：
1. `RUNNING`：Worker 正在执行
2. `WAITING_FOREMAN`：Worker 遇到阻塞，需要总工介入（转提请/补信息/调整工具包等）
3. `SUCCEEDED`：完成且报告已提交
4. `FAILED`：失败且报告已提交
5. `CANCELLED`：被总工/系统取消

触发：
1. 分配并启动 -> `RUNNING`
2. 发现缺信息/需要取舍 -> 上报阻塞事件 -> `WAITING_FOREMAN`
3. 收到总工的继续指令或提请结果 -> 回到 `RUNNING`（或直接创建新 run 重试）
4. 完成 -> `SUCCEEDED`
5. 明确失败 -> `FAILED`
6. 被取消 -> `CANCELLED`

状态膨胀控制：
1. 运行态只表达“下一步谁能推进”（Worker 继续跑 / 总工介入 / 已结束）
2. 其它细节用事件链表达（progress/log/artifact）

---

## 5. 事件链（总工如何监控，不靠猜）

思路：
监控靠“结构化事件”，日志只是事件的附件或引用。

建议的最小事件类型（足够用，不要做大全）：
1. `RUN_STARTED`：run 启动
2. `HEARTBEAT`：心跳（可带执行摘要）
3. `PROGRESS`：进度更新（例如完成某一步）
4. `NEED_CLARIFICATION`：缺信息，需要用户补充（总工转成 CLARIFICATION 工单）
5. `NEED_DECISION`：需要取舍（总工转成 DECISION 工单）
6. `ARTIFACT_LINKED`：产物已生成（报告/变更摘要/日志引用/测试结果引用）
7. `RUN_FINISHED`：结束（成功/失败/取消）

事件设计的硬规则：
1. Worker 只上报事实与需求，不上报“自作主张的决策”
2. 任何 `NEED_*` 事件必须让 run 进入 `WAITING_FOREMAN`（防止 Worker 在不确定处继续乱跑）

---

## 6. 总工如何监控与介入（Control Plane）

总工的监控动作应是“面向 run 的”，最小包含：
1. 查看队列：哪些任务可接单、哪些在等待 Worker、哪些 run 在跑、哪些 run 在等总工
2. 查看健康：哪些 Worker 心跳超时/自检失败
3. 查看阻塞：哪些 run 发出了 `NEED_DECISION`/`NEED_CLARIFICATION`

总工的介入动作（不写代码，但要定义动作语义）：
1. `Triage NEED_*`：先由总工判定是否可在现有约束内内部闭环（补上下文、修正任务标注、调整工具包、明确执行指令）
2. `Create Ticket`：仅当总工无法内部闭环时，才把 `NEED_*` 转成对应工单（DECISION/CLARIFICATION）进入用户决策面
3. `Resume Run`：总工完成判定（内部闭环或用户已响应）后，允许 Worker 继续（可在原 run 继续或新建 run 重试）
4. `Cancel Run`：当风险不可接受或方向错误时立即停止
5. `Adjust Toolpacks`：若发现任务标注错误/过宽过窄，回到模块 04 的任务标注规则，更新 required_toolpacks（必要时提请）
6. `Provision Worker`：当长期 WAITING_WORKER，触发创建新 Worker 或扩容提请

补充保护规则（2026-03-09 起生效）：
1. 若同一任务连续多次出现“planner 连续两轮都没有产出真实文件差异”的 `NEED_CLARIFICATION`，控制面不再无限重复创建澄清工单
2. 默认策略是：超过 3 次后，自动封口当前 no-op `CLARIFICATION`，并升级为 `ARCH_REVIEW`
3. 该 `ARCH_REVIEW` 的目标不是问用户补信息，而是要求架构师回看“已完成/未完成/任务粒度/依赖关系”，重新拆分并重新编排，避免 Worker 在宽泛任务上空转
4. 这类升级仍然走工单事件链，可审计、可回放，不引入新的 ticket type

---

## 7. 最小数据结构草案（仅为表达监控与审计）

说明：
这里给最小 DDL 草案用于表达“run + events + lease/heartbeat”。
不追求一次设计到位，后续按需要增量字段即可。

```sql
-- 任务运行：一次任务的一个执行尝试（重试/重新分配 = 新 run）
create table task_runs (
  run_id           varchar(64) primary key,
  task_id          varchar(64) not null,
  worker_id        varchar(64) not null,
  status           varchar(32) not null,  -- RUNNING | WAITING_FOREMAN | SUCCEEDED | FAILED | CANCELLED
  run_kind         varchar(32) not null,  -- IMPL | VERIFY（用于 DoD 门禁，详见模块 06/07）
  context_snapshot_id varchar(64) not null, -- 绑定最新 READY 上下文快照（模块 08）

  lease_until      timestamp not null,
  last_heartbeat_at timestamp not null,

  started_at       timestamp not null,
  finished_at      timestamp null,

  task_skill_ref   varchar(256) null,     -- 引用 task_skill.md 或其产物索引
  toolpacks_snapshot_json text not null,  -- run 开始时的工具包快照（审计用）

  created_at       timestamp not null,
  updated_at       timestamp not null
);

-- 运行事件链：进度/心跳/阻塞/产物引用
create table task_run_events (
  event_id         varchar(64) primary key,
  run_id           varchar(64) not null,
  event_type       varchar(64) not null,  -- RUN_STARTED | HEARTBEAT | PROGRESS | NEED_* | ARTIFACT_LINKED | RUN_FINISHED
  body             text not null,
  data_json        text null,
  created_at       timestamp not null
);
```

补充：
模块 06 会为 `task_runs` 补齐 Git 审计字段（例如 `base_commit/branch_name/worktree_path`），用于并发隔离与可追溯基线。
模块 08 会补齐上下文快照锚点（`context_snapshot_id` 与 `task_context_snapshots` 状态链），用于“启动前可观测 + 防半跑”。

关于“提请”与 run 的关系：
1. 当 run 触发 `NEED_DECISION/NEED_CLARIFICATION`，总工创建相应工单（模块 03）
2. run 记录与工单的关联可通过 `ARTIFACT_LINKED` 事件携带 ticket_id（不强制新增字段）

---

## 8. 交互场景（总工-Worker，含监控与阻塞）

### W1：任务可接单，创建 run 并下发任务包

触发：任务处于 `READY_FOR_ASSIGN` 且存在 `READY` Worker  
动作：
1. 原子分配：将 `work_tasks.status` 从 `READY_FOR_ASSIGN` 置为 `ASSIGNED`，并写入 `active_run_id=run_id`（并发语义见 `docs/06-git-worktree-workflow.md`）
2. 创建 `task_runs(status=RUNNING, lease_until=now+ttl, last_heartbeat_at=now)`
3. Worker 运行态可观测为 `BUSY`（派生，不强制更新 `workers.status`）
4. 写入 `task_run_events(RUN_STARTED)`

### W2：Worker 执行中持续心跳与进度上报

触发：run=RUNNING  
动作：
1. 周期性写入 `task_run_events(HEARTBEAT/PROGRESS)`
2. 同步更新 `task_runs.last_heartbeat_at` 与 `lease_until`

### W3：Worker 遇到缺信息或需要取舍（阻塞）

触发：Worker 发现无法在不脑补的情况下继续  
动作：
1. 写入 `task_run_events(NEED_CLARIFICATION|NEED_DECISION)`
2. 将 `task_runs.status` 置为 `WAITING_FOREMAN`
3. 总工先做 triage：能否在不改变价值取向/架构约束的前提下内部闭环
4. 若 triage 仍无法闭环，再创建对应工单/提请（模块 03）

### W4：总工完成判定后，恢复执行

触发：总工 triage 已完成（内部闭环）或对应工单/提请已完成  
动作（两种选择）：
1. 原 run 继续：仅当上下文事实指纹未变化（仍可复用原 `context_snapshot_id`）时，写入事件并将 status 回到 RUNNING
2. 新 run 重试：若 triage/用户响应引入了新事实，取消旧 run，先重编译得到新的 `READY` 快照，再创建新 run（绑定新 `context_snapshot_id`）

### W5：Worker 完成或失败，上交工作报告

触发：任务结束  
动作：
1. 写入 `ARTIFACT_LINKED`（链接工作报告/验证结果等）
2. 写入 `RUN_FINISHED`
3. `task_runs.status` -> `SUCCEEDED/FAILED/CANCELLED`
4. 若无其它 active run，Worker 运行态恢复为 `READY`（派生）
5. `work_tasks.status` 的推进不在本模块定义（避免把“计划态/交付门禁”塞进 run 生命周期），见 `docs/06-git-worktree-workflow.md` 与 `docs/07-definition-of-done.md`

### W6：心跳超时，总工判定 Worker 不健康并触发重试

触发：`now - last_heartbeat_at > threshold`  
动作：
1. 控制面判定该 Worker `UNHEALTHY`（派生健康态，依据心跳/租约）
2. run 标记为“租约过期”（可通过事件记录）
3. 总工创建新 run 并重新分配（是否重试/是否提请取决于策略，后续模块再定）
