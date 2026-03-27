# Domain 基础语义

这份文档只固定两件事：

1. 哪些对象是当前必须保留的聚合根。
2. 哪些值对象是真正有边界意义的。

## 聚合根

| 切片 | 聚合根 | 它拥有的真相 | 暂不提升为聚合根的对象 |
| --- | --- | --- | --- |
| `catalog` | `AgentDefinition` | Agent 注册、启停资格、能力包绑定入口 | agent 与能力包的明细绑定 |
| `catalog` | `CapabilityPack` | runtime / tool / skill 的稳定组合 | pack 内部展开记录 |
| `flow` | `WorkflowTemplate` | 固定工作流结构 | `WorkflowTemplateNode` |
| `flow` | `WorkflowRun` | 顶层流程阶段 | `WorkflowNodeRun`、`WorkflowRunEvent` |
| `intake` | `RequirementDoc` | 需求文档版本与确认状态 | `RequirementVersion` |
| `intake` | `Ticket` | 人工介入闭环 | `TicketEvent` |
| `planning` | `WorkTask` | 任务生命周期和 readiness | `WorkModule`、依赖边、能力需求 |
| `execution` | `TaskRun` | 执行尝试、重试、交付候选 | `TaskRunEvent`、`GitWorkspace`、`TaskContextSnapshot` |

## 为什么这样划分

1. 外部命令应该落到聚合根，而不是直接改附属表。
2. 跨聚合协作通过 ID、命令、事件或流程编排完成。
3. 不按“每张表一个实体”去拆，避免退化成胶水 CRUD。

## 关键值对象

| 值对象 | 作用 | 为什么必须保留 |
| --- | --- | --- |
| `ActorRef` | 标识谁触发了操作 | `actor_type + actor_id` 在系统里到处复用，必须成对出现 |
| `WriteScope` | 描述任务允许写入的路径边界 | 它是执行安全边界，不是普通字符串 |
| `JsonPayload` | 保留暂不强类型化的结构化契约 | `Ticket.payloadJson`、`TaskRun.executionContractJson` 需要基本 JSON 约束 |
| `TicketBlockingScope` | 表示 ticket 阻塞范围 | 要区分全局阻塞、任务阻塞和信息性 ticket |

## 当前明确不做的事

1. 不给每个 ID 都包一个 class。
2. 不把每段文本都建成值对象。
3. 不把所有 JSON 字段立即强类型化。
4. 不把所有附属记录都抬成聚合根。

## 对状态机设计的意义

1. `WorkflowRun / RequirementDoc / Ticket / WorkTask / TaskRun` 都有独立状态机价值。
2. `WorkModule`、`TicketEvent`、`TaskRunEvent` 这些附属记录不应该反客为主。
3. LangGraph 后面调用的是聚合命令，不是直接改表。
