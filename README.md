# AgentX Backend

AgentX Backend 是一个面向多 Agent 协作的软件工程控制平面（Control Plane）。
它把需求澄清、任务拆解、执行编排、结果交付串成可追踪、可审计、可自动化的工程闭环。

## 项目定位

AgentX 关注的不是“单次对话能力”，而是“工程流程能力”：

1. 从需求到交付的全链路状态管理。
2. 多角色协作（用户、架构代理、执行代理）的流程化编排。
3. 对任务执行过程和结果证据的结构化沉淀。
4. 面向真实开发场景的仓库交付与可回放能力。

## 核心能力

1. 会话与需求文档版本化管理（Session + Requirement Docs）。
2. 工单驱动的澄清/决策闭环（Ticket + Ticket Events）。
3. 任务拆解与依赖调度（Planning）。
4. Worker 运行编排与执行事件落库（Execution + Task Run Events）。
5. 代码工作区与交付仓库发布（Workspace + Delivery）。
6. 面向流程自动化的跨模块 Process Manager。

## 架构概览

项目采用模块化单体（Modular Monolith）+ DDD 分层设计，依赖方向遵循：

`api -> application -> domain <- infrastructure`

主要业务模块：

1. `session`
2. `requirement`
3. `ticket`
4. `planning`
5. `execution`
6. `workforce`
7. `workspace`
8. `mergegate`
9. `contextpack`
10. `delivery`
11. `process`
12. `query`

## 技术栈

1. Java 21
2. Spring Boot
3. MyBatis
4. MySQL 8
5. Redis 7
6. Docker Compose
7. 前端演示：Vite + React（`frontend-demo`）

## 快速启动（Docker）

### 1) 准备环境变量

```powershell
Copy-Item .env.docker.example .env.docker
```

请在 `.env.docker` 中按需填写数据库、缓存、仓库路径等配置。
敏感信息请只保存在本地环境文件中，不要提交到仓库。

### 2) 启动服务

```powershell
docker compose --env-file .env.docker up -d --build
```

建议使用隔离启动脚本（每次启动自动使用新的产物目录，避免不同场景互相污染）：

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/start_isolated_runtime.ps1 -EnvFile .env.docker -DownFirst
```

默认端口：

1. Backend: `http://127.0.0.1:18082`
2. MySQL: `127.0.0.1:13306`
3. Redis: `127.0.0.1:16379`

### 3) 启动前端演示（可选）

```powershell
Set-Location frontend-demo
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。

页面右上角可直接切换 `ZH / EN`，当前前端演示页已经统一为双语文案，不再混用中英文。

## 本地开发

### 后端

```powershell
mvn -q test
mvn spring-boot:run
```

### 前端

```powershell
npm --prefix frontend-demo run build
```

## 测试建议

1. 先跑后端单测：`mvn -q test`
2. 再跑前端构建校验：`cmd /c npm --prefix frontend-demo run build`
3. 最后按需执行 E2E 脚本：`tests/e2e/`

## 当前已验证链路

截至 2026-03-09，已在 Docker 运行态完成两条真实端到端验证，均为：

1. 新建 session
2. requirement-agent 自动起草并确认需求
3. architect 自动拆解任务
4. worker 自动执行与修复
5. session 进入 `canCompleteSession=true`
6. `POST /api/v0/sessions/{sessionId}/complete`
7. `POST /api/v0/sessions/{sessionId}/delivery/clone-repo`
8. 本机 `git clone` + `mvn test` + 启动应用并请求接口

已验证场景：

1. `SES-365c1b01de6a44bfadc5bc0ae499bed2`
   - 产物接口：`GET /api/greeting`
   - 本机验收结果：`Hello, 张三!` / `Hello, World!`
2. `SES-8c9b63dae1454b43ad435e3c2cdbe155`
   - 产物接口：`GET /api/ping`
   - 本机验收结果：`{\"status\":\"ok\",\"service\":\"agentx-demo\"}`

结论：当前系统已经可以从全新会话自动跑到可克隆交付仓库，至少在上述两类 Spring Boot 3 / Java 17 小型需求上已完成真实闭环验证。

## 目录结构

```text
src/main/java/com/agentx/agentxbackend/   # 后端主代码
src/test/java/com/agentx/agentxbackend/   # 后端测试
docs/                                     # 设计文档与契约
tests/                                    # e2e 与 python 测试脚本
frontend-demo/                            # 演示前端
```

## 文档入口

1. 文档索引：`docs/README.md`
2. API 语义契约：`docs/09-control-plane-api-contract.md`
3. OpenAPI：`docs/openapi/agentx-control-plane.v0.yaml`
4. 数据库结构：`docs/schema/agentx_schema_v0.sql`
5. Docker 部署说明：`docs/deployment/docker-runtime.md`

## 安全说明

1. 仓库默认不应包含密钥、令牌和本地运行时产物。
2. 请使用 `.env.docker` / 环境变量注入敏感配置。
3. 提交前建议执行一次敏感信息扫描。
