# AgentX Platform Docs

这套文档只做三件事：

1. 固化平台的三层代码架构。
2. 固化当前固定 coding workflow。
3. 固化数据库 30 张表的分层真相和关系图。

## 推荐阅读顺序

1. [三层架构](architecture/01-three-layer-architecture.md)
2. [固定工作流](architecture/02-fixed-coding-workflow.md)
3. [领域基础语义](architecture/03-domain-foundations.md)
4. [状态机分层图](architecture/04-state-machine-layers.md)
5. [L1-L3 状态机交互](architecture/05-l1-l3-state-machine-interactions.md)
6. [数据库分层关系图](database/01-table-layer-map.md)
7. [数据库建表 SQL](../db/schema/agentx_platform_v1.sql)

## 文档范围

### 架构文档

- `architecture/01-three-layer-architecture.md`
  - 解释 `domain / controlplane / runtime` 三层。
  - 说明三层和数据库五层之间的映射关系。
- `architecture/02-fixed-coding-workflow.md`
  - 说明当前固定 workflow 的节点、循环和人类介入面。
- `architecture/03-domain-foundations.md`
  - 说明当前 domain 层的聚合根和值对象规划。
  - 说明为什么不按“每张表一个实体包”来拆。
- `architecture/04-state-machine-layers.md`
  - 给出当前状态机设计的分层总览。
  - 说明后续状态机讨论的自顶向下顺序。
- `architecture/05-l1-l3-state-machine-interactions.md`
  - 展开 L1 `WorkflowRun`、L2 `WorkflowNodeRun`、L3 `RequirementDoc / Ticket` 的交互。
  - 归档三层状态机的小场景流转。

### 数据库文档

- `database/01-table-layer-map.md`
  - 给出 30 张表的整体层次图。
  - 给出每个层次内部的单独关系图。
  - 说明每层是谁的真相来源。

## 当前结论

1. 平台不做自由工作流编辑器。
2. 平台的主要可扩展面是 Agent、Capability Pack、Agent Pool。
3. Skill、Tool、Runtime 必须分层，不能糊成一张万能表。
4. Requirement / Ticket / Task / Run / Workspace 是当前固定 coding workflow 的最小主链路。
