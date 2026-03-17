# ticket 模块

## 职责

`ticket` 是系统的人机决策面和跨阶段交接面：

- 创建 ticket
- claim ticket
- 写 ticket event
- 查询 ticket 和事件历史

当前最小闭环里，它最明显的作用是承接 `ARCH_REVIEW`。
在更复杂的场景里，它也承接 `NEED_DECISION` / `NEED_CLARIFICATION`。

## 入站入口

- API:
  [TicketController](../../src/main/java/com/agentx/agentxbackend/ticket/api/TicketController.java)
  - `listTicketsBySession`
  - `createTicket`
  - `claimTicket`
  - `listTicketEvents`
  - `appendTicketEvent`

## 主要表

- `tickets`
- `ticket_events`

## 关键代码入口

- command:
  [TicketCommandService](../../src/main/java/com/agentx/agentxbackend/ticket/application/TicketCommandService.java)
  - `createTicket`
  - `claimTicket`
  - `tryMovePlanningLease`
  - `appendEvent`
- query:
  [TicketQueryService](../../src/main/java/com/agentx/agentxbackend/ticket/application/TicketQueryService.java)
  - `listBySession`
  - `findById`
  - `listEvents`

## 在全链路里的位置

ticket 主要出现两种情形：

1. requirement confirm 后生成 `ARCH_REVIEW`
2. run 需要人工输入时，由 `process` 转成 ticket，而不是直接向用户发问

这让系统可以把“决策”和“缺信息”都统一放在 ticket 流里处理。

## 想查什么就看哪里

- 为什么 requirement confirm 之后有一张 `ARCH_REVIEW`
  - 看 [RequirementConfirmedProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RequirementConfirmedProcessManager.java)
- run 为什么变成等待用户
  - 看 [RunNeedsInputProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RunNeedsInputProcessManager.java)
- 某张 ticket 做过哪些状态变化
  - 看 `ticket_events`
  - 再看 [TicketQueryService](../../src/main/java/com/agentx/agentxbackend/ticket/application/TicketQueryService.java)

## 调试入口

- API: `GET /api/v0/sessions/{sessionId}/tickets`
- API: `GET /api/v0/sessions/{sessionId}/ticket-inbox`
- API: `GET /api/v0/tickets/{ticketId}/events`
- SQL: `select * from tickets where session_id = '<SESSION_ID>';`
- SQL: `select * from ticket_events where ticket_id = '<TICKET_ID>' order by created_at;`

## 工程优化思路

### 近期整理

- 统一 ticket `payload_json` 的 shape，减少同类 ticket 载荷格式漂移。
- 给 `ticket_events` 的 `data_json` 增加更稳定的字段约定。

### 可维护性与可观测性

- 补 ticket 生命周期状态机图和事件约束测试。
- 把“由谁创建、由谁处理、由谁关闭”的因果链在查询层直接展示出来。

### 中长期演进

- 区分“系统交接票”和“人工决策票”，减少不同类型 ticket 混在一起造成的复杂度。
- 将 ticket 演进为统一的人机协作总线，而不是只作为附属记录表。
