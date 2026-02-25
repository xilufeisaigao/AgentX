# AgentX Frontend Demo

一个放在仓库根目录的最小前端演示页，目标是展示 Agent 工程化流程：

1. 左侧：会话历史 + 新建会话
2. 中间：需求/增量开发对话台
3. 右侧顶部：运行时初始化配置（LLM provider/base-url/model/api-key，支持连通性测试与应用）
4. 右上：架构师提请列表（可下拉看详情并回覆）
5. 右下：流程进展 + worker 列表（可下拉看任务与上下文）
6. 代码产出后弹窗展示 Git 地址

## 启动方式

先确保后端已启动（默认 `http://127.0.0.1:18082`），然后在仓库根目录执行：

```powershell
npm --prefix frontend-demo install
npm --prefix frontend-demo run dev
```

打开 `http://127.0.0.1:5173`。

## 初始化配置（无需重启后端）

右侧“初始化配置”支持：

1. 读取当前生效配置：`GET /api/v0/runtime/llm-config`
2. 连通性测试：`POST /api/v0/runtime/llm-config:test`
3. 应用配置：`POST /api/v0/runtime/llm-config:apply`

默认 provider 是 `mock`，不填 key 也能跑演示流程。  
应用配置成功后，后续请求会立刻使用新配置，无需重启后端。

## 自动克隆地址（推荐）

当流程进入“代码产出可拉取”后，前端会自动调用后端接口：

`POST /api/v0/sessions/{sessionId}/delivery/clone-repo`

弹窗会展示可直接执行的命令：

```powershell
git clone <clone_url>
```

若自动发布地址失败，会回退到右侧输入框中的手动地址。

## 常用命令

```powershell
# 本地开发
npm --prefix frontend-demo run dev

# 生产构建
npm --prefix frontend-demo run build

# 本地预览构建产物
npm --prefix frontend-demo run preview
```

## 可选环境变量

```powershell
# 前端代理监听地址
$env:AGENTX_UI_HOST="127.0.0.1"
# 前端代理端口
$env:AGENTX_UI_PORT="5173"
# 后端地址
$env:AGENTX_UI_API_BASE="http://127.0.0.1:18082"
```
