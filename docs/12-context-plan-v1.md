# 模块 12：工程级上下文管理计划（Context Plan v1）

更新时间：2026-03-12

本文件是对 `docs/08-context-management-module.md` 的工程化落地补充，目标是把“上下文包 + 快照门禁”的设计升级为可扩展、可观测、可预算、可回收的上下文体系，能够在复杂系统与大型仓库下稳定工作。

本计划坚持 v0 的亮点：
1. 事实来源可追溯（refs + 证据目录 `.agentx/`）
2. 上下文编译有门禁（`task_context_snapshots` READY/STALE 等状态机）
3. `task_context`（事实/引用）与 `task_skill`（做法/指导）分离

---

## 1. v1 要解决的工程问题

在复杂系统下，v0 方案最容易出现的失控点：
1. 上下文无限增长：需求文档、票据事件、run 日志、代码规模一起增长，worker/architect prompt 很快超预算。
2. 上下文漂移：不同角色拿到的信息粒度不同，导致“同一事实多版本描述”，后续很难审计。
3. 检索不稳定：靠人工挑文件/靠模型猜文件，导致相关文件缺失或夹带无关内容。
4. 调度死循环：任务宽泛或信息缺失时，worker 反复 NEED_CLARIFICATION，系统不断重试，耗 token 且不收敛。

---

## 2. v1 上下文分层（Context Stack）

v1 明确把上下文分成“稳定层”和“动态层”，并对每层做预算与回退策略：

1. L0 固定规则层（Hard Rules）
   - 依赖方向、门禁规则、write_scope/read_scope 约束、VERIFY 只读约束
   - 来源：`docs/10-...`、`docs/06-...`、`docs/07-...`
2. L1 需求基线层（Requirement Baseline）
   - confirmed requirement 文档（必要时仅提取 highlights）
   - 来源：`requirement_doc_versions.confirmed_version`
3. L2 决策账本层（Decision Ledger）
   - DECISION/CLARIFICATION 的 Q/A 摘要、状态变化
   - 来源：`tickets + ticket_events`
4. L3 执行账本层（Run Ledger）
   - 最近 runs 的状态、失败根因摘要、delivery_commit
   - 来源：`task_runs + task_run_events(RUN_FINISHED)`
5. L4 代码与证据层（Repo/Evidence Context）
   - 相关文件列表 + 关键 excerpt（RAG 输入）
   - 来源：repo/worktree + `.agentx/` artifacts（按白名单）

说明：
1. L0-L3 倾向结构化与短文本，追求稳定可审计。
2. L4 是最容易爆炸的层，必须预算化（限制文件数、excerpt 数、总字符数）。

---

## 3. v1 索引与检索（Indexing + RAG）

v1 定义“检索”是上下文编译的一个子步骤，不直接等同向量库选型。

### 3.1 现阶段落地（默认）

1. Repo/Worktree 采用 **lexical indexing**（词法检索）
   - 输入：query terms（由 task title、ticket-summary、run-summary、task skill fragments 组成）
   - 输出：relevant files + excerpts（受预算限制）
2. 该检索用于：
   - 生成 `task_skill` 中的 Repo Context（baseline）
   - 生成 worker 的 `workspace_snapshot`（当前 worktree）

### 3.2 后续可插拔升级（可选）

当仓库/证据体量上来后，可以在不破坏控制面门禁的前提下引入：
1. Embedding + Vector Store（语义检索）
2. Chunking + 引用（每段 excerpt 带 ref/path）
3. 多索引源：`docs/`、`.agentx/`、`src/` 分开建索引并分别预算

硬约束：
1. 检索结果必须可追溯（path/ref + score/reason）。
2. 检索必须可降级（向量不可用时退回 lexical）。

---

## 4. v1 上下文预算（Budget）

建议用“字符预算”而不是“token 预算”作为服务端硬约束（实现简单、可控），示例：
1. `task_skill_excerpt`：<= 20k chars（worker runtime 已做截断）
2. `workspace_snapshot`：<= 12k chars（worker runtime 已做截断）
3. `task_context_pack`（compact）：<= 2k chars 等价结构化字段（不携带长正文）
4. architect/foreman role pack：只含摘要 + refs（禁止长正文）

回退策略：
1. 超预算时优先丢弃 L4 excerpt，其次减少 runs/tickets 摘要数量。
2. 永不丢弃 L0 门禁规则与 write_scope/read_scope。

---

## 5. v1 角色上下文（Architect / Worker）

### 5.1 Architect（提请生成 + 拆解规划）

目标：让 architect agent 在不依赖“聊天记忆”的前提下，稳定地产生 DECISION/CLARIFICATION 与可执行任务拆解。

最小输入：
1. requirement_doc_content（可裁剪）
2. recent_ticket_events（有限条）
3. role_context_pack（结构化摘要 + refs）

落地策略：
1. 提请生成阶段：在 LLM input 的 payload_json 中嵌入 role_context_pack（减少遗漏）。
2. 拆解规划阶段：继续使用 role_context_pack_json，后续可加入 repo context（用于“在已有代码基础上规划”）。

### 5.2 Worker（执行规划 + edits）

目标：让 worker 的 “planner prompt” 拿到足够事实与足够代码上下文，但始终受预算控制。

最小输入：
1. `task_context`（结构化 refs）
2. `task_context_ref`（指向完整 pack，便于审计与调试）
3. `task_context_pack`（compact 版 pack，便于模型读）
4. `task_skill_excerpt`（做法 + baseline repo context）
5. `workspace_snapshot`（当前 worktree 的相关文件 + excerpt）

---

## 6. 与现有代码的映射（当前已落地的 v1 改造点）

以下改造点属于 v1 的“先把上下文打稳”：

1. Worker Task Package 增加 `task_context_ref`
   - 便于 worker runtime 读取完整 task_context_pack（审计与调试锚点）
2. Worker planner prompt 增加 `task_context_pack`（compact）
   - 避免仅靠 `task_context` 的 refs 丢失 module/run_kind 等信息
3. Architect 提请生成阶段的 payload_json 增强
   - 将 `role_context_pack` 嵌入 payload_json，减少 architect agent 上下文漂移

对应实现文件（仅供定位）：
1. `src/main/java/com/agentx/agentxbackend/execution/domain/model/TaskPackage.java`
2. `src/main/java/com/agentx/agentxbackend/execution/application/RunCommandService.java`
3. `src/main/java/com/agentx/agentxbackend/execution/api/WorkerRunController.java`
4. `src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java`
5. `src/main/java/com/agentx/agentxbackend/process/application/ArchitectTicketAutoProcessorService.java`

---

## 7. 下一步建议（v1 后续迭代）

如果要继续把 v1 做到“复杂系统级”，建议按优先级推进：
1. 统一检索组件：抽出共享的 lexical index（避免 contextpack 与 worker runtime 两套算法漂移）。
2. 引入 Evidence Index：对 `.agentx/` 下的 work_report/verify 证据做检索，帮助 bugfix/verify 任务更快定位。
3. 加入循环保护：对连续 NEED_* / FAILED 的任务触发“架构师自检/任务重排”工单（见 `docs/05` NEED_* 语义）。
4. 在快照中记录检索统计：例如 top files、excerpt chars、warnings（可落到 artifact 中，不强绑 schema）。

