# AgentX 运行审计（2026-03-17）

这份审计只记录已经实际验证过的事实，不写推测。

审计时间：

- 运行环境确认时间: 2026-03-17
- 最小闭环验证 session: `<validated-session-id>`
- 对应 requirement doc: `<validated-requirement-doc-id>`

说明：

- 这页里的运行样本标识已经做脱敏处理。
- 结论、步骤、统计和目录结构都来自真实验证，但不保留临时运行实例的原始 ID。

## 结论

截至 2026-03-17，AgentX 的最小全链路闭环已经在 Docker 运行面上跑通：

1. 创建 session 成功。
2. 需求草案生成成功。
3. 需求确认后自动进入 architect 规划。
4. 任务被自动拆分、自动调度、自动执行。
5. 中间出现 2 次 run 失败，但后续 run 自动修复并继续推进。
6. 所有任务最终进入 `DONE`。
7. session 成功完成。
8. clone repo 成功发布。
9. 发布出的项目在 Docker 中再次验证通过：
   - `mvn -q test` 通过
   - 应用启动后 `GET /api/healthz` 返回 `{"status":"ok","service":"codex-check"}`

这说明当前系统不是概念闭环，而是实闭环。

## Docker 栈现状

`docker compose --env-file .env.docker ps` 的实际结果：

- `backend`: `http://127.0.0.1:18082`
- `mysql`: `127.0.0.1:13306`
- `redis`: `127.0.0.1:16379`
- `git-export`: `git://127.0.0.1:19418`

后端容器挂载：

- host bind mount: `<repo-root>/runtime-projects/default-repo -> /agentx/repo`
- Docker volume: `agentx_runtime_data -> /agentx/runtime-data`

## 运行时配置事实

`.env.docker` 中 requirement/worker 的默认 provider 是 `mock`，但运行时接口返回的真实配置不是 `mock`。

`GET /api/v0/runtime/llm-config` 返回：

- `customized = true`
- `requirement_llm.provider = bailian`
- `requirement_llm.model = qwen3-max`
- `worker_runtime_llm.provider = bailian`
- `worker_runtime_llm.model = qwen3-max`
- requirement 和 worker runtime 的 API key 都已配置

这件事非常重要：

- `.env.docker` 不是最终真相。
- 当前系统允许在控制面动态覆盖运行时 LLM 配置。
- 所以排查问题时要优先看运行时接口，而不是只看环境变量。

## 最小闭环样本

使用的需求是一个最小 Java 服务：

- Spring Boot 3
- Java 17
- Maven
- 暴露 `GET /api/healthz`
- 固定返回 `{"status":"ok","service":"codex-check"}`
- 包含 MockMvc 测试和 README
- 不引入数据库、认证、前端

实际链路如下：

1. `POST /api/v0/sessions`
2. `POST /api/v0/sessions/{sessionId}/requirement-agent/drafts`
3. `POST /api/v0/requirement-docs/{docId}/confirm`
4. 后台自动完成 architect 规划、task 创建、worker 分配、run 执行、verify、merge
5. 在可完成状态下调用 `POST /api/v0/sessions/{sessionId}/complete`
6. `POST /api/v0/sessions/{sessionId}/delivery/clone-repo`

返回的 clone URL：

- `git://127.0.0.1:19418/agentx-session-<session-id>.git`

## 查询层证据

`GET /api/v0/sessions/<session-id>/progress` 返回的关键信息：

- `sessionStatus = COMPLETED`
- `phase = COMPLETED`
- `requirement.status = CONFIRMED`
- `taskCounts.total = 5`
- `taskCounts.done = 5`
- `ticketCounts.total = 1`
- `ticketCounts.done = 1`
- `runCounts.total = 13`
- `runCounts.succeeded = 11`
- `runCounts.failed = 2`
- `delivery.deliveryTagPresent = true`
- `delivery.latestVerifyStatus = SUCCEEDED`

注意一个容易误解的点：

- 在 session 完成之前，系统曾进入“可以 complete”的状态。
- 在你已经调用 `POST /complete` 之后，再看 `progress`，`canCompleteSession` 会变成 `false`，因为此时 session 已经处于 `COMPLETED`，不需要再次 complete。
- 所以 `canCompleteSession` 是查询层状态，不是历史事实的永久标记。

## task board 证据

`GET /api/v0/sessions/<session-id>/task-board` 返回两组模块：

1. `bootstrap`
   - 1 个 `tmpl.init.v0` 任务
2. `健康检查服务模块`
   - 4 个任务

总计 5 个任务，全部 `DONE`。

这 4 个业务任务分别是：

1. 创建 Spring Boot 3 Maven 项目骨架
2. 实现 `/api/healthz` 端点并返回硬编码 JSON 字符串
3. 编写基于 MockMvc 的集成测试验证 `/api/healthz` 行为
4. 创建 `README.md` 文件说明构建、运行与测试命令

## ticket 证据

`GET /api/v0/sessions/<session-id>/ticket-inbox` 返回：

- 只有 1 张 ticket
- 类型是 `ARCH_REVIEW`
- 状态是 `DONE`
- 由 `requirement_agent` 创建
- 分配给 `architect_agent`
- 最新事件说明：架构规划完成，execution 阶段的工作项已经创建

这个事实说明：

- 当前最小闭环里，人类决策面没有被触发。
- `ARCH_REVIEW` 在这个样本里承担的是 requirement 到 planning 的自动交接。

## run timeline 证据

`GET /api/v0/sessions/<session-id>/run-timeline?limit=30` 给出了完整 run 事件序列。

其中两个失败 run 的原因已经被明确记录：

1. `<failed-impl-run-id>`
   - `runKind = IMPL`
   - 失败原因: `Planner returned only out-of-scope edits: README.md`
2. `<failed-verify-run-id>`
   - `runKind = VERIFY`
   - 失败原因: `mvn -q test` 失败，测试报出返回内容与预期 JSON 不兼容

随后系统继续推进并自动修复：

- `<recovery-impl-run-id>`
  修复了健康检查端点返回类型
- 后续 verify 全部恢复为 `SUCCEEDED`

所以当前系统的真实行为不是“只要失败就终止”，而是“允许中途失败，再通过后续 run 恢复到闭环”。

## 数据库证据

从 MySQL 实际查询到的事实：

- `sessions.status = COMPLETED`
- `work_modules` 中该 session 对应 2 个模块
- `work_tasks` 经 `work_modules` 关联统计得到 5 个任务
- `task_runs` 经 task 和 module 关联统计得到 13 个 run

一个关键认知要写死：

- `sessions` 表只有会话级基础状态，不包含 `canCompleteSession` 这种聚合态字段。
- `work_tasks` 也不直接带 `session_id`，而是通过 `module_id -> work_modules.session_id` 关联回 session。
- 所以前端看到的会话总览并不是简单查一张表。

## Git 证据

session repo 实际路径：

- `runtime-projects/default-repo/sessions/<session-id-lowercase>/repo`

git log 里可以看到：

- `main`
- 多个 `task/*` 分支
- 多个 `run/*` 分支
- `delivery/20260317-0732` tag
- `origin/main` 基线提交

这个结构说明：

- 任务和 run 过程都在 git refs 中有痕迹。
- session 交付不是只靠数据库标记，也有 git 证据链。

## 运行时目录证据

在容器内确认到了真实上下文目录：

- `/agentx/runtime-data/context/context/task-context-packs/...`
- `/agentx/runtime-data/context/context/task-skills/...`
- `/agentx/runtime-data/runtime-env/global-toolpacks/...`

这意味着：

- context pack 和 task skill 不是纯内存对象。
- 它们有明确的持久化落点，可以在排查时直接读文件。

## 生成项目二次验证

对 clone 出来的生成项目做了 Docker 内二次验证：

1. 用 `maven:3.9.11-eclipse-temurin-21` 运行 `mvn -q test`
2. 再用 Docker 启动 Spring Boot 应用
3. 请求 `http://127.0.0.1:19095/api/healthz`

返回：

```json
{"status":"ok","service":"codex-check"}
```

所以当前闭环不仅完成了“代码生成”，也完成了“代码可运行”的最小验证。

## 当前状况总结

当前项目已经具备以下现实能力：

1. Docker 化运行面稳定存在。
2. 从 session 到 delivery 的主链路已跑通。
3. query 层能汇总出用户可读的进度视图。
4. 运行时配置、git 工作区、context pack、delivery publish 都有真实落点。
5. 出现局部失败时，系统具有一定自动恢复能力。

当前仍然需要重点盯防的地方：

1. 查询视图和表结构容易混淆。
2. `process` 模块承担了大量编排逻辑，后续最容易继续膨胀。
3. scheduler 驱动链路需要更强的日志和可观测性，才能让问题定位更快。
4. worker 选择、toolpack 满足关系、verify 恢复策略仍需要更清晰的工程边界说明。
