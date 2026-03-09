# AgentX 前端交互重构说明（更新至 2026-03-09）

更新时间：2026-03-09  
定位：说明当前前端已经落地到什么程度、页面信息架构是什么、前后端下一步如何并行推进。

## 1. 结论

1. 前端已经不再沿用“聊天区 + 杂项侧栏”的旧思路。
2. 当前 `frontend-demo` 已切换到 React 版 `Mission Room` 结构，并完成双语字典支持。
3. 页面现在按控制面对象组织，而不是按消息组织。
4. 当前目标不是“做一个炫技 dashboard”，而是让用户一眼看清：
   - 当前 session 卡在哪里
   - 我下一步该做什么
   - 系统正在执行什么

## 2. 当前已实现的信息架构

### 2.1 一级空间

前端分成两个一级空间：

1. `Project Workspace`
   - 面向正常使用流程
   - 围绕 session / requirement / ticket / task / run / delivery
2. `Ops Console`
   - 面向运行时配置与 worker 运维
   - 不再与用户主流程混在同一屏

### 2.2 Project Workspace 下的主页面

当前页面结构：

1. `Overview`
2. `Requirement Studio`
3. `Decision Inbox`
4. `Execution`
5. `Delivery`

页面主旨：

1. `Overview` 负责讲清当前阶段与 blocker。
2. `Requirement Studio` 负责需求草拟、编辑、保存版本、确认需求。
3. `Decision Inbox` 聚合需要人工处理的 ticket。
4. `Execution` 展示 task board 与 run timeline。
5. `Delivery` 展示 clone repo、交付线索与完成条件。

### 2.3 Ops Console 下的主页面

当前页面结构：

1. `Runtime`
2. `Workers`

用途：

1. `Runtime` 读取、测试、应用 LLM 配置。
2. `Workers` 查看 worker 池并触发自动化动作。

## 3. 当前交互原则

### 3.1 一阶段一个主动作

现在页面主逻辑遵循：

1. Drafting / Reviewing：优先看 requirement
2. Waiting User：优先看 inbox
3. Executing：优先看 execution
4. Delivered：优先看 delivery

这比旧页面“所有按钮都堆在一起”更符合 AgentX 的流程对象。

### 3.2 统一双语，不再中英混排

当前实现：

1. 新增统一字典文件：`frontend-demo/src/i18n.jsx`
2. 页面右上角支持 `ZH / EN` 切换
3. 页面组件统一从字典取词

当前要求：

1. 新增页面文案必须进入字典
2. 不允许直接在组件里写混合语言字符串
3. 服务端返回的动态文本按原文展示，但页面壳文案必须统一

### 3.3 风格方向

当前视觉方向不是堆砌元素，而是“简约指挥台”：

1. 左侧窄导航
2. 中间主工作面
3. 顶部 phase / mode 切换
4. 右侧细节由页面内容驱动，而不是永远占屏

这比旧版更接近 Codex 自身的控制台气质：信息密度高，但层级清晰，主要动作突出，次要操作后置。

## 4. 当前代码落点

主要前端代码：

1. `frontend-demo/src/App.jsx`
2. `frontend-demo/src/pages.jsx`
3. `frontend-demo/src/components.jsx`
4. `frontend-demo/src/useMissionRoom.js`
5. `frontend-demo/src/controlPlane.js`
6. `frontend-demo/src/i18n.jsx`

职责划分：

1. `App.jsx`
   - 顶层布局
   - workspace / ops 切换
   - locale 切换
2. `pages.jsx`
   - 各主页面内容区
3. `components.jsx`
   - 共享 UI 组件
4. `useMissionRoom.js`
   - 页面状态与数据交互逻辑
5. `controlPlane.js`
   - 控制面接口访问与读模型适配
6. `i18n.jsx`
   - 双语字典与文本解析

## 5. 已完成的前端侧验证

1. `cmd /c npm --prefix frontend-demo run build` 已成功通过。
2. 页面双语字典已接入主壳。
3. 前端启动方式已在文档中修正为更稳妥的 Windows 用法：
   - `Set-Location frontend-demo`
   - `npm install`
   - `npm run dev`
4. Requirement Studio 已修正需求草稿生成链路：
   - 不再把 `READY_TO_DRAFT` 误报成“已生成草稿”
   - 信息足够时，`生成草稿` 会自动完成后端要求的确认步骤
   - `GET /api/v0/sessions/{sessionId}` 返回新文档后，右侧编辑器会同步最新标题与内容，而不是停留在旧本地状态

## 6. 当前仍需继续做的事

### 6.1 读模型字段收敛

虽然 `progress / task-board / ticket-inbox / run-timeline` 已可用，但还需要继续收敛：

1. 前端当前应按运行态真实返回的 camelCase 字段消费。
2. 后续最好统一接口字段风格，降低适配层复杂度。

### 6.2 页面细节打磨

下一步建议优先做：

1. `Overview` 的 blocker / primary action 视觉层级再拉开
2. `Decision Inbox` 的回复体验再压缩为单焦点表单
3. `Delivery` 页面明确区分：
   - 已有 clone 地址
   - 可完成 session
   - 仍有 completion blockers

### 6.3 浏览器实测

用户本机有 Edge 与 Chrome，因此接下来可继续做：

1. 启动 `frontend-demo`
2. 用真实 backend 会话数据逐页走读
3. 对布局、文案、间距、密度做第二轮视觉修正

## 7. 前后端并行接口面

当前前后端可以并行，不需要互相等待到“全部完成”。

### 7.1 前端可直接依赖的接口

1. `GET /api/v0/sessions/{sessionId}/progress`
2. `GET /api/v0/sessions/{sessionId}/ticket-inbox`
3. `GET /api/v0/sessions/{sessionId}/task-board`
4. `GET /api/v0/sessions/{sessionId}/run-timeline`
5. `GET /api/v0/runtime/llm-config`
6. `POST /api/v0/runtime/llm-config:test`
7. `POST /api/v0/runtime/llm-config:apply`
8. `POST /api/v0/sessions/{sessionId}/delivery/clone-repo`

### 7.2 后端接下来最值得继续补的点

1. 扩大真实场景回归，不要只停留在两个样例
2. 稳定 query 接口字段与文档
3. 继续减少执行链路中“需要脚本额外兜底”的地方

## 8. 当前判断

当前前端已经从“方向错误的 demo”进入“可以继续细化的控制面壳子”阶段。

换句话说：

1. 信息架构已经切到正确轨道。
2. 双语支持已经接入，不再允许中英文混排。
3. 现在值得继续做的是细节与真实浏览器打磨，而不是重新推翻页面结构。
