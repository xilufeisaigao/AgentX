# 模块 07：Definition of Done（DoD）与交付门禁

更新时间：2026-02-20

范围说明：
本模块定义 AgentX 的 DoD v0（Definition of Done，完成定义）与最小交付门禁，用于解决两个现实问题：
1. “完成”如果只靠 Worker 自述，在 AI 场景下不可控且不可审计
2. `task_run.SUCCEEDED` 只是一次执行尝试结束，不等价于“已交付/已集成/可用”

本模块会把“完成”拆成两个层级：
1. Run Done：什么时候可以把一次 `task_run` 标成 `SUCCEEDED`
2. Task Done：什么时候可以把一条 `work_tasks` 记录标成 `DONE`

术语引用：
1. 工单/提请（DECISION/CLARIFICATION）：见 `docs/03-project-design-module.md`
2. Worker 执行协议与工作报告（Work Report）：见 `docs/05-worker-execution-and-monitoring.md`
3. 并发领任务、Git 基线、合并门禁、`DELIVERED/DONE` 语义：见 `docs/06-git-worktree-workflow.md`

非目标（明确排除，避免跑偏）：
1. 不定义具体语言/框架的代码规范（留给技术栈工具包与 skill）
2. 不定义具体 CI/CD 工具与流水线编排（只定义门禁语义与证据要求）
3. 不把 DoD 设计成“万能清单”；只保留能显著提升可控性与可追溯性的硬门槛

---

## 1. 核心结论（写死的底线）

1. DoD 必须可被验证（有证据链），不能是“感觉写完了”
2. `task_run.SUCCEEDED` 只表示“本次 run 有产出”，不表示任务已交付到主线
3. `work_tasks.status=DONE` 必须延后到“独立验证通过 + 合并门禁成功”之后
4. 系统必须强制一个只读验证步骤（VERIFY run），用于打破“自证完成”的闭环
5. VERIFY 必须验证“最终可合并候选”（merge candidate），而不是验证某个旧 commit 后再 rebase（否则会出现“验证通过但最终合入代码没被验证”的漏洞）

---

## 2. Done 的两个层级：Run Done vs Task Done

### 2.1 Run Done（什么时候 `task_run` 可以是 SUCCEEDED）

对任意 `task_run`，要标记为 `SUCCEEDED`，必须满足：
1. 无阻塞未结：不存在未处理的 `NEED_CLARIFICATION/NEED_DECISION`（否则 run 必须停在 `WAITING_FOREMAN`）
2. 有工作报告：必须产出 Work Report（模块 05 的最小输出结构）
3. 有上下文锚点：run 必须绑定 `context_snapshot_id`，且该快照在 run 启动时为 `READY`
4. 有可追溯基线：run 必须绑定 `base_commit`，并与 `task_context.repo_baseline_ref` 一致（模块 06）
5. 有可重放证据：报告中必须包含执行过的命令清单与结果摘要（不要求全量日志入库，但必须有引用或摘要）

对不同 `run_kind` 的补充要求：
1. `run_kind=IMPL`：工作报告中必须明确“交付候选 commit”（`delivery_commit`），用于后续验证与集成门禁
2. `run_kind=VERIFY`：工作报告必须明确验证目标（被验证的 commit），并给出验证结论与关键输出摘要

持久化约定（与 schema v0 对齐）：
1. v0 不在 `task_runs` 增加 `work_report/delivery_commit` 专用列。
2. `POST /api/v0/runs/{runId}/finish` 的 `result_status/work_report/delivery_commit/artifact_refs_json` 统一写入 `task_run_events(event_type=RUN_FINISHED).data_json`。
3. 大体量报告正文与附件通过 `ARTIFACT_LINKED` 事件记录 `artifact_ref`（例如 `git:<commit>:<path>`）。

说明：
1. 这里的 `delivery_commit` 是工程化必要字段，它让后续验证与集成不依赖“猜测 Worker 当时到底跑在哪个版本”
2. `delivery_commit` 不要求已经合入主线，它只是一个可审计的中间产物
3. 合并门禁可能会对交付分支执行 rebase，从而产生新的 merge candidate；DoD 要求 VERIFY 绑定 merge candidate（见第 3 节）

### 2.2 Task Done（什么时候 `work_tasks.status` 可以是 DONE）

在本系统里，`work_tasks.status=DONE` 的含义定义为：
1. 已满足 DoD v0 的证据要求
2. 已通过合并门禁并进入主线（`main`）

因此，`work_tasks.status` 的关键语义（与模块 06 对齐）：
1. `ASSIGNED`：存在 active run（并发占用态）
2. `DELIVERED`：已有交付候选产物，但尚未满足 DoD（仍需独立验证与合并门禁）
3. `DONE`：独立验证通过 + 合并门禁成功（最终集成完成）

`DELIVERED -> DONE` 的最小 DoD v0 门槛：
1. 存在至少一个 `run_kind=IMPL` 的 `SUCCEEDED` run，并产出 `delivery_commit`
2. 存在至少一个 `run_kind=VERIFY` 的 `SUCCEEDED` run，且其 `base_commit` 绑定到“最终可合并候选”（merge candidate）
3. 合并门禁成功：rebase 到 `main` HEAD（得到 merge candidate）-> VERIFY 通过 -> merge 成功（模块 06 第 6 节）

---

## 3. 独立验证（VERIFY run）的约束（防止自证完成）

### 3.1 为什么必须有 VERIFY run

原因（与人类团队不同，AI 场景更容易触发）：
1. Worker 很容易把“没跑过的命令”当作“应该能跑过”
2. Worker 很容易把“跑过一次”当作“可复现的结论”
3. Worker 写测试与写实现若由同一上下文驱动，容易产生“顺着实现写的测试”，覆盖不足

VERIFY run 的定位是：把“交付候选”变成“可验证事实”。

### 3.2 VERIFY run 的硬约束（必须满足）

1. 必须是只读验证：不允许产生任何文件变更（以 `git diff --name-only` 为空作为验收条件）
2. 必须绑定验证目标：`base_commit` 必须等于“最终可合并候选”（merge candidate），不能取 `main` HEAD
3. 必须产出可审计报告：命令清单、关键输出摘要、结论

### 3.3 “独立”的最低实现（不增加新角色也能做到）

独立验证的最低要求不是“换一个团队”，而是：
1. 换一个 run（`run_id` 不同）
2. 只读权限（写入范围为空）
3. 绑定明确的 commit

如果资源允许，优先策略：
1. VERIFY run 尽量分配给不同的 Worker（降低同一上下文偏差）
2. 若确实只能同一 Worker 执行，也必须以新 run 的方式执行 VERIFY（不能在 IMPL run 里“顺手跑一下就算”）

---

## 4. DoD v0：按任务类型给出最小清单（总工可直接用来派活）

说明：
这里的“任务类型”是面向总工拆分与门禁的语义分类，不要求你现在落到数据库字段。

### 4.1 IMPL（实现类任务）

完成门槛：
1. IMPL run 满足 Run Done，且产出 `delivery_commit`
2. 交付范围可审计：变更文件列表与变更摘要可追溯到 `delivery_commit`
3. 必须触发一次 VERIFY run（本模块第 3 节）
4. 合并门禁成功后才允许把任务置为 `DONE`

### 4.2 BUGFIX（缺陷修复任务）

完成门槛：
1. 必须先复现：提供可重复的失败证据（测试或脚本步骤）
2. 修复后必须验证：VERIFY run 必须覆盖复现路径与最小回归路径
3. 合并门禁成功后才允许 `DONE`

### 4.3 REFACTOR（重构任务）

完成门槛：
1. 必须提供“行为未变”的证据：至少一次 VERIFY run（回归测试/特征化测试/脚本验证）
2. 若重构引入了对外行为变化，必须升级为 IMPL（并走提请/需求约束流程）

### 4.4 TEST（补测试/测试建设任务）

完成门槛：
1. 测试必须可重复运行（同一 commit 多次运行结论一致）
2. 必须给出“怎么跑”的命令与范围说明（否则后续 VERIFY 无法工业化）
3. 不要求测试必然覆盖所有路径，但必须明确覆盖边界与缺口（避免虚假安全感）

### 4.5 VERIFY（验证任务）

完成门槛：
1. 必须只读：不产生文件变更
2. 必须绑定明确 commit
3. 必须产出可审计报告（命令、关键输出、结论）

---

## 5. DoD 未通过时怎么处理（避免“带病 DONE”）

1. VERIFY 失败：不允许进入 `DONE`；必须通过新任务修复（BUGFIX/IMPL/补测试）后再重新触发 VERIFY
2. 合并门禁失败（冲突/不满足约束）：不允许进入 `DONE`；可创建“冲突修复任务”或回收原任务继续修改，见模块 06 的 C9
3. 需要取舍/补信息：只能通过 `DECISION/CLARIFICATION` 工单进入用户决策面（模块 03），不允许 Worker 自行决定

---

## 6. 交付规范（v0，面向“用户 clone 仓库即可交付”）

本节回答你最后确认的交付思路：最终交付就是把对应的 Git 仓库给用户，用户本地 `git clone` 即可开始维护。

### 6.1 “交付对象”写死定义

对某个 session 的最终交付对象定义为：
1. 一个可 clone 的 Git 仓库（包含代码 + 必要的文档与脚本）
2. 一个可追溯的版本锚点（commit 或 tag），用于告诉用户“你拿到的是哪个版本”

系统约束（为了可复现与可审计）：
1. 交付锚点必须指向 `main` 上的 commit（因为 DoD 的 `DONE` 已写死要求“最终进入主线”）
2. 用户如果要回滚/复盘，只需要切换到该 tag/commit 即可

### 6.2 最小发布标识（Tag 约定）

为避免“只靠口头说哪个 commit”的混乱，采用最小且不引入复杂版本管理的约定：
1. 每次“对外交付”由总工在 `main` HEAD 打一个**带注释**的 tag
2. tag 命名写死为：`delivery/<YYYYMMDD-HHmm>`（例如 `delivery/20260220-1530`）

说明：
1. v0 不引入语义化版本（SemVer）与自动版本递增，避免无意义工作
2. 如果未来需要版本体系，再单独增量模块，不在 DoD v0 里扩展

### 6.3 证据与产物存放（不做冗余存储，但保证可追溯）

你提出“放仓库/落库/写日志”的分歧点，本项目 v0 选择一个可用且最省事的落点：
1. **长期需要交付给用户的内容**：必须在仓库里（随代码一起版本化）
   - 典型：需求确认版本、架构规格/ADR、接口文档、模块级测试脚本/说明、关键运行命令清单
2. **运行期编排必需但不要求交付给用户的内容**：放数据库（控制面状态）
   - 典型：work_tasks/task_runs/leases/heartbeats/toolpacks_snapshot、`RUN_FINISHED` 事件摘要
3. **体量大且低价值的细碎日志**：写到日志系统/文件系统，数据库只存引用或摘要
   - 典型：完整控制台输出、逐行编译日志、长测试输出

路径约定（你确认采用该约定，用于避免与“项目自身文档资产”混在一起）：
1. AgentX 生成的“过程证据/审计链/决策痕迹/工作报告/导出的确认版需求与架构规格”等，统一放在仓库的 `.agentx/` 目录下
2. 项目自身的文档资产（例如用户希望交付给最终维护者的接口文档、README、使用说明）由任务明确写入其技术栈约定的目录（例如 `docs/`、`openapi/`、`README.md` 等），不与 `.agentx/` 混用

关于“需求文档存 DB 还是存仓库”的折中（避免冗余但保证交付）：
1. 运行期编辑/版本过程可以在 DB 里完成（模块 03 的 requirement_doc_versions），便于交互与审计
2. **对外交付**时，只要求把“确认版本”（confirmed_version）导出到仓库 `.agentx/requirements/` 下作为交付资产；不强制把所有草稿版本都写入仓库

最小“引用”格式（只定义语义，不限定实现）：
1. `artifact_ref` 形如：`git:<commit>:<path>`（表示该证据在仓库某 commit 的某路径下）
2. 运行事件链（模块 05 的 `ARTIFACT_LINKED`）携带 `artifact_ref` 即可，不强制新增字段

为什么这样设计：
1. 交付给用户的必须在仓库里，否则用户 clone 后拿不到“为什么这么做/怎么维护”
2. DB 只承担运行期控制面的职责，避免把 DB 当成知识库导致交付困难
3. “只存引用”能避免同一份证据在 repo 与 DB 双份冗余存储
