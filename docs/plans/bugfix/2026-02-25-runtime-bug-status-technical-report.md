# AgentX Runtime Bug Status Technical Report

更新时间：2026-02-25 09:44:50 +08:00  
代码基线：`0478b85`（工作区未提交改动 113 项）

## 1. 报告目的

本报告用于汇总当前阶段的 bug 现状，回答以下问题：

1. 当前“卡死/阻塞”是否仍在发生。
2. 当前是否存在明确后端回归 bug。
3. 原 `2026-02-24-worker-runtime-clarification-bug-handoff.md` 列出的问题目前处于什么状态。
4. 哪些是后端逻辑问题，哪些是环境或测试链路问题。

本报告聚焦事实证据，不做“已彻底修复”的结论承诺。

## 2. 本次证据来源

本次主要证据文件：

1. `target/full-suite-requirement-api-1771944615087.log`
2. `target/full-suite-requirement-agent-api-1771944615087.log`
3. `target/full-suite-ticket-api-1771944615087.log`
4. `target/full-suite-spring-boot-1771944615087.log`
5. `docs/plans/bugfix/2026-02-24-worker-runtime-clarification-bug-handoff.md`

补充运行态观察：

1. 当前未检测到残留 `spring-boot:run` 进程。
2. 当前未检测到残留 `serve_followup_demo.py` 进程。
3. 最近文件改动集中在 `target/`（测试编译产物），未观察到持续异常抖动。

## 3. 总体结论（先给结论）

当前是“后端回归 bug + 环境干扰并存”，不是纯环境问题，也不是纯后端问题。

明确结论：

1. 存在可稳定复现的后端行为回归：`architect auto-processor` 未按预期为特定 ticket 生成 `DECISION_REQUESTED` 事件。
2. 同时存在环境级干扰因素：Redis 连通性告警、历史僵尸进程干扰、Docker 本地守护进程可用性波动。
3. 当前没有证据表明“持续创建/删除文件”的问题仍在进行中。

## 4. 回归结果矩阵

### 4.1 通过项

1. `Requirement API`：5/5 通过  
   证据：`target/full-suite-requirement-api-1771944615087.log`
2. `Requirement Agent API`：6/6 通过  
   证据：`target/full-suite-requirement-agent-api-1771944615087.log`

### 4.2 失败项

1. `Ticket API`：6 个用例中 1 个失败  
   失败用例：`test_architect_auto_processor_generates_decision_and_clarification`  
   失败断言：`handoff_request` 为空（未观察到 `DECISION_REQUESTED`）
2. 证据：`target/full-suite-ticket-api-1771944615087.log`
3. 失败位置：`tests/python/ticket_api/test_ticket_api.py:351`

## 5. 已确认后端回归问题（P0）

### BUG-RG-001：Architect auto-process 未稳定产出 `DECISION_REQUESTED`

#### 现象

在 `HANDOFF + ARCH_REVIEW` 自动处理场景中，`HANDOFF` ticket 未出现预期的 `DECISION_REQUESTED` 事件，导致测试失败。

#### 复现路径（已在回归中复现）

1. 创建会话级 `HANDOFF` ticket 与 `ARCH_REVIEW` ticket。
2. 调用 `/api/v0/architect/auto-process`。
3. 等待 `HANDOFF` ticket 的 `DECISION_REQUESTED` 事件。
4. 实际结果：`HANDOFF` 事件缺失，断言失败。

#### 证据

1. 失败日志：`target/full-suite-ticket-api-1771944615087.log`  
   关键断言：`AssertionError: unexpectedly None`
2. 用例代码：`tests/python/ticket_api/test_ticket_api.py:318` 至 `tests/python/ticket_api/test_ticket_api.py:367`
3. 服务日志显示 auto-processor 确实消费了 ticket：  
   `target/full-suite-spring-boot-1771944615087.log` 中出现  
   `Architect auto-processor handled ...`（例如行 3579、6034、8841）

#### 影响

1. 架构自动处理链路不稳定，可能出现“票据被处理但用户侧没有提问事件”的假成功状态。
2. 直接影响你当前关注的“clarification/decision 闭环推进可靠性”。
3. 会放大“任务看似推进、实际上缺失关键人机交互”的风险。

#### 根因假设（待代码级确认）

结合当前代码结构，优先怀疑：

1. `ArchitectTicketAutoProcessorService` 的阶段判定与事件写入时序存在分支竞态或状态短路。
2. ticket 在 `claim -> comment -> decision_requested` 链路中可能被其他流程（或二次轮询）提前改写状态，导致预期事件未落库。
3. `HANDOFF` 与 `ARCH_REVIEW` 在同轮处理时的条件判定不完全对称。

重点排查文件：

1. `src/main/java/com/agentx/agentxbackend/process/application/ArchitectTicketAutoProcessorService.java`
2. `src/main/java/com/agentx/agentxbackend/ticket/application/TicketCommandService.java`
3. `src/main/java/com/agentx/agentxbackend/ticket/infrastructure/persistence/TicketEventMapper.java`

## 6. 原 BUG 清单状态（截至本报告）

以下状态对应 `docs/plans/bugfix/2026-02-24-worker-runtime-clarification-bug-handoff.md` 的 BUG-001~006：

1. BUG-001（`USER_RESPONDED` 后 run 恢复）  
   状态：代码有较大改动，尚未完成端到端验收闭环；当前无“已完全修复”证据。
2. BUG-002（前端 worker 视图与后端不一致）  
   状态：前后端相关代码已变更，但本轮未完成专门 UI/E2E 复核。
3. BUG-003（语言不遵从配置）  
   状态：运行时配置链路有改动，尚未做中英文切换回归证据收集。
4. BUG-004（ticket 事件顺序）  
   状态：未见明确“稳定顺序”验证证据；仍需高频 append 场景验证。
5. BUG-005（run_need_input 工单堆积）  
   状态：已有去重/supersede 相关实现，但需结合真实流量验证“不会误伤”。
6. BUG-006（context pack 未注入 worker prompt）  
   状态：执行链路有改动，但尚未完成“提示词里可见上下文字段”的抓证验收。

结论：BUG-001~006 当前都不能做“完全关闭”判定，仍处于“部分实现，待验收”。

## 7. 非后端逻辑问题（会干扰判断）

### ENV-001：历史僵尸进程干扰

现象：曾出现外部目录进程（`serve_followup_demo.py`）持续运行，造成误判为“本项目流程还在后台执行”。  
当前状态：已清理，当前未检测到。

### ENV-002：Redis 连通性波动

证据：`target/full-suite-spring-boot-1771944615087.log` 出现 `Unable to connect to Redis`。  
影响：会影响 requirement discovery history 等依赖 Redis 的行为，导致“功能异常”与“环境异常”混淆。

### ENV-003：Docker 可用性波动

现象：本地 `docker ps` 存在“daemon 不可用”时段。  
影响：会导致容器态回归与本机直跑回归结果不一致。

### TEST-001：测试脚本对空密码场景不健壮（已修正）

问题：部分 Python 测试脚本总是拼接 `-p{DB_PASSWORD}`，空密码时可能导致交互式行为或错误。  
已做修正：改为“仅在密码非空时才传 `-p...`”。  
受影响文件（示例）：

1. `tests/python/requirement_api/test_requirement_api.py`
2. `tests/python/requirement_agent_api/test_requirement_agent_api.py`
3. `tests/python/ticket_api/test_ticket_api.py`
4. `tests/python/ticket_api/test_architect_auto_scheduler_flow.py`
5. `tests/python/execution_api/test_execution_worker_claim_api.py`
6. `tests/python/workforce_runtime/test_auto_provision_and_lease_recovery.py`
7. `tests/python/workforce_runtime/stress_worker_claim_concurrency.py`
8. `tests/python/workforce_runtime/stress_claim_mixed_toolpack_backlog.py`

说明：该项属于测试稳定性改进，不是业务后端逻辑修复。

## 8. 风险评估

当前最高风险点：

1. auto-process 事件丢失会直接破坏人机交互闭环，影响流程可信度。
2. 多处流程并发调度（scheduler + manual trigger）导致状态切换复杂，容易引入“看起来处理过、关键事件未持久化”的隐性回归。
3. 环境波动（Redis/Docker）会污染回归信号，若不先稳定环境，容易误判代码质量。

## 9. 建议的下一步执行顺序

1. 先修复并锁定 `BUG-RG-001`（以 `tests/python/ticket_api/test_ticket_api.py:318` 场景为准）。
2. 完成最小回归：`requirement_api`、`requirement_agent_api`、`ticket_api` 三件套必须全绿。
3. 在环境稳定后再进入两个真实业务场景（学生管理系统、图书管理系统）的大模型实测。
4. 最后回头逐项关闭 BUG-001~006 的验收条件，避免“代码改了但状态未关闭”。

---

如果后续需要，我可以基于本报告直接追加下一份文档：  
`2026-02-25-bug-rg-001-root-cause-and-fix-plan.md`，专门用于跟踪 `ArchitectTicketAutoProcessorService` 的根因和修复动作。
