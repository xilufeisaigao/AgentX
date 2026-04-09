# AgentX Platform Docs

这套文档只保留当前 runtime 内核真正需要的高价值资料，目标是让“先读什么、真相在哪、哪些东西已完成/仍延期”保持稳定。

## 主线设计阅读顺序

这组文档用于讲清“AgentX 现在对外主线想怎么做”，适合项目介绍、方案沟通和面试表达。

1. [三层架构](architecture/01-three-layer-architecture.md)
2. [固定工作流与 LangGraph 结构](architecture/02-fixed-coding-workflow.md)
3. [领域基础语义](architecture/03-domain-foundations.md)
4. [状态机设计（L1-L5）](architecture/04-state-machine-layers.md)
5. [Unix 探索式 Coding Context 主线方案](runtime/07-unix-exploration-coding-context-design.md)
6. [Agent 能力升级蓝图](runtime/08-agent-capability-upgrade-design.md)
7. [Repo Graph Lite 设计](runtime/09-repo-graph-lite-design.md)
8. [审批处理中心设计](runtime/10-approval-processing-center-design.md)
9. [Eval Center V1 总览](evaluation/01-eval-center-overview.md)
10. [Eval 维度目录](evaluation/02-dimension-catalog.md)
11. [Workflow Eval Report Schema](evaluation/03-workflow-report-schema.md)
12. [Scenario Pack 与 Regression](evaluation/04-scenario-pack-and-regression.md)
13. [Eval Report Reader Skill](evaluation/05-agentx-eval-report-reader-skill.md)
14. [严格真实 Workflow Scenario Pack](evaluation/06-real-workflow-scenario-pack.md)
15. [Controlplane V1 Command API](controlplane/01-controlplane-v1-command-api.md)
16. [面试题归档](interview/README.md)

## 实现基线 / 归档对照

这组文档用于回答“当前代码到底实现了什么”，适合做代码真相核对和实现对照。

1. [Runtime V1 实现说明](runtime/01-runtime-v1-implementation.md)
2. [Runtime 基础设施设计](runtime/02-runtime-v1-infrastructure.md)
3. [上下文编译中心](runtime/03-context-compilation-center.md)
4. [本地 RAG 与代码索引（当前实现真相）](runtime/04-local-rag-and-code-indexing.md)
5. [Runtime 收口评估](runtime/05-runtime-closure-assessment.md)
6. [归档：分层向量 RAG 设计](runtime/06-layered-vector-rag-design.md)
7. [真实 LLM 全链路 Smoke Findings](agentkernel/01-real-llm-full-flow-smoke-findings.md)
8. [数据库分层真相](database/01-table-layer-map.md)
9. [数据库建表 SQL](../db/schema/agentx_platform_v1.sql)
10. [Deferred 清单](deferred/01-runtime-v1-deferred.md)

## 每份文档回答什么

| 文档 | 主要回答的问题 |
| --- | --- |
| `architecture/01-three-layer-architecture.md` | 代码为什么只分 `domain / controlplane / runtime` 三层，以及三层边界是什么 |
| `architecture/02-fixed-coding-workflow.md` | 固定工作流有哪些节点、哪些是 Agent、LangGraph 顶层图怎么挂 |
| `architecture/03-domain-foundations.md` | 哪些对象是聚合根，哪些值对象是必须保留的 |
| `architecture/04-state-machine-layers.md` | L1-L5 状态机怎么分层、怎么交互、L4/L5 怎么衔接 |
| `runtime/01-runtime-v1-implementation.md` | Runtime V1 当前的异步内核到底怎么跑，节点职责、稳定点、写库点和恢复入口分别是什么 |
| `runtime/02-runtime-v1-infrastructure.md` | Docker CLI、单仓 Git worktree、中央派发、监督恢复、merge/verify 证据链是怎么落地的 |
| `runtime/03-context-compilation-center.md` | 四个 agent 的上下文包是怎么统一编译、落证据和做 fingerprint 的 |
| `runtime/04-local-rag-and-code-indexing.md` | 当前代码真相里的本地 lexical/symbol RAG、repo index、workflow overlay index、Java symbol retrieval 是怎么落地的 |
| `runtime/05-runtime-closure-assessment.md` | 当前 runtime 是否已经与三层架构/L1-L5 保持一致，哪些缺口已经收束为基础设施完成项，哪些仍然是效果升级项 |
| `runtime/06-layered-vector-rag-design.md` | 已归档的旧目标方案；保留历史设计上下文，不再作为下一阶段主方向 |
| `runtime/07-unix-exploration-coding-context-design.md` | 新的目标方案：保留结构化事实层，移除 coding 主路径上的代码 RAG，改用 Unix 类工具探索代码，并引入读宽写窄的权限隔离 |
| `runtime/08-agent-capability-upgrade-design.md` | 下一阶段能力升级总稿：requirement completeness gate、审批处理中心、spec-first/verify-first、repo graph lite、write scope overlap governance 如何接回固定主链 |
| `runtime/09-repo-graph-lite-design.md` | 轻量代码图的内容、构建方式和使用方式：节点/边模型、高扇入统计、exploration roots、公共组件候选和高影响面提示 |
| `runtime/10-approval-processing-center-design.md` | 审批处理中心如何把资源请求、契约校验、异步审批、grant 复用和架构师唤起统一收口；资源授权账本和外部集成契约都是其中的持久化事实 |
| `evaluation/01-eval-center-overview.md` | Eval Center 为什么存在、挂在哪、输出哪些 artifact、与 runtime 主链怎么衔接 |
| `evaluation/02-dimension-catalog.md` | 9 个评测维度分别评什么、看哪些证据、哪些是 hard gate、复杂 DAG 和 RAG 召回率怎么评 |
| `evaluation/03-workflow-report-schema.md` | `raw-evidence.json / scorecard.json / workflow-eval-report.md` 的固定结构和字段语义 |
| `evaluation/04-scenario-pack-and-regression.md` | scenario pack 应该怎么设计、baseline 和 regression 应该怎么比较 |
| `evaluation/05-agentx-eval-report-reader-skill.md` | 项目专属报告解读 skill 的输入、输出、限制和归因分类法 |
| `evaluation/06-real-workflow-scenario-pack.md` | 严格真实 workflow 评测如何通过 scenario pack、strict runner 和 stop policy 统一扩展 |
| `controlplane/01-controlplane-v1-command-api.md` | 第一批控制面为什么只做 command API、该管哪些命令、为什么管理 AgentDefinition 而不是 runtime instance |
| `agentkernel/01-real-llm-full-flow-smoke-findings.md` | 最新一次真实 DeepSeek 学生管理系统全链路 smoke 的真实结果、fallback 节点、导出代码位置和下一步优化点 |
| `database/01-table-layer-map.md` | 30 张表的五层真相、主要写入方，以及 dispatcher/supervisor 高频读取面分别是谁 |
| `deferred/01-runtime-v1-deferred.md` | 当前刻意不做的 embeddings/vector、prompt 质量升级、K8s、多仓、多租控制面和观测治理能力有哪些 |
| `interview/README.md` | 面试题归档总索引；问题按主题文档分类维护，不按会话顺序堆叠 |

## 当前结论

1. 平台不做自由工作流编辑器，只做固定主链上的 Agent 平台。
2. 可扩展面优先放在 Agent、Capability Pack、Agent Runtime、Supervisor，而不是工作流拓扑。
3. `Requirement -> Ticket -> Task -> Run -> Workspace` 是固定主链。
4. 状态真相在 `domain + MySQL`，LangGraph 只做顶层 reconciliation，不做业务真相源。
5. 任务执行采用中心派发制，不采用 worker 自抢任务。
6. Runtime V1 已完成真实 Docker CLI 适配、真实 Git worktree、中央 dispatcher、进程内 supervisor 和真实 merge/verify 基线。
7. 当前 requirement / architect / coding / verify 已经共享统一的 context compilation center，并具备本地 lexical/symbol 检索基础设施。
8. coding 主链已经收口到统一 `ToolCall` 协议，`callId` 与标准化执行证据可支持重复 tick 下的幂等复用。
9. `tickets.task_id` 已成为 `TASK_BLOCKING` 的显式真相字段，runtime/readiness/eval 的基础设施已具备下一步 controlplane/UI 所需的查询前提。
10. command-side controlplane baseline 已具备正式 HTTP 入口，当前真正延期的是 query/display/UI，而不是 workflow / ticket / requirement / agent 的人工命令能力。
11. Eval Center V1 已具备文件优先的离线评测输出能力，当前可以对 baseline agent eval 和真实 workflow smoke 生成统一三件套报告。
12. 下一阶段的效果优化应优先围绕 eval scenario、报告解读和回归比较推进，而不是重新发明主链概念。
13. 当前代码真相仍然是 lexical / symbol baseline，但 coding 阶段的下一步目标方向已经切到“结构化事实 + Unix 探索工具”，不再继续把代码 RAG 做成主路径。
