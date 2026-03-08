# AgentX 项目快照（2026-02-25）

更新时间：2026-02-25  
定位：面向“当前是否在轨道、有哪些偏差、离完整闭环还差什么”的工程状态快照。

## 1. 结论先行

1. 项目仍在轨道上，控制平面主链路可运行，模块边界与状态机主语义基本一致。
2. P0 第一项（会话级仓库隔离）已完成并进入后端一等能力，不再仅依赖运行脚本约定。
3. 当前短板不再是“能不能跑起来”，而是“能不能稳定产出可交付项目并持续复现”。

## 2. 本轮关键落地（P0 #1）

1. 会话级仓库隔离已落地到执行/工作区/合并门禁/交付链路：
   - session 仓库：`sessions/<session_id>/repo`
   - run worktree：`worktrees/<session_id>/<run_id>/`（相对 session 仓库根）
2. `WorkspacePort`、`TaskAllocationPort`、mergegate git 端口已 session-aware。
3. delivery clone 发布源仓库已改为 session 仓库，不再共享单一 repo 根。
4. 目标测试与全量后端测试已通过（`mvn test`）。
5. merge gate 已切换为“`DELIVERED` 后立即触发 + GC 兜底”模式，减少门禁等待滞留。
6. VERIFY 失败处置策略已落地：
   - 基础设施失败：同一 merge candidate 自动重试（最多 2 次）
   - 业务失败：回退原任务继续 debug（不自动拆 verify-failed bugfix 任务）
7. git 证据链增强已落地：
   - merge candidate 证据 ref：`refs/agentx/candidate/<task>/<attempt>`
   - merge 成功后确保主线存在注释 `delivery/<YYYYMMDD-HHmm>` tag

## 3. 当前确认“没问题”的部分

1. OPEN 工单不会阻塞其它可执行任务的 claim 与执行（此前回归已覆盖）。
2. 架构师自动拆分关键阻塞已修复，且有单测与 API 侧回归。
3. 任务模板能力保持 6 类可识别：
   - `tmpl.init.v0`
   - `tmpl.impl.v0`
   - `tmpl.verify.v0`
   - `tmpl.bugfix.v0`
   - `tmpl.refactor.v0`
   - `tmpl.test.v0`
4. 运行态目录隔离（`runtime-projects`）和 Docker/测试脚本可配套使用。

## 4. 文档统筹结果（本轮）

已删除的过时文档：

1. `docs/plans/bugfix/2026-02-24-worker-runtime-clarification-bug-handoff.md`
2. `docs/plans/bugfix/2026-02-25-runtime-bug-status-technical-report.md`

已更新并与当前实现对齐的文档：

1. `docs/06-git-worktree-workflow.md`
2. `docs/09-control-plane-api-contract.md`
3. `docs/deployment/docker-runtime.md`
4. `docs/README.md`
5. 本快照：`docs/plans/status/2026-02-25-project-snapshot.md`

## 5. 文档正确但实现仍偏离的点（登记）

1. 已收敛项：`session.complete` 的交付证明校验已接入 git 实现，不再是固定返回。
2. 当前保留风险（非架构偏离，属于策略参数化问题）：
   - VERIFY 基础设施失败判定采用规则匹配（work_report 关键词），后续可升级为结构化错误码。
   - 冲突修复任务去重目前采用“按状态扫描 + 标题前缀”策略，规模增大后建议补专门关联字段。

## 6. 离“完整闭环可交付系统”还缺什么

P0（必须补齐）：

1. 扩展 VERIFY 失败分类为结构化错误码（替代纯文本规则），进一步降低误判重试/误回退概率。
2. 为冲突修复任务补“来源任务”显式关联字段，避免超大规模下标题匹配去重不稳。

P1（扩展能力，非推倒重来）：

1. 强化架构师任务编排策略：复杂场景稳定产出 `impl + test + verify + bugfix` 组合，不只依赖保守 fallback。
2. 标准化“模块脚本测试任务”模板与验收证据结构，减少临场拼装。
3. 建立真实场景回归集（图书管理、学生管理等）并形成可重复自动化评估脚本。

## 7. 关于“主要不足在哪里”的直接回答

1. 是的，核心短板主要集中在两处：
   - 架构师派发与编排策略的稳定性、覆盖深度
   - Worker 交付后的合并门禁/完成证明闭环
2. 当前阶段建议“在现有实现上扩展能力”，而不是重做架构：
   - 基础模块边界与主流程方向正确
   - 已具备继续叠加门禁与策略的工程基础
   - 需要的是补关键缺口和提升编排质量，不是推翻重建

## 8. 轨道判断

1. 若目标是“按文档建设可扩展控制平面”，目前在轨。
2. 若目标是“稳定自主产出并交付简单系统”，目前尚未达标，但路径清晰且可在当前实现上收敛达成。
