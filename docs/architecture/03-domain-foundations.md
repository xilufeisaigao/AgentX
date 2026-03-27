# Domain 基础语义

这份文档只回答两个问题：

1. 当前 `domain` 层里，哪些对象是聚合根。
2. 当前 `domain` 层里，哪些小值对象值得单独建模。

目标不是套一整套重 DDD 术语，而是先把固定 workflow 的一致性边界钉住，避免后面状态机、LangGraph、MyBatis 和控制面各写一套真相。

## 1. 什么是聚合根

在这个项目里，聚合根就是“一个局部一致性边界的入口对象”。

它承担的责任很具体：

1. 外界的命令优先落到聚合根，而不是直接操作它下面的附属表。
2. 跟它强绑定的一组状态和规则，由它所在的聚合负责维持一致性。
3. 持久化读写优先围绕聚合根组织，而不是退化成“一张表一个 CRUD service”。
4. 跨聚合协作通过 ID、命令、事件或流程编排完成，不直接共享可变内部状态。

这也是为什么我们不按“数据库每张表一个实体包”去拆。那种拆法会把 `ticket_events`、`requirement_doc_versions`、`task_run_events` 这种附属记录误当成独立业务中心，后面代码会迅速变成胶水。

## 2. 当前聚合根规划

| 切片 | 聚合根 | 为什么是它 | 暂不提升为聚合根的对象 |
| --- | --- | --- | --- |
| `catalog` | `AgentDefinition` | Agent 是平台资产的直接管理对象，用户和架构代理看到的都是 Agent。 | `AgentCapabilityBinding` 是 Agent 的附属绑定，不单独管理。 |
| `catalog` | `CapabilityPack` | 平台调度围绕 capability pack，而不是 raw tool/skill。 | `CapabilitySkillGrant`、`CapabilityToolGrant`、`CapabilityRuntimeRequirement` 都是包的附属展开。 |
| `flow` | `WorkflowTemplate` | 固定 workflow 的结构边界由模板定义。 | `WorkflowTemplateNode` 是模板内部节点，不单独暴露生命周期。 |
| `flow` | `WorkflowRun` | 顶层流程实例是真正的流程一致性入口。 | `WorkflowNodeBinding`、`WorkflowNodeRun`、`WorkflowRunEvent` 先作为 run 的附属记录。 |
| `intake` | `RequirementDoc` | 需求闭环围绕文档状态和版本演进。 | `RequirementVersion` 是不可变附属版本，不单独当业务入口。 |
| `intake` | `Ticket` | 人类介入统一通过 ticket 流转。 | `TicketEvent` 只承担审计和对话轨迹。 |
| `planning` | `WorkTask` | 真正要被调度和执行的是 task，不是 module。 | `WorkModule` 只是 task 分组；`TaskDependency`、`TaskCapabilityRequirement` 是 task 的附属结构。 |
| `execution` | `TaskRun` | 执行尝试、重试、失败、交付候选都围绕 run 展开。 | `TaskRunEvent` 是 run 事件流；`GitWorkspace`、`TaskContextSnapshot`、`AgentPoolInstance` 当前先视为支撑工件。 |

## 3. 当前不这么做的几类对象

下面这些对象现在不当聚合根，是刻意收缩复杂度，不是遗漏：

### `WorkModule`

它只是架构拆分后的任务分组，不是独立业务中心。

如果把 `WorkModule` 做成主聚合根，后面容易出现：

1. task 生命周期写在 module service 里。
2. module 和 task 互相抢状态真相。
3. DAG 关系在 module、task、dependency 三边分散。

当前更合理的做法是：

1. `WorkflowRun` 负责顶层流程阶段。
2. `WorkTask` 负责真正的执行语义。
3. `WorkModule` 仅作为规划分组实体存在。

### `TaskContextSnapshot`

它有独立表和独立状态，但目前先不提升为业务主聚合根。

原因是：

1. 当前它更像 `TaskRun` 派发前必须引用的支撑工件。
2. 它的主要规则还没脱离执行状态机。
3. 等后面讨论“快照失效 / 恢复 / 重编译”状态机时，再决定是否升级为独立聚合根更稳。

### `GitWorkspace`

它是执行产物，不是当前阶段要暴露给业务命令的中心对象。

后面如果 merge gate 和 cleanup 逻辑复杂到需要独立命令面，再考虑提升。

## 4. 什么是值对象

在这个项目里，值对象就是：

1. 没有独立生命周期。
2. 只靠值本身判等。
3. 一旦创建就不可变。
4. 可以把原本零散的字符串或数字约束收进构造器。

当前值对象只挑了几个真的有边界含义的地方，不搞“所有 ID 都包一层 class”的重度包装。

## 5. 当前值对象规划

### `ActorRef`

位置：`domain.shared.model.ActorRef`

表示“谁触发了这件事”。

为什么单独建模：

1. 系统里到处都有 `actor_type + actor_id` 这个组合。
2. 这是天然的二元值对象，而不是两个散装字段。
3. 后面状态机和事件流都会依赖它。

### `WriteScope`

位置：`domain.shared.model.WriteScope`

表示 task 允许写入的单个路径边界。

为什么单独建模：

1. 写域不是普通字符串，它代表执行安全边界。
2. 后面 Docker / Git worktree / merge gate 都会依赖这个边界。
3. 先把路径规范化收口，避免各层自己 trim、replace、校验。

当前设计刻意只做到单路径值对象，不额外引入 `WriteScopeSet` 之类集合包装，避免过度设计。

### `JsonPayload`

位置：`domain.shared.model.JsonPayload`

表示领域模型里需要保留但又不在当前阶段展开为强类型结构的 JSON 载荷。

当前使用点：

1. `Ticket.payloadJson`
2. `TaskRun.executionContractJson`

为什么单独建模：

1. 这两个字段都不是普通备注文本，而是结构化契约。
2. 现在如果直接用 `String`，后面 repository、测试、应用层都容易传入空串或非 JSON 形态。
3. 先用一个轻值对象把“必须是结构化 JSON”这个约束守住，等未来字段稳定后再考虑进一步强类型化。

## 6. 当前刻意不做的值对象

为了控制复杂度，下面这些暂时不做：

1. 每个 ID 一种类。
2. 每个标题、一段描述都包成文本值对象。
3. 所有 JSON 字段都立即强类型化。
4. 所有枚举状态都包成 state wrapper。

当前阶段更重要的是把真正影响流程边界的地方先收住，而不是把领域模型包装成“对象数量爆炸”。

## 7. 代码落点

当前这套基础语义已经落到 `domain` 代码中：

1. `AggregateRoot<ID>`
2. `ValueObject`
3. `ActorRef`
4. `WriteScope`
5. `JsonPayload`

并且这些语义已经接入以下核心模型：

1. `AgentDefinition`
2. `CapabilityPack`
3. `WorkflowTemplate`
4. `WorkflowRun`
5. `RequirementDoc`
6. `Ticket`
7. `WorkTask`
8. `TaskRun`

## 8. 对后续状态机的意义

这一步的作用是给状态机设计先搭边界：

1. 先明确“状态机挂在哪个聚合根上”。
2. 再明确“附属对象是被谁驱动变化的”。
3. 最后才决定 LangGraph 节点怎么调这些聚合。

接下来讨论状态机时，优先顺序应该是：

1. `WorkflowRun`
2. `RequirementDoc`
3. `Ticket`
4. `WorkTask`
5. `TaskRun`

不要一上来把所有表拉进一张超大状态图。
