# AgentX Platform Docs

这套文档现在收敛为少量高价值资料，目标是让阅读顺序稳定，图少而有效。

## 推荐阅读顺序

1. [三层架构](architecture/01-three-layer-architecture.md)
2. [固定工作流与 LangGraph 结构](architecture/02-fixed-coding-workflow.md)
3. [领域基础语义](architecture/03-domain-foundations.md)
4. [状态机设计（L1-L5）](architecture/04-state-machine-layers.md)
5. [Runtime V1 实现说明](runtime/01-runtime-v1-implementation.md)
6. [数据库分层真相](database/01-table-layer-map.md)
7. [数据库建表 SQL](../db/schema/agentx_platform_v1.sql)
8. [Deferred 清单](deferred/01-runtime-v1-deferred.md)

## 每份文档回答什么

| 文档 | 主要回答的问题 |
| --- | --- |
| `architecture/01-three-layer-architecture.md` | 代码为什么只分 `domain / controlplane / runtime` 三层，以及三层边界是什么 |
| `architecture/02-fixed-coding-workflow.md` | 固定工作流有哪些节点、哪些是 Agent、LangGraph 顶层图怎么挂 |
| `architecture/03-domain-foundations.md` | 哪些对象是聚合根，哪些值对象是必须保留的 |
| `architecture/04-state-machine-layers.md` | L1-L5 状态机怎么分层、怎么交互、L4/L5 怎么衔接 |
| `runtime/01-runtime-v1-implementation.md` | Runtime V1 现在到底实现到了哪、节点职责、写库点、恢复入口分别是什么 |
| `database/01-table-layer-map.md` | 30 张表的五层真相和主要写入方分别是谁 |
| `deferred/01-runtime-v1-deferred.md` | 本轮刻意不做的真实运行时、控制面、监控、RAG 等后续升级项 |

## 当前结论

1. 平台不做自由工作流编辑器，只做固定主链上的 Agent 平台。
2. 可扩展面优先放在 Agent、Capability Pack、Agent Pool，而不是工作流拓扑。
3. Requirement / Ticket / Task / Run / Workspace 是固定主链。
4. 状态真相在 domain + MySQL，LangGraph 只做编排，不做业务真相源。
5. 任务执行采用中心派发制，不采用 worker 自抢任务。
6. 运行监督器负责 lease、heartbeat、超时恢复与异常升级，不让架构代理去盯运行态。
7. Runtime V1 已跑通固定主链和一次人工澄清恢复闭环，后续升级优先替换适配器而不是重写主链。
