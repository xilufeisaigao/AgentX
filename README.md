# AgentX Backend

AgentX 是一个面向 Agent 工程化的后端控制平面（Control Plane）项目，目标是把“需求澄清 -> 架构提请 -> 任务拆解 -> Worker 执行 -> 交付取码”做成可审计、可回放、可自动化的闭环。

## 核心能力

1. 会话化需求流转（session + requirement doc versioning）。
2. 架构师提请（`DECISION` / `CLARIFICATION`）与用户回覆闭环。
3. 模块/任务拆解与 Worker 池自动分配。
4. 任务运行事件链（`task_run_events`）与工单事件链（`ticket_events`）。
5. 交付仓库发布与临时 clone 地址生成（支持到期清理）。
6. 前端初始化配置面板，支持运行时切换 LLM 且**无需重启后端**。

## 技术栈

1. Java 21 + Spring Boot + MyBatis
2. MySQL 8 + Redis 7
3. 前端演示：Vite + Vanilla JS（`frontend-demo`，npm 管理）
4. Docker Compose 一键部署（backend + mysql + redis + git-export）

## 快速开始（推荐：Docker）

### 1) 准备环境变量

```powershell
Copy-Item .env.docker.example .env.docker
```

必须填写（无明文默认值）：

1. `MYSQL_ROOT_PASSWORD`
2. `AGENTX_DB_PASSWORD`
3. `AGENTX_REDIS_PASSWORD`

建议设置：

1. `AGENTX_HOST_REPO_ROOT`：主机上用于保存生成代码与 worktree 的目录
2. `AGENTX_DELIVERY_CLONE_PUBLISH_PUBLIC_BASE`：用于返回可直接 `git clone` 的地址
3. `AGENTX_WORKFORCE_BOOTSTRAP_DEFAULT_WORKERS=true`：启动时预置常用 READY worker 组合（可关闭）

### 2) 启动

```powershell
docker compose --env-file .env.docker up -d --build
```

默认端口：

1. Backend: `http://127.0.0.1:18082`
2. MySQL: `127.0.0.1:13306`
3. Redis: `127.0.0.1:16379`
4. Git Export: `git://127.0.0.1:19418`

### 3) 启动前端演示

```powershell
npm --prefix frontend-demo install
npm --prefix frontend-demo run dev
```

打开 `http://127.0.0.1:5173`。

## 运行时 LLM 配置（默认 mock）

默认 provider 为 `mock`，系统可以在无真实 key 情况下跑完整流程。  
当你在前端“初始化配置”面板填写真实 LLM 信息后，可立即生效，无需重启后端。

后端接口：

1. `GET /api/v0/runtime/llm-config`
2. `POST /api/v0/runtime/llm-config:test`
3. `POST /api/v0/runtime/llm-config:apply`

配置持久化文件：

1. 本地默认：`.agentx/runtime/runtime-llm-config.json`
2. Docker 推荐：`/agentx/runtime-data/runtime/runtime-llm-config.json`

## 代码产出在哪里

生成代码与 git worktree 会落在：

1. `AGENTX_HOST_REPO_ROOT` 对应目录（Docker volume 挂载）

如果你不关心中间过程，只想拿最终代码，推荐直接调用发布接口拿 clone 地址：

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:18082/api/v0/sessions/<SESSION_ID>/delivery/clone-repo"
```

返回体包含：

1. `clone_url`
2. `clone_command`
3. `expires_at`

之后直接：

```powershell
git clone <clone_url>
```

## 回归测试

### Java 测试

```powershell
mvn -q test
```

### 前端构建校验

```powershell
npm --prefix frontend-demo run build
```

### 全链路回归（本地 mysql/redis 可达时）

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/run_full_backend_suite.ps1
```

启用真实 LLM 冒烟：

```powershell
$env:AGENTX_REQUIREMENT_LLM_API_KEY="<your-key>"
pwsh -NoLogo -NoProfile -File tests/e2e/run_full_backend_suite.ps1 -EnableRealLlm
```

注意：`-EnableRealLlm` 不再从 `application.yml` 回退读取 key，必须显式设置环境变量。

## 文档入口

1. 文档索引：`docs/README.md`
2. API 语义契约：`docs/09-control-plane-api-contract.md`
3. Schema：`docs/schema/agentx_schema_v0.sql`
4. Docker 部署：`docs/deployment/docker-runtime.md`

## 发布前建议

1. 使用 `.env.docker` 注入所有敏感配置，不要把密钥写入仓库文件。
2. 先跑 `mvn -q test` 与前端 build，再发版本标签。
3. 对外演示优先走 clone-url 交付路径，降低用户取码门槛。
