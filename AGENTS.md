# AgentX Platform Development Guide

本文件是项目内的开发规范入口，目标只有两个：

1. 让后续编码始终围绕当前固定架构推进，不再长出胶水代码森林。
2. 让新开工的 agent 能快速找到真相来源、代码落点和编码约束。

## 1. 项目定位

这是新的 greenfield Agent 平台内核，不延续旧控制面的胶水式堆叠。

当前已经冻结的核心判断：

1. 平台不做自由工作流编辑器，只做固定主链上的 Agent 平台。
2. 顶层流程固定，LangGraph 只编排顶层固定节点。
3. task 执行采用中心派发制，不采用 worker 自抢任务。
4. `架构代理` 负责规划、重规划、提请分流。
5. `工作代理管理器` 负责派发。
6. `运行监督器` 负责 heartbeat、lease、超时恢复与异常升级。
7. `DELIVERED != DONE`
8. `TaskRun.SUCCEEDED != WorkTask.DONE`
9. `GitWorkspace.MERGED != WorkTask.DONE`

## 2. 真相来源与必读文档

开始动代码前，先看这些文档，不要凭印象写：

1. `docs/README.md`
   - 文档总索引和阅读顺序
2. `docs/architecture/01-three-layer-architecture.md`
   - 三层代码架构和边界
3. `docs/architecture/02-fixed-coding-workflow.md`
   - 顶层固定流程、LangGraph 结构、中心派发、运行监督器位置
4. `docs/architecture/03-domain-foundations.md`
   - 聚合根和值对象边界
5. `docs/architecture/04-state-machine-layers.md`
   - L1-L5 状态机、L4/L5 关系、运行监督规则
6. `docs/database/01-table-layer-map.md`
   - 数据库五层真相和主要写入方
7. `db/schema/agentx_platform_v1.sql`
   - 当前表结构真相
8. `progress.md`
   - 当前实现阶段、验收方式、最近工作

优先级规则：

1. 表结构真相看 `db/schema/agentx_platform_v1.sql`
2. 架构边界真相看 `docs/architecture/*.md`
3. 当前计划和阶段边界看 `progress.md`

## 3. 项目结构索引

当前主要代码结构如下：

### `src/main/java/com/agentx/platform/domain`

领域层，只放模型、状态机、不变量、领域端口。

当前主要切片：

1. `shared`
   - 通用值对象、错误类型
2. `catalog`
   - Agent / Capability Pack / Skill / Tool 相关领域模型
3. `flow`
   - `WorkflowTemplate`、`WorkflowRun`、`WorkflowNodeRun`
4. `intake`
   - `RequirementDoc`、`Ticket`
5. `planning`
   - `WorkTask`、依赖和能力需求相关规则
6. `execution`
   - `TaskContextSnapshot`、`TaskRun`、`GitWorkspace`

### `src/main/java/com/agentx/platform/controlplane`

控制编排面，只放固定工作流编排和对外控制面能力。

当前目录：

1. `api`
2. `application`
3. `config`
4. `support`

### `src/main/java/com/agentx/platform/runtime`

执行面，只放运行时适配、派发、执行和监督。

当前目录：

1. `application`
2. `orchestration`
   - LangGraph 顶层图挂载点
3. `agentruntime`
   - agent runtime 适配
4. `persistence`
   - MyBatis repository / mapper / typehandler
5. `workspace`
   - Git worktree
6. `retrieval`
7. `support`

### 当前主要数据库真相层

1. L1 平台资产层
2. L2 固定流程定义层
3. L3 流程编排运行层
4. L4 需求与人工介入层
5. L5 规划与交付执行层

不要让代码结构退化成“每张表一个 service + 一个 DTO + 一个 mapper helper”。

## 4. 依赖与分层规则

硬规则：

1. `controlplane -> domain`
2. `runtime -> domain`
3. `controlplane <-> runtime` 只能通过显式 command / port / event 交互。
4. 不允许把数据库表直接当跨层公共 API。
5. `task` 只绑定 capability requirement，不直接绑定固定 agent。
6. worker 不直接找人，人工介入统一走 `tickets`。
7. `runtime` 不能直接改写 `RequirementDoc`、`Ticket` 的业务语义。
8. LangGraph 不承载业务真相，只承载顶层编排。

## 5. 编码风格核心原则

### 5.1 先保主链，再补扩展

新增代码先问自己两个问题：

1. 这是不是 Requirement -> Ticket -> Task -> Run -> Workspace 主链上的必要代码？
2. 这段代码如果不写，会不会直接挡住当前阶段目标？

如果答案都是否，就不要急着写。

### 5.2 不做占位抽象

不允许为了“未来可能会用”而引入：

1. 多余的 manager / helper / facade / adapter 层
2. 泛化过头的策略接口
3. 没有第二个实现的抽象
4. 只有转发作用的 service

判断标准很简单：

如果一个类的主要工作只是把参数搬到另一个类，不增加边界价值，就不要建。

### 5.3 不发明平行概念

当前已经有这些核心术语：

1. `WorkflowRun`
2. `WorkflowNodeRun`
3. `RequirementDoc`
4. `Ticket`
5. `WorkTask`
6. `TaskContextSnapshot`
7. `TaskRun`
8. `GitWorkspace`

后续不要轻易再造：

1. `Job`
2. `Mission`
3. `Assignment`
4. `WorkItem`
5. `ExecutionContext`
6. `TaskExecution`

如果只是已有概念换个名字，就是制造混乱。

## 6. 注释规范

代码要有重点注释，但只能写高信号注释，不能写流水账。

### 6.1 应该写注释的地方

1. 状态迁移的关键约束
2. 跨层转换的边界原因
3. lease / heartbeat / recovery 的非直觉逻辑
4. 为什么这里不能直接调用另一层
5. 为什么这里必须 fail fast
6. 临时设计折中和后续替换点

### 6.2 不该写注释的地方

1. 对着代码复述“给字段赋值”
2. 每个 getter / setter / record 字段解释一遍
3. 方法名已经很清楚时再翻译一遍
4. 大段无信息量的分隔线注释

### 6.3 注释写法要求

1. 优先解释 `why` 和 `invariant`，不要只解释 `what`
2. 注释要短，能一两行说清就不要写一段
3. 注释必须和当前设计一致，设计变了要一起改

一个合格例子：

```java
// TaskRun success only means the execution attempt completed.
// WorkTask can become DONE only after merge gate and verification accept the delivery.
```

## 7. 反胶水代码与反 DTO 膨胀规则

这是当前最需要严格执行的规范。

### 7.1 什么叫胶水代码

下面这些都算胶水代码高风险区：

1. 一层层 request -> dto -> vo -> entity -> po 来回复制同样字段
2. service A 只调 service B，不增加约束
3. mapper 之外又包一层纯搬运 converter
4. 一个业务动作拆成 4 个空心 helper 类

### 7.2 DTO 只在真实边界创建

允许创建 DTO 的地方：

1. HTTP request / response
2. 外部 LLM / runtime / provider 的入参与回包
3. 事件总线或异步消息契约
4. 专门的查询视图对象
5. MyBatis row 读取结果在 persistence 内部的中间映射

默认不允许创建 DTO 的地方：

1. domain -> application 之间纯搬运
2. application -> runtime 之间纯搬运
3. 同一用例内部只为了“看起来分层”而新增 DTO
4. 为每张表各造一组 `XxxDTO / XxxVO / XxxBO`

### 7.3 判断标准

如果一个对象满足下面任一情况，就不要新建：

1. 字段和来源对象 1:1 一样
2. 只是为了“显得专业”改了类名
3. 没有独立的边界语义
4. 删除后代码会更清楚

### 7.4 推荐做法

1. 优先直接使用现有领域对象或 command object
2. 只有在边界契约真的不同的时候才建新类型
3. MyBatis row mapping 留在 `runtime.persistence.mybatis` 内部，不扩散到业务层
4. 一个用例优先保持“一条清晰的数据主线”，不要横向复制对象

## 8. 异常处理与快速失败规范

异常处理必须以“快速失败、易定位、少层吞掉”为原则。

### 8.1 基本规则

1. 不允许 `catch (Exception)` 后吞掉
2. 不允许记录日志后返回 `null`
3. 不允许记录日志后假装成功继续跑
4. 不允许把真正错误偷偷降级成 boolean
5. 不允许同一个异常在多层重复打印

### 8.2 domain 层

1. 领域规则不满足时，优先抛明确的领域异常，例如 `DomainRuleViolation`
2. 状态不变量被破坏时，直接 fail fast
3. domain 不负责吞异常，也不负责兜底恢复

### 8.3 controlplane / runtime 层

1. 外部依赖失败时要补上下文再抛出
2. 异常信息里优先带这些 ID：
   - `workflowRunId`
   - `taskId`
   - `runId`
   - `ticketId`
   - `agentInstanceId`
3. 如果是后台执行场景，异常除了抛出，还要落成事件或运行证据

### 8.4 日志规则

1. 失败日志优先在边界层打一次
2. domain 层不做 noisy logging
3. 运行监督类异常要明确区分：
   - 心跳超时
   - lease 过期
   - worker 失联
   - 自动恢复失败

### 8.5 一个简单判断标准

如果出错后：

1. 你不知道是哪一个 run 挂了
2. 你不知道是哪一个 task 出的问题
3. 你只能靠 debug 才知道发生了什么

那就是异常处理没写合格。

## 9. 状态机实现规则

### 9.1 当前关键状态机

1. `WorkflowRun`
2. `WorkflowNodeRun`
3. `RequirementDoc`
4. `Ticket`
5. `WorkTask`
6. `TaskContextSnapshot`
7. `TaskRun`
8. `GitWorkspace`

### 9.2 状态机实现约束

1. 不允许绕过聚合直接改状态字段
2. 状态迁移必须能解释触发命令、守卫条件和副作用
3. L5 执行工件状态不能直接代替 L4 业务状态
4. worker 异常、心跳异常、自动恢复结果都应回写到 run/event 真相中

## 10. 派发、监督与恢复规则

### 10.1 中心派发制

当前冻结为中心派发制：

1. `架构代理` 负责拆 task 和定义 capability requirement
2. `工作代理管理器` 负责选实例和派发
3. worker 不自己抢任务

### 10.2 运行监督器

`运行监督器` 属于 runtime，不是 agent。

最小职责：

1. 接收 heartbeat
2. 检查 `lease_until`
3. 识别 worker 失联
4. 触发恢复
5. 恢复不成立时升级给 `架构代理`

### 10.3 当前租约真相

实例租约：

1. 表：`agent_pool_instances`
2. 字段：
   - `lease_until`
   - `last_heartbeat_at`

运行租约：

1. 表：`task_runs`
2. 字段：
   - `lease_until`
   - `last_heartbeat_at`

不要把 lease 状态只放在内存里。

## 11. 修改前检查清单

动代码前，先逐条过一遍：

1. 这次改动属于 `domain`、`controlplane`、`runtime` 哪一层？
2. 有没有打破“固定流程 + 中心派发”的基本策略？
3. 有没有错误地让 worker 直接找人？
4. 有没有把 L4 业务状态和 L5 工件状态混为一谈？
5. 有没有新建不必要的 DTO / VO / BO / Helper？
6. 有没有写高信号注释，而不是流水账注释？
7. 异常是不是做到快速失败、带上下文、易定位？
8. 是否更新了 `progress.md`？
9. 是否准备本地 git 提交？

## 12. 提交前检查清单

1. `git diff --check`
2. 这次新增的类，是否真的有边界价值？
3. 是否出现纯搬运方法或纯转发 service？
4. 是否新增了本该合并到现有聚合/应用服务里的碎片类？
5. 注释是否解释了关键约束而不是重复代码？
6. 异常栈里是否能直接定位到 run / task / ticket / instance？
