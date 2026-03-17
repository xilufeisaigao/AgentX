# Interview

本目录用于沉淀 AgentX 项目的面试材料，但回答时应始终以仓库当前实现为准，而不是只背设计稿。

当前文档结构：
1. `agentx-00-项目总述和关键问题.md`
   - 适合先讲 30 秒电梯陈述，再讲整套控制面的核心价值、成熟度判断、设计边界。
2. `agentx-01-基于事件驱动的模块化架构.md`
   - 重点讲模块边界、状态机归属、Spring Event + process 编排，以及为什么先做模块化单体。
3. `agentx-02-沙箱隔离与Worker动态调度.md`
   - 重点讲 worker 能力边界、租约/心跳、自动调度、Git worktree、门禁和当前“本地执行 + 可选 VERIFY Docker”的落地方式。
4. `agentx-03-上下文编译中心.md`
   - 重点讲上下文快照、事实账本、LangChain4j 语义检索 + lexical fallback、`task_context_ref/task_context_pack`、worker 的 `task_evidence_snapshot/workspace_snapshot` 与 `role_context_pack`。
5. `agentx-04-决策面工单化HITL机制.md`
   - 重点讲 NEED_DECISION / NEED_CLARIFICATION 如何进入 ticket 流程、用户回复后如何刷新上下文并恢复调度。

建议阅读顺序：
1. 先读 `agentx-00-项目总述和关键问题.md`
2. 再按“架构 -> 执行/调度 -> 上下文 -> HITL”顺序读 `01 -> 02 -> 03 -> 04`

使用这些文档时，建议坚持一个诚实表述方式：
1. 先说“设计目标”是什么。
2. 再说“当前代码里已经落地到哪一步”。
3. 最后补一句“还没有做完的工程化部分”。

当前实现的总体口径可以统一为：
1. 已经是一个有明确控制面的 Agent 工程系统，而不只是脚本拼接。
2. 核心工业模式已经落地：模块化单体、事件驱动编排、任务/运行/工单三条状态机、上下文快照门禁、Git worktree、Merge Gate、HITL。
3. 仍有增强空间：`.agentx` 证据索引、更严格的容器/策略隔离、MQ/outbox、可观测性与调度优化。
