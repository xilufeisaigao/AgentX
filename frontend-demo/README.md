# AgentX Frontend Demo

当前前端演示页已经从“聊天式控制台”切换为 React 版 `Mission Room`，围绕：

1. Session 列表与切换
2. Overview 总览
3. Requirement Studio
4. Decision Inbox
5. Execution 现场（Task Board / Run Timeline）
6. Delivery 与 Ops Console

## 启动方式

先确保后端已启动（默认 `http://127.0.0.1:18082`），然后在仓库根目录执行：

```powershell
Set-Location frontend-demo
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。

说明：

1. 右上角支持 `ZH / EN` 切换，页面文案已经统一走同一套字典，不再中英文混排。
2. 如果你更习惯在仓库根目录执行命令，也可以用：

```powershell
cmd /c npm --prefix frontend-demo run build
cmd /c npm --prefix frontend-demo run dev
```

## Requirement Studio 行为说明

`Requirement Studio` 中的两个关键动作现在按结构化 UI 语义工作，而不是要求用户理解聊天式确认词：

1. `分析缺口`
   - 只做需求澄清与缺口分析
   - 不会创建需求文档
2. `生成草稿`
   - 当信息不足时，展示缺口信息
   - 当信息已足够时，前端会自动完成后端要求的确认步骤并生成需求草稿
   - 草稿生成成功后，右侧 `文档编辑器` 会立即同步后端返回的最新需求内容

如果接口只进入 `READY_TO_DRAFT` 而尚未真正持久化，前端不会再误报“草稿已生成”。

## 运行时配置（无需重启后端）

`Ops Console` 中的 Runtime 页面支持：

1. 读取当前生效配置：`GET /api/v0/runtime/llm-config`
2. 连通性测试：`POST /api/v0/runtime/llm-config:test`
3. 应用配置：`POST /api/v0/runtime/llm-config:apply`

默认 provider 是 `mock`，不填 key 也能跑演示流程。  
应用配置成功后，后续请求会立刻使用新配置，无需重启后端。

## Delivery Clone 地址

当流程进入可交付状态后，前端可调用后端接口：

`POST /api/v0/sessions/{sessionId}/delivery/clone-repo`

弹窗会展示可直接执行的命令：

```powershell
git clone <clone_url>
```

若自动发布地址失败，会回退到右侧输入框中的手动地址。

## 常用命令

```powershell
# 本地开发
Set-Location frontend-demo
npm run dev

# 生产构建
npm run build

# 本地预览构建产物
npm run preview
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
