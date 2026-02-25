# Bug Handoff: Worker Clarification Loop + Worker View + Language Consistency

更新日期：2026-02-24

## 1. 背景与目标

本交接单用于专门的 bug 修复 agent。
当前系统在“架构拆解后 -> worker 执行 init 任务”阶段出现持续卡顿，前端与后端状态不一致，且提请语言与配置不一致。

目标：
1. 消除 `NEED_CLARIFICATION -> USER_RESPONDED -> 继续卡住` 的循环。
2. 让前端 worker 视图真实反映后端 worker 池状态。
3. 让 worker 侧提请语言遵从配置（默认中文）。
4. 消除工单事件显示顺序错乱问题。
5. 建立修复后的统一验证与后端自动重建规范。

---

## 2. 复现环境（本次观测）

1. 部署方式：Docker Compose
2. Backend: `agentx-backend-1`（端口 `18082`）
3. 会话样例：`SES-7440de024d324876ba8643a56de00bfa`
4. 观测时间：2026-02-24 12:39 ~ 13:02（UTC+8）

---

## 3. Bug 列表

## BUG-001：用户已回复提请，但 run 不恢复，继续卡在 WAITING_FOREMAN

现象：
1. init run 发出 `NEED_CLARIFICATION` 后，创建 `CLARIFICATION` 工单（`WAITING_USER`）。
2. 用户通过前端提交 `USER_RESPONDED` 后，工单转 `IN_PROGRESS`。
3. 关联 run 仍停留 `WAITING_FOREMAN`，直到 lease 到期被 watchdog 标记 `FAILED`。
4. 随后系统又拉起新 run，再次 `NEED_CLARIFICATION`，形成循环。

实际观测样例：
1. run：`RUN-3061eb1d959142d6b3b77feb9665a399` -> `WAITING_FOREMAN` -> `FAILED`
2. ticket：`TCK-165966b1ca9f49e0998d731f0d3896b1` 收到 `USER_RESPONDED` 后仍未带动 run 继续。

期望：
1. 用户回应后，系统要么：
   - 恢复同一 run 继续执行；
   - 或显式终止旧 run 并创建可继续的后续 run（但不能静默卡住）。

可能涉及文件：
1. `src/main/java/com/agentx/agentxbackend/process/application/ContextRefreshProcessManager.java`
2. `src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java`
3. `src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java`
4. `src/main/java/com/agentx/agentxbackend/process/infrastructure/external/ContextRefreshEventListener.java`

修复建议方向：
1. 为 `run_need_input` 场景增加“用户回应后恢复执行”的显式流程。
2. 明确“恢复同 run”或“新 run 续跑”的单一路径，避免多义。
3. 增加幂等保护，防止重复响应导致重复恢复。

验收（必须全部满足）：
1. 同一任务在用户回应后 30 秒内出现可推进状态变化（`RUNNING/SUCCEEDED/FAILED`），不能长期停在 `WAITING_FOREMAN`。
2. 不再出现“仅靠 lease 到期驱动状态推进”的主流程。
3. E2E 中同一 init 任务最多 1 次人工澄清后可以继续推进（除非用户信息确实不足）。

---

## BUG-002：前端“Worker 视图=0”与后端真实 worker 池不一致

现象：
1. 前端点击“自动分配”显示“新增 0 worker”。
2. 前端 worker 列表为空，误导为“没有 worker 接单”。
3. 但数据库里有 `READY` worker（如 `WRK-BOOT-JAVA-CORE`），后端轮询日志也显示 `scannedReadyWorkers=1`。

原因特征：
1. 前端仅基于 `created_worker_ids` 增量维护 `state.workersById`。
2. 未提供“全量 worker 列表”刷新口径。

可能涉及文件：
1. `frontend-demo/app.js`（`triggerAutoProvision`、`renderWorkers`）
2. `src/main/java/com/agentx/agentxbackend/process/api/WorkforceAutomationController.java`（可新增查询入口）
3. `src/main/java/com/agentx/agentxbackend/workforce/application/WorkerCapabilityService.java`（已有 list 能力可复用）

修复建议方向：
1. 新增后端只读接口：按状态查询 worker（至少支持 READY/PROVISIONING/DISABLED）。
2. 前端 worker 面板改为“后端真值刷新”而非仅看自动分配返回。
3. 在 UI 文案区分“新增 worker 数”与“当前可用 worker 总数”。

验收：
1. 当 DB 中有 READY worker 时，前端 worker 列表必须可见。
2. `auto-provision` 返回 0 时，前端仍能展示现有 worker。
3. “执行轮询 claimed=0”时，页面可解释是“无可认领任务/被 gate 阻塞”，不是“无 worker”。

---

## BUG-003：worker 提请语言不遵从配置，默认输出英文

现象：
1. 前端/运行时配置为中文语境，但 worker 发出的 `CLARIFICATION` 提请正文常为英文。
2. 影响用户理解和交互一致性。

可能涉及文件：
1. `src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java`
2. `src/main/java/com/agentx/agentxbackend/process/application/RuntimeLlmConfigService.java`

关键观察：
1. worker planner 系统提示词是英文。
2. worker 解析配置时未显式传入请求输出语言（存在 `null` 路径）。

修复建议方向：
1. worker planner prompt 增加 `output_language` 约束（默认 `zh-CN`）。
2. 从 runtime config 显式读取并传递语言给 worker LLM resolve 路径。
3. NEED_* body 和 decision/question 文本统一遵循配置语言。

验收：
1. `AGENTX_LLM_OUTPUT_LANGUAGE=zh-CN` 时，worker 生成的 NEED_* 文案为中文。
2. 切换 `en-US` 后，新产生文案为英文（支持动态生效）。

---

## BUG-004：工单事件顺序偶发错乱（同秒写入显示反序）

现象：
1. UI 中偶发看到 `DECISION_REQUESTED -> COMMENT`，与业务写入顺序不一致。
2. 当前查询只按 `created_at` 排序，timestamp 精度不足时同秒事件顺序不稳定。

可能涉及文件：
1. `src/main/java/com/agentx/agentxbackend/ticket/infrastructure/persistence/TicketEventMapper.java`
2. `docs/schema/agentx_schema_v0.sql`（`ticket_events.created_at` 精度/排序策略）

修复建议方向：
1. 引入稳定排序键（推荐新增单调序列列 `seq_id` 或使用高精度时间列并保证单调）。
2. API 查询顺序改为稳定排序（例如 `created_at, seq_id`）。

验收：
1. 同一 ticket 的事件回放顺序与 append 顺序一致。
2. 连续高频 append（同秒）不再乱序。

---

## BUG-005：run_need_input 工单收口策略缺失，历史工单堆积

现象：
1. 同一 task/run 流程中会出现多个旧 `CLARIFICATION` 工单长期停留 `OPEN/IN_PROGRESS`。
2. 影响用户判断当前真正待处理项。

可能涉及文件：
1. `src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java`
2. `src/main/java/com/agentx/agentxbackend/ticket/application/TicketCommandService.java`
3. `src/main/java/com/agentx/agentxbackend/process/application/ArchitectTicketAutoProcessorService.java`

修复建议方向：
1. 对同一 `task_id + run_id + ticket_type` 做去重/合并策略。
2. 新 ticket 生成时，自动关闭或标记旧 ticket 为 superseded（保留审计事件）。
3. UI 仅高亮“当前有效待处理项”。

验收：
1. 同一运行上下文不会无限叠加待处理澄清工单。
2. 历史工单可追溯，但不会干扰当前操作。

---

## BUG-006：init 上下文组装丢失关键记忆，context pack 生成了但未注入 worker 提示

现象：
1. `task_context_snapshots` 已持续刷新，且 `task_context_ref` 指向的 context pack 中包含最新 ticket refs / prior run refs / decision refs。
2. 但 worker 执行时仍反复提出“缺少验收标准/缺少目标”的同类澄清，表现出“无记忆”。
3. 用户多次 `USER_RESPONDED` 后，下一轮 run 仍可能重复几乎同样的问题。

本次硬证据：
1. DB 中 `task_context_snapshots` 最新 READY 记录的 `task_context_ref` 包含丰富上下文（含多条澄清 ticket 引用）。
2. `RunCommandService` 组装 `TaskPackage` 时写死了简化 `TaskContext`，`architectureRefs/priorRunRefs` 直接是空数组。
3. `LocalWorkerTaskExecutor` 构造给 LLM 的 user prompt 时，只注入 `task_skill_excerpt` + scope/规则，没有注入 context pack 内容。

可能涉及文件：
1. `src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java`
2. `src/main/java/com/agentx/agentxbackend/execution/application/port/out/ContextSnapshotReadPort.java`
3. `src/main/java/com/agentx/agentxbackend/execution/infrastructure/persistence/ContextSnapshotMapper.java`
4. `src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java`

修复建议方向：
1. 扩展 `ContextSnapshotReadPort.ReadySnapshot`，除 `taskSkillRef` 外增加 `taskContextRef`（至少可读取 context pack）。
2. `RunCommandService.toTaskPackage` 不再硬编码空 `TaskContext`，改为从 `taskContextRef` 解析并注入真实 `requirementRef/architectureRefs/priorRunRefs`。
3. `LocalWorkerTaskExecutor.buildUserPrompt` 明确注入 `task_context`（至少 requirementRef、最近 decision/user response 摘要、priorRunRefs）。
4. 为 init 模板增加“同一问法去重”规则：若最近一次 `USER_RESPONDED` 已覆盖同类问题，不允许重复发相同 `NEED_CLARIFICATION`。

验收：
1. 用户回答一次明确 init 验收标准后，后续 run 不再重复同类澄清问题。
2. 抓包/日志可见 worker prompt 包含非空 `task_context` 核心字段（而非空数组占位）。
3. 对同一 task 连续运行，`priorRunRefs` 与最近 ticket response 能进入下一轮执行上下文。

---

## 4. 回归验证脚本建议（修复后执行）

最小回归顺序：
1. 新建 session -> 需求确认 -> 架构拆解。
2. 触发 init 任务，故意让 worker 先发一次 `NEED_CLARIFICATION`。
3. 用户回复后，验证 run 能继续推进，不再仅靠 lease 超时。
4. 验证前端 worker 面板可显示后端真实 READY worker。
5. 验证提请语言随配置切换。
6. 验证 ticket event 顺序稳定。

建议检查点（SQL）：
1. `task_runs.status` 不长期停留 `WAITING_FOREMAN`。
2. `tickets` 中当前会话仅有一个有效 `WAITING_USER` 提请。
3. `ticket_events` 顺序与业务 append 顺序一致。

---

## 5. 交付与部署规范（必须执行）

每次修复后必须完成以下步骤：

1. 本地测试
   - 至少运行相关单测与回归测试。

2. 后端镜像重建并重启（强制）
   - 若使用 `.env.docker`：
     - `docker compose --env-file .env.docker up -d --build backend`
   - 若无 `.env.docker`，需在执行命令前注入必填变量：
     - `MYSQL_ROOT_PASSWORD`
     - `AGENTX_DB_PASSWORD`
     - `AGENTX_REDIS_PASSWORD`

3. 健康检查
   - `docker ps` 确认 `agentx-backend-1` 为 `healthy`。
   - 检查启动日志无新增异常栈。

4. 验证记录
   - 提交修复报告时附：
     - 复现步骤
     - 修复前后 SQL 对比
     - 关键日志片段
     - API 返回样例

---

## 6. 优先级建议

1. P0：BUG-001（用户回应后 run 不恢复）
2. P0：BUG-006（上下文记忆未注入 worker，导致反复同类澄清）
3. P0：BUG-002（前端 worker 视图误导）
4. P1：BUG-003（语言不一致）
5. P1：BUG-004（事件顺序）
6. P2：BUG-005（工单堆积收口）

