# 模块 06：并发领任务与 Git 工作区（Worktree + 分支分配）

更新时间：2026-02-20

范围说明：
模块 05 明确“不讨论如何并发调度多个 Worker”，只定义了 run 生命周期与监控面。
本模块在模块 04/05 的基础上补齐两件事：
1. 并发领任务：多个 Worker 同时尝试“领取/分配”任务时，系统如何保证原子性与可恢复性
2. Git 工作区：总工如何基于 `git worktree` 给每个 run 分配可写路径，并定义“基线与最新性”的工业化规则

术语引用：
1. 任务/模块/Worker/工具包：见 `docs/04-foreman-worker-module.md`
2. run/租约/心跳/事件链/NEED_*：见 `docs/05-worker-execution-and-monitoring.md`
3. 工单/提请（DECISION/CLARIFICATION）：见 `docs/03-project-design-module.md`

非目标（明确排除，避免跑偏）：
1. 不讨论具体 CI/CD 工具落地（Jenkins/GitHub Actions/GitLab CI 等）
2. 不讨论代码评审流程细节（只定义合并语义与门禁点）
3. 不讨论“如何把任务拆得更好”（拆分属于总工/架构模块）

---

## 1. 核心结论（先把规则定死，避免自由发挥）

1. 每个 `task_run` 必须绑定一个不可变的 `base_commit`，run 的事实来源只能是该 commit 的仓库快照
2. Worker 在 run 过程中不允许自行“追最新”（不允许把事实来源从 `base_commit` 漂移到 `main` HEAD）
3. “并发抢任务”必须被建模为一个原子动作：同一时刻一个 `task_id` 至多存在一个有效的 active run（可通过租约失效回收）
4. 并发写文件冲突用 worktree 解决在执行期隔离；真正需要治理的是合并期冲突与基线刷新门禁

---

### 1.1 初始化独占阶段（Bootstrap）规则（你补充的约束）

你要求“先派一个初始化任务操作根目录，且在初始化确认前不允许并发”。在本模块里把它定成硬规则：
1. Session 刚创建时必须先完成一个 `bootstrap` 模块，且该模块在 v0 **只有一个 INIT 任务**（`task_template_id=tmpl.init.v0`），初始化确认前系统进入独占模式
2. 独占模式下：任意时刻最多允许存在 1 个 active run（无论多少 Worker）
3. 独占模式下：只允许分配这一个 INIT 任务；其它模块任务一律保持 `PLANNED/WAITING_WORKER/READY_FOR_ASSIGN` 但不得进入 `ASSIGNED`
4. 初始化任务允许写入仓库根目录（`write_scope` 可以覆盖 `/`），但这是一次性特权：该 INIT 任务完成并确认后，后续任务默认不允许这么宽（必须收敛写入范围）
5. 初始化确认的最小结果是：`main` 上存在一个可复现的基线 commit（后续 run 的 `base_commit` 都从此开始有意义）
6. 独占模式的解锁点写死为：该 INIT 任务进入 `DONE`（而不是 `DELIVERED`）
   - 理由：你选择了 “解锁点用 DONE”，这会强制 INIT 也走 DoD v0（独立 VERIFY + 合并门禁），保证基线是可验证事实

工程化说明（只解释必要点）：
1. 你选择了 `git worktree` 并发隔离，而 `git worktree` 要求仓库至少存在一个 commit
2. 因此“初始化”不仅是生成模板文件，更是把仓库推进到“可用 Git 快照治理”的状态

补充约束（防止初始化权限被滥用）：
1. **只有** `tmpl.init.v0` 允许 `write_scope=/`；其它任务如确实需要扩大写入范围，必须触发 `NEED_DECISION`，由总工提请用户决策是否放开（模块 03/05）

---

## 2. 必须使用的 Git 概念（只保留与系统行为相关的定义）

1. `commit`：一次提交对应一个不可变的历史节点（由哈希标识），可作为系统“事实版本”的锚点
2. `branch`：指向某个 commit 的可移动引用；任务分支用于承载 Worker 的交付
3. `remote`/`origin`：远端仓库引用；系统同步远端信息默认用 `fetch`（不改工作区）
4. `merge`：把任务分支的交付合并回集成主线
5. `rebase`：把任务分支的提交串重新基于新的主线 commit（用于合并前的基线适配）
6. `git worktree`：在同一个仓库对象库之上创建多个独立工作目录，每个目录检出不同分支/commit，用于并发执行期隔离

系统约束（写死）：
1. Worker 在被分配的 worktree 内工作，只允许在任务分支上提交
2. Worker 不允许在 run 过程中对任务分支执行“隐式更新主线”的动作（例如 pull main 导致基线漂移）

---

## 3. 基线与“最新性”：本项目的确定规则（不保留选择题）

### 3.1 定义：什么叫“最新”

在本系统里，“最新”只允许两种表述：
1. 对某个 `task_run`：最新 = `base_commit`
2. 对集成主线 `main`：最新 = `main` 当前 HEAD 对应的 commit

任何没有落到 commit 的“最新”都是不可执行的描述。

### 3.2 固定规则：严格快照制 + 合并门禁刷新

规则：
1. run 启动时总工选择 `base_commit`，并写入 `task_context.repo_baseline_ref`
2. Worker 在 run 执行期间，事实来源只能是 `base_commit` 对应的仓库快照
3. 如果需要适配 `main` 的新变化，只允许在“合并门禁点”由总工执行基线刷新：
   - 典型动作是：对任务分支执行 rebase 到 `main` HEAD（或其它明确指定的 commit）
4. 每一次基线刷新都必须形成可追溯记录（最小要求：从哪个 commit 刷到哪个 commit，触发原因）

这样处理“可读范围大但不是最新”的问题：
1. Worker 可读范围可以覆盖整个仓库，但读到的内容永远与 `base_commit` 一致
2. 这不是缺陷，是为了保证可复现、可审计、可回滚

---

## 4. 并发领任务协议（Claiming Contract）

本节回答你问的“并发抢任务”。模块 05 没写，这里补齐。

### 4.1 目标与硬约束

目标：
1. 多个 Worker 并发请求任务时，同一任务不会被重复分配
2. Worker 崩溃/断联时，任务能在租约过期后被回收并重新分配

硬约束：
1. 同一时刻一个 `task_id` 至多有一个 active run（active = run=RUNNING 或 WAITING_FOREMAN 且租约未过期）
2. 任务分配必须与 run 创建、工作区分配绑定为一个“不可分割的结果”（要么都成功，要么都失败并回滚）

### 4.2 领任务模型：Worker Pull，分配由总工控制面原子完成

为什么不做纯 Worker 自主抢占：
1. 你已经选择“总工分配可写路径”，这要求分配动作具备全局一致性与审计能力
2. 工具包约束、路径约束、基线选择、worktree 资源都需要在同一个控制面内协调

因此这里采用：
1. Worker 发起 `CLAIM_REQUEST(worker_id)`（可以是轮询，但语义上是“领取请求”）
2. 总工控制面在一个原子动作里完成：选任务 -> claim -> 创建 run -> 分配 worktree -> 下发任务包

说明：
这仍然是“并发抢任务”，只是抢占发生在总工控制面的原子分配逻辑里，而不是让 Worker 直接抢写数据库。

### 4.3 原子性要求（不用写代码，但必须定义语义）

一次成功的任务分配必须同时满足：
1. 该任务仍可分配（例如 `work_tasks.status=READY_FOR_ASSIGN`）
2. 存在满足工具包约束的 Worker（Worker 已处于 `READY`）
3. 存在最新 `READY` 的上下文快照（`task_context_snapshots`），且未 `STALE`
4. 成功创建 `task_run`（写入 run 表，初始化 lease/heartbeat，并绑定 `context_snapshot_id`）
5. 成功分配 Git 工作区（创建任务分支与 worktree 目录，且工作区干净）

任意一步失败：
1. 不允许留下“半分配状态”
2. 必须释放 claim（让其他 Worker 能继续领取）

### 4.4 任务状态与触发（最小增量，避免膨胀）

本模块不新增新的 task 状态集合，而是沿用模块 04 定义的最小状态集，并补齐触发语义与 DoD/合并门禁的联动：
1. `READY_FOR_ASSIGN`：可被分配（无 active run）
2. `ASSIGNED`：已分配且存在 active run（`active_run_id` 非空）
3. `DELIVERED`：已有交付候选（存在 `delivery_commit`），但尚未通过“独立验证 + 合并门禁”，因此不允许直接标 `DONE`
4. `DONE`：最终已合入 `main` 且通过 DoD v0（模块 07）

触发（最小、可审计）：
1. 原子分配成功：`READY_FOR_ASSIGN -> ASSIGNED`，并写入 `active_run_id`
2. active run 租约过期回收：`ASSIGNED -> READY_FOR_ASSIGN`，并清空 `active_run_id`
3. active run 失败/取消：`ASSIGNED -> READY_FOR_ASSIGN`（或回到 `WAITING_WORKER`），并清空 `active_run_id`
4. active run 成功（run_kind=IMPL，产出 `delivery_commit`）：`ASSIGNED -> DELIVERED`，并清空 `active_run_id`
5. 合并门禁成功（见第 6 节 + 模块 07）：`DELIVERED -> DONE`

说明（避免状态膨胀）：
1. “阻塞等待用户”属于 run 状态（模块 05 的 `WAITING_FOREMAN`），不新增 task 状态
2. “验证通过/验证失败”属于 run 结果与门禁结论，不新增 task 状态；失败只会阻止 `DELIVERED -> DONE`

---

## 5. Git 工作区分配协议（Workspace Contract）

### 5.1 工作区布局与命名（建议写死，减少歧义）

命名建议：
1. 集成主线：`main`（写死，不再保留 `master` 选择题）
2. 任务分支：`task/<task_id>`（任务维度，便于长期跟踪）
3. run 工作分支：`run/<run_id>`（run 维度，便于重试与回收）

目录建议：
1. 会话仓库根目录：`sessions/<session_id>/repo/`（每个 session 一个独立 git 仓库）
2. worktree 目录：`worktrees/<session_id>/<run_id>/`（相对于该 session 仓库根目录）

说明：
1. 会话仓库隔离是硬约束：不同 session 不共享同一个 `main` 分支历史
2. 用 `run_id` 做 worktree 目录名，避免重试时目录冲突
3. 任务分支用于“人类/总工视角的任务追溯”，run 分支用于“执行尝试的隔离”

### 5.2 分配步骤（语义序列）

对一个 `task_run`：
1. 确认 `base_commit` 可用（必要时 `fetch` 获取远端对象）
2. 创建 run 分支 `run/<run_id>` 指向 `base_commit`
3. 创建 worktree 目录并检出该 run 分支
4. 校验工作区干净（无未提交变更、无冲突状态）
5. 记录 workspace 分配结果（用于审计与回收）
6. 下发任务包（包含 `repo_baseline_ref=base_commit`、worktree_path、branch_name）
7. 任务包必须携带 `context_snapshot_id`（审计“本 run 使用了哪份上下文”）

失败处理（必须定义）：
1. 分支创建失败或 worktree 创建失败 -> 视为“分配失败”，不得启动 run
2. 工作区不干净（例如残留文件）-> 标记 workspace 为 `BROKEN` 并重新分配新的 worktree

### 5.3 回收步骤（run 结束后的清理语义）

run 结束后：
1. worktree 目录应被回收（释放磁盘空间，避免污染后续 run）
2. run 分支是否保留取决于审计策略：
   - 最小策略：保留远端分支引用到一个时间窗口（便于追溯），超期由系统清理
3. 任务分支 `task/<task_id>` 在 v0 被定义为“任务交付候选分支”（强制存在）：
   - Worker 在 `run/<run_id>` 上工作
   - 当 IMPL run 产出 `delivery_commit` 后，总工将 `task/<task_id>` 创建或快进到该提交（见 C8）
   - 合并门禁对 `task/<task_id>` 做 rebase/VERIFY/merge（见第 6 节与 C9）

---

## 6. 合并门禁（只定义必要门禁，不扩展到 CI/CD）

合并门禁的目标不是“把代码合进去”，而是把交付候选变成“可验证且最终进入主线的事实”（与模块 07 的 DoD 对齐）。

因此本系统把合并门禁定义为一个**串行集成车道（Integration Lane）**：
1. 任意时刻最多允许 1 个 `DELIVERED` 任务进入门禁（避免并发 rebase/merge 互相踩踏）
2. 门禁内必须产生一个“最终可合并候选”（merge candidate），并对该候选执行只读 VERIFY
3. VERIFY 通过后才允许把候选合入 `main`，并将任务置为 `DONE`

### 6.1 门禁输入（来自 IMPL 交付）

进入门禁的前置条件（最小）：
1. 任务已处于 `DELIVERED`
2. 存在一个可审计的 `delivery_commit`（来自 IMPL run 的 Work Report，模块 05/07）
3. `task/<task_id>` 已被总工“创建或快进”到 `delivery_commit`（把“交付候选”固定在一个可追溯的分支上）

### 6.2 门禁步骤（写死的最小流程）

当 Worker 完成 IMPL run 并产出 `delivery_commit` 后，总工进入合并门禁，按以下序列执行：
1. 固定集成目标：读取当前 `main` HEAD（记为 `main_head_before`），作为本轮门禁的目标基线
2. 基线适配：把交付分支 `task/<task_id>` rebase 到 `main_head_before`
   - rebase 成功后，交付分支 HEAD 形成一个新 commit（记为 `merge_candidate_commit`）
3. 冲突处理：
   - 若冲突可机械解决：总工创建“冲突修复任务”分配给 Worker（仍走本模块的分配流程）
   - 若冲突涉及实现取舍或会改变价值/架构约束：发起 `DECISION/CLARIFICATION` 提请（模块 03）
4. 独立验证（强制）：创建一个 `run_kind=VERIFY` 的 run，且 `base_commit=merge_candidate_commit`，并绑定最新 `READY` 的 `context_snapshot_id`
   - VERIFY 必须只读（`write_scope` 为空），且必须产出可审计 Work Report（模块 05/07）
5. 合并：VERIFY 通过后，把 `main` 快进（fast-forward）到 `merge_candidate_commit`
   - 这里强制使用快进的原因：避免生成“新的 merge commit”从而破坏“验证了哪个 commit”这件事的可追溯性
6. 收尾：任务状态 `DELIVERED -> DONE`；记录本次门禁证据引用（VERIFY run_id、merge_candidate_commit、main_head_before）

说明（为什么这么写死）：
1. “是否一定要 rebase”不作为选择题保留；默认门禁动作就是 rebase + VERIFY
2. 对个人项目落地工具来说，串行集成车道能显著降低复杂度，同时又能保证可审计性
3. 如果未来需要优化吞吐（merge queue、批量合并），再单独开模块增量，不在 v0 扩展

---

## 7. 最小数据结构草案（只为表达 claim + workspace + 审计）

说明：
本节是在模块 04/05 的数据结构之上给“并发领任务 + Git 工作区”补齐最小字段/表。
不追求一次到位，后续按需要增量字段即可。

### 7.1 任务表增量（work_tasks）

```sql
-- 模块 04 已定义 work_tasks 的最小状态集：
-- status: PLANNED | WAITING_WORKER | READY_FOR_ASSIGN | ASSIGNED | DELIVERED | DONE
--
-- 模块 06 仅要求增加 active_run_id，便于并发领任务时快速定位 active run
alter table work_tasks add column active_run_id varchar(64) null;
```

### 7.2 run 表增量（task_runs）

```sql
-- 模块 05 的 task_runs 增量字段（用于 Git 审计）
alter table task_runs add column base_commit varchar(64) not null;
alter table task_runs add column branch_name varchar(128) not null;
alter table task_runs add column worktree_path varchar(256) not null;
```

说明：
1. `base_commit` 必须入库，否则审计只能依赖外部日志
2. `branch_name/worktree_path` 让“总工分配可写路径”可追溯
3. 上下文锚点字段 `context_snapshot_id` 由模块 08 定义（本模块在分配时必须校验其 `READY`）

### 7.3 Git 工作区记录（git_workspaces）

```sql
create table git_workspaces (
  run_id           varchar(64) primary key, -- v0：一个 run 对应一个 workspace（workspace identity = run_id）
  status           varchar(32) not null, -- ALLOCATED | RELEASED | BROKEN

  created_at       timestamp not null,
  updated_at       timestamp not null
);
```

状态触发（最小）：
1. worktree 创建成功 -> `ALLOCATED`
2. run 结束且清理完成 -> `RELEASED`
3. 发现污染/不可用 -> `BROKEN`

---

## 8. 完整交互流程与异常场景（并发抢任务 + worktree + 基线）

本节只列“系统必须解释清楚的场景”，避免写成故事。

### C1：多个 Worker 并发领取任务（只有一个成功）

触发：多个 `READY` Worker 同时发起 `CLAIM_REQUEST`  
要求：
1. 控制面必须保证同一 `task_id` 的分配原子性
2. 输掉竞争的 Worker 获得“无任务/稍后重试”的响应，不允许重复启动 run

### C2：成功分配（claim + run + workspace 全部完成）

触发：存在 `READY_FOR_ASSIGN` 任务且存在满足工具包的 Worker  
动作：
1. 将 `work_tasks.status` 置为 `ASSIGNED`，写入 `active_run_id`
2. 校验存在最新 `READY` 的 `context_snapshot_id`（不存在则不得分配）
3. 创建 `task_runs(status=RUNNING, base_commit, branch_name, worktree_path, lease_until, last_heartbeat_at, context_snapshot_id)`
4. 创建 `git_workspaces(status=ALLOCATED)`
5. 下发任务包（包含 `repo_baseline_ref=base_commit` 与 `context_snapshot_id`）

### C3：分配中失败（必须回滚，不能留下半状态）

触发：worktree 创建失败、分支创建失败、路径冲突、磁盘不足等  
动作：
1. 回滚 `work_tasks.status` 到 `READY_FOR_ASSIGN`，清空 `active_run_id`
2. 记录失败原因到事件链（run 未创建则记到系统事件；run 已创建则标记 FAILED 并释放租约）

### C4：Worker 崩溃/断联（租约过期回收）

触发：run 心跳超时，租约到期  
动作：
1. 将该 run 标记为失败或取消（模块 05）
2. 将 `work_tasks.status` 从 `ASSIGNED` 回收到 `READY_FOR_ASSIGN`
3. 将对应 `git_workspaces.status` 标记为 `BROKEN` 或尝试清理后 `RELEASED`

### C5：Worker 阻塞需要用户信息/决策（不新增提请类型）

触发：Worker 发出 `NEED_CLARIFICATION/NEED_DECISION` 事件（模块 05）  
动作：
1. run -> `WAITING_FOREMAN`
2. 总工先 triage；仅当无法内部闭环时，才创建 `CLARIFICATION/DECISION` 工单（模块 03）
3. triage 或用户响应引入新事实时，先重编译上下文快照到 `READY`
4. 若事实指纹未变化可恢复同 run；若已变化必须新建 run（模块 05）

### C6：主线更新导致合并期冲突（在门禁点解决）

触发：总工对 run 分支执行 rebase 到 `main` HEAD 出现冲突  
动作：
1. 若冲突可工程化拆解：创建“冲突修复任务”，走本模块分配流程
2. 若冲突涉及取舍：转 `DECISION/CLARIFICATION` 提请
3. 冲突解决后再 merge

### C7：同一文件被多个任务修改（执行期隔离，合并期治理）

触发：两个 run 分支都修改了同一文件  
处理原则：
1. 执行期不阻止（worktree 已隔离）
2. 合并期通过冲突或代码审计暴露，按 C6 处理

### C8：IMPL run 成功产出交付候选（ASSIGNED -> DELIVERED）

触发：某个 `ASSIGNED` 任务的一个 `run_kind=IMPL` run 达到 `SUCCEEDED`，且 Work Report 给出 `delivery_commit`  
动作：
1. 清空 `active_run_id`（run 已结束，不再占用）
2. `work_tasks.status` 推进为 `DELIVERED`
3. 总工将任务交付候选分支 `task/<task_id>` 创建或快进到 `delivery_commit`（把候选固定在可追溯分支上）
4. 把 `delivery_commit` 与 Work Report 作为证据引用挂接到任务（可通过事件链 `ARTIFACT_LINKED` 实现，不强制加字段）
5. 服务端在 `DELIVERED` 后立即尝试触发 merge gate（若车道繁忙则保留 `DELIVERED`，由下一轮调度/GC 兜底重试）

### C9：合并门禁（rebase -> VERIFY merge candidate -> fast-forward merge）

触发：总工从 `DELIVERED` 队列中取出一个任务进入串行集成车道  
动作：
1. 读取 `main` HEAD（记为 `main_head_before`）
2. 对交付分支执行 rebase 到 `main_head_before`，得到 `merge_candidate_commit`，并写入候选证据 ref：`refs/agentx/candidate/<task>/<attempt>`
3. 创建 VERIFY run：`run_kind=VERIFY`，`base_commit=merge_candidate_commit`，`context_snapshot_id=latest READY`，只读验证
4. VERIFY 通过：`main` 快进到 `merge_candidate_commit`，并确保 `main` 上存在至少一个注释 `delivery/<YYYYMMDD-HHmm>` tag；任务 `DELIVERED -> DONE`
5. VERIFY 失败：
   - 若判定为基础设施失败：允许对同一 `merge_candidate_commit` 自动重试（最多 2 次）
   - 若为业务失败：将原任务从 `DELIVERED` 回退到可调度状态，进入同任务 debug 流程（不自动拆分新 bugfix 任务）

异常（最小）：
1. rebase 冲突：创建“冲突修复任务”（`tmpl.bugfix.v0`），把原任务回退并挂上 `depends_on=冲突修复任务(DONE)`，完成后再回到 C9
2. fast-forward 失败（`main` HEAD 已变化）：必须重新执行 C9（产生新的 merge candidate 并重新 VERIFY）
   - 控制面不得把任务卡死在旧的 `DELIVERED + VERIFY SUCCEEDED` 组合上
   - 正确行为是：识别“本次 VERIFY 已过时”，立即重新启动 merge gate，基于新的 `main` HEAD 再走一次 rebase -> VERIFY -> fast-forward
