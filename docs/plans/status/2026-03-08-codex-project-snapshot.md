# AgentX 项目快照（更新至 2026-03-09）

更新时间：2026-03-09  
定位：说明当前系统已经真实验证到哪一步、这轮修了哪些关键阻塞、剩余风险还在哪。

## 1. 结论

1. AgentX 现在已经可以从全新 session 自动推进到可交付 clone repo，且已完成两条真实端到端验证。
2. 本轮关键修复点已经从“流程早期卡死”推进到“可自动交付的小型 Spring Boot 需求可闭环”。
3. 当前最重要的事实不是“理论上能跑”，而是下面两条会话都已经真实完成：
   - `SES-365c1b01de6a44bfadc5bc0ae499bed2`
   - `SES-8c9b63dae1454b43ad435e3c2cdbe155`
4. 这两个 session 都已经做到：
   - requirement 草拟并确认
   - architect 自动拆解任务
   - worker 自动执行与自修复
   - `canCompleteSession=true`
   - `POST /api/v0/sessions/{id}/complete` 成功
   - `POST /api/v0/sessions/{id}/delivery/clone-repo` 成功
   - 本机 `git clone` + `mvn test` + 启动应用 + 请求接口成功

## 2. 本轮关键修复

### 2.1 `tmpl.test.v0` 不再因为“无新增改动”卡死

修复位置：

1. `src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutor.java`
2. `src/test/java/com/agentx/agentxbackend/process/infrastructure/external/LocalWorkerTaskExecutorTest.java`

修复要点：

1. 当 `tmpl.test.v0` 收到的 planner 输出全部是越界改动或 no-op 时，不再直接陷入 clarification。
2. 如果 `write_scope` 内已经存在测试文件，并且验证命令可通过，则直接复用当前 `HEAD` 标记任务成功。

效果：

1. 之前“实现任务已经顺手写完测试，后续 test task 反而卡死”的链路已打通。

### 2.2 Java 17 / Java 21 toolpack 兼容打通

修复位置：

1. `src/main/java/com/agentx/agentxbackend/workforce/application/WorkerCapabilityService.java`
2. `src/main/java/com/agentx/agentxbackend/workforce/infrastructure/external/DefaultToolpackBootstrap.java`
3. `src/main/java/com/agentx/agentxbackend/process/infrastructure/external/LocalRuntimeEnvironmentAdapter.java`
4. 相关测试：
   - `src/test/java/com/agentx/agentxbackend/workforce/application/WorkerCapabilityServiceTest.java`
   - `src/test/java/com/agentx/agentxbackend/workforce/infrastructure/external/DefaultToolpackBootstrapTest.java`
   - `src/test/java/com/agentx/agentxbackend/process/infrastructure/external/LocalRuntimeEnvironmentAdapterTest.java`

修复要点：

1. 默认 toolpack bootstrap 新增 `TP-JAVA-17`。
2. 默认 Java worker 同时绑定 `TP-JAVA-17` 与 `TP-JAVA-21`。
3. `WorkerCapabilityService` 支持“Java 21 worker 满足 Java 17 requirement”的兼容判断。
4. runtime 环境准备和 Docker 镜像选择都将 `TP-JAVA-17` 视为 Java runtime 能力。

修复原因：

1. 真实会话曾因规划结果要求 `TP-JAVA-17`，而运行池只认识 `TP-JAVA-21`，导致持续生成缺失 toolpack clarification。

效果：

1. `TP-JAVA-17` 不再阻塞任务派发。
2. 现有 Java 21 worker 能直接承接 Java 17 小型项目需求。

## 3. 已验证的真实端到端场景

### 3.1 场景 A：Greeting API

Session：

1. `SES-365c1b01de6a44bfadc5bc0ae499bed2`

需求摘要：

1. Spring Boot 3 + Java 17
2. `GET /api/greeting?name=张三`
3. 返回 `text/plain`
4. 包含 MockMvc 自动化测试和 README

运行结果：

1. session 已 `COMPLETED`
2. clone repo 已发布：
   - `git://127.0.0.1:19418/agentx-session-SES-365c1b01de6a44bfadc5bc0ae499bed2.git`
3. 本机验收：
   - `mvn test` 成功
   - `GET /api/greeting?name=张三` 返回 `Hello, 张三!`
   - `GET /api/greeting` 返回 `Hello, World!`

### 3.2 场景 B：Ping API

Session：

1. `SES-8c9b63dae1454b43ad435e3c2cdbe155`

需求摘要：

1. Spring Boot 3 + Java 17
2. `GET /api/ping`
3. 返回 `application/json`
4. 响应体固定为 `{"status":"ok","service":"agentx-demo"}`
5. 包含 MockMvc 自动化测试和 README

运行结果：

1. session 已 `COMPLETED`
2. clone repo 已发布：
   - `git://127.0.0.1:19418/agentx-session-SES-8c9b63dae1454b43ad435e3c2cdbe155.git`
3. 本机验收：
   - `mvn test` 成功
   - `GET /api/ping` 返回 `{"status":"ok","service":"agentx-demo"}`

## 4. 当前可以怎么判断“项目是否能跑”

对于“小型、约束清晰、标准 Spring Boot 3 / Java 17”这一类需求，现在可以回答：能跑。

更准确地说：

1. 你可以直接新建 session。
2. 用 requirement-agent 草拟并确认需求。
3. 系统会自动推进到任务拆解、执行、验证、交付发布。
4. 最终可以拿到真实 `git clone` 地址，并在本机完成二次验收。

但这不等于“所有复杂需求都已经百分之百稳定”。

当前结论边界：

1. 已验证的是小型后端需求闭环。
2. 复杂多模块、数据库迁移、外部依赖、多语言工程等情况仍需继续扩大回归覆盖面。

## 5. 运行态现状

当前 Docker 栈：

1. backend：`http://127.0.0.1:18082`
2. mysql：`127.0.0.1:13306`
3. redis：`127.0.0.1:16379`
4. git-export：`git://127.0.0.1:19418`

LLM 运行态：

1. requirement_llm：`bailian / qwen3-max`
2. worker_runtime_llm：`bailian / qwen3-max`
3. 通过 `GET /api/v0/runtime/llm-config` 可确认当前生效配置

## 6. 当前剩余风险

### 6.1 已有真实闭环，但覆盖面仍偏窄

1. 当前两条成功场景都属于小型生成式项目。
2. 对更复杂需求，还需要再补回归场景，例如：
   - 数据库接入
   - 多模块 Maven
   - 额外验证脚本
   - 外部 API 集成

### 6.2 query 接口形状与前端消费仍需收敛

1. `progress / task-board / ticket-inbox / run-timeline` 这批 read-model 现已可用。
2. 但当前实际返回是 camelCase 字段，前端和脚本应按运行态接口消费，不能只看 record 上的 `@JsonNaming` 预期。

### 6.3 仍需要持续做代码腐化与临时文件治理

1. 当前工作树改动较多。
2. 后续继续迭代时，要优先保持：
   - 不引入新的一次性调试逻辑
   - 不保留无用中间产物
   - 文档与实际行为同步

## 7. 当前建议

### 7.1 后端

1. 继续增加真实需求场景回归，优先覆盖更复杂的 Spring Boot 需求。
2. 给关键自动化链路补更系统的 E2E 脚本，而不是只靠临时命令。
3. 收敛 query 接口字段风格，减少前端适配成本。

### 7.2 前端

1. 继续沿用新的 `Mission Room` 信息架构，不回退到聊天式大面板。
2. 把 `Project Workspace` 与 `Ops Console` 明确分层。
3. 按已落地的双语字典继续做页面细化，不再允许混合语言文案回流。

## 8. 快速复验建议

如果要在本地快速确认当前状态，建议按下面顺序：

1. `docker compose --env-file .env.docker up -d --build backend`
2. 新建 session
3. 输入一条小型 Spring Boot 需求
4. 确认 requirement
5. 轮询：
   - `GET /api/v0/sessions/{id}/progress`
   - `GET /api/v0/sessions/{id}/task-board`
   - `GET /api/v0/sessions/{id}/ticket-inbox`
6. 当 `canCompleteSession=true`：
   - `POST /api/v0/sessions/{id}/complete`
   - `POST /api/v0/sessions/{id}/delivery/clone-repo`
7. 在宿主机执行：
   - `git clone <clone_url>`
   - `mvn test`
   - `mvn -q spring-boot:run -Dspring-boot.run.arguments=--server.port=<port>`

这份快照的核心结论是：当前项目已经不再停留在“只能看到流程大概在动”的阶段，而是已经可以拿到真实可克隆、可二次验收的交付仓库。
