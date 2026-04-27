# Claude Managed Agents 调查文档

调研日期：2026-04-27
官方文档：https://platform.claude.com/docs/en/managed-agents/overview
工程文章：https://www.anthropic.com/engineering/managed-agents

## 1. 一句话定位

Claude Managed Agents 是 Anthropic 提供的托管式 agent 基础设施。它把长任务 agent 所需的 agent 配置、云端环境、会话状态、事件流、工具调用、MCP、权限确认、沙箱执行和可观测性抽象成一套官方 API。

与 Multica 和 AgentX 相比，Managed Agents 更靠近“基础设施层”：

- Multica 是开源团队协作控制面，执行主要在本地 daemon。
- AgentX 是面向长流程软件交付的控制面和方法论。
- Claude Managed Agents 是 Anthropic 托管的 agent harness、session log 和 sandbox 运行体系。

如果把一个 PRD/开发需求交给 Claude Managed Agents，它通常不是创建一个“PRD 对象”，而是把 PRD 作为 `user.message` 或 Research Preview 的 `user.define_outcome` 事件送入某个 `Session`。之后由托管 harness 驱动 Claude、工具和环境，直到 session idle、产出文件/消息/事件，或停在需要人工确认的动作上。

## 2. 核心对象

| 对象 | 官方语义 | 在开发需求流转中的角色 |
|---|---|---|
| Agent | 可复用、版本化的 agent 配置，包括模型、system prompt、tools、MCP servers、skills | 定义“谁来做”和能力边界 |
| Environment | 容器模板，配置运行时依赖、包、网络访问等 | 定义“在哪里做” |
| Session | 某个 agent 在某个 environment 中运行的一次实例，保留多轮历史 | 定义“一次任务/PRD/需求实例” |
| Events | 应用与 agent 之间交换的消息、工具请求、状态变化 | 定义“如何驱动、观察、暂停、恢复和交付” |
| Tools | 内置工具、自定义工具、MCP 工具 | 定义 agent 能操作什么 |
| Vaults | session 级凭证绑定 | 定义外部服务授权，不把 secret 写入 agent config |
| Files / Outputs | session 文件和 outcome 输出 | 定义交付物 |

三个边界特别重要：

- **Agent 不是任务**：Agent 是可复用配置模板。
- **Session 才是任务实例**：一次 PRD、一个 issue、一个修复任务应对应 session。
- **Event log 不是模型上下文窗口**：事件日志持久化在模型上下文之外，harness 可以读取、裁剪、压缩、转换后再喂给 Claude。

## 3. PRD/开发需求端到端流转

一个典型开发需求在 Managed Agents 中可以这样流转：

```text
PRD / Issue / Ticket
  -> 选择或创建 Agent
  -> 选择或创建 Environment
  -> 创建 Session(agent + environment + resources + vaults)
  -> 发送 user.message 或 user.define_outcome
  -> Harness 读取 session event log，调用 Claude
  -> Claude 产生 agent.message / agent.tool_use / agent.mcp_tool_use / agent.custom_tool_use
  -> 工具在 sandbox、MCP proxy 或应用侧执行
  -> 工具结果写回事件流
  -> Agent 继续迭代
  -> session.status_idle / outcome satisfied / 文件输出 / 事件和交付物可读取
```

### 3.1 Agent 选择与版本

Agent 是可复用、可版本化的配置。创建 session 时可以引用 agent 最新版本，也可以锁定 `{id, version}`。对生产软件开发任务来说，锁定版本非常重要：否则同一个需求类型可能因为 agent prompt 或 tool policy 自动升级而表现不同。

Agent 可配置：

- 模型。
- System prompt。
- 内置 toolset。
- MCP servers。
- Custom tools。
- Permission policy。
- Skills。

### 3.2 Environment 准备

Environment 是容器模板。它定义：

- 基础镜像/运行时。
- 需要安装的包。
- 网络策略。
- 是否允许包管理器访问。
- 是否允许 MCP servers。
- allowed hosts。

每个 session 会得到自己的隔离容器实例；多个 session 可以引用同一个 environment 定义，但文件系统状态不共享。

生产环境建议用 `limited` networking 和明确 allowed hosts，而不是 unrestricted networking。

### 3.3 Session 创建

Session 绑定 agent 和 environment。创建 session 会 provision 运行资源，但不会自动开始工作。真正开始执行需要发送事件。

Session 常见状态包括：

- `idle`
- `running`
- `rescheduling`
- `terminated`

Session 是长任务的根边界：

- 保存 conversation history。
- 保存事件。
- 关联 environment container。
- 关联 resources 和 vaults。
- 可中断、续跑、查询、流式观察。

### 3.4 需求输入

常规模式：

```text
user.message(PRD/issue/acceptance criteria)
```

Outcome 模式：

```text
user.define_outcome(description, rubric, max_iterations)
```

Outcome 模式会让 agent 立即开始工作，并在后续由独立评估循环判断 outcome 是否 satisfied。它非常适合 PRD 验收、文件产出、数据分析报告等任务，但官方将其标为 Research Preview，因此生产使用需要谨慎。

### 3.5 Harness 执行循环

Anthropic 工程文章把 Managed Agents 拆成三个关键抽象：

```text
session  = 持久事件日志和状态
harness  = 调用 Claude、路由工具、恢复执行的控制循环
sandbox  = 执行代码、编辑文件、运行命令的手
```

新的设计重点是把 **brain** 和 **hands** 解耦：

- Brain：Claude + harness。
- Hands：sandbox/tools。
- Memory：session event log。

这样 harness 崩溃后可以从 session event log 恢复；sandbox 失败时可以把它看成一次 tool-call error，而不是整个 agent session 崩掉。

### 3.6 工具调用

Managed Agents 支持三类工具：

1. **内置 agent toolset**
   典型包括 `bash`、`read`、`write`、`edit`、`glob`、`grep`、`web_fetch`、`web_search`。

2. **MCP tools**
   Agent config 声明 MCP server；session 通过 vaults 提供凭证。MCP 默认更谨慎，常需要 confirmation。

3. **Custom tools**
   Claude 发出结构化请求；你的应用执行工具，并通过 `user.custom_tool_result` 写回结果。权限、幂等、审计和失败处理由你的系统负责。

典型内置工具流：

```text
user.message
  -> agent.message
  -> agent.tool_use(name=bash/read/write/...)
  -> harness routes tool to sandbox
  -> tool result event
  -> agent continues
  -> session.status_idle(stop_reason=end_turn)
```

MCP 或 custom tool 需要确认时：

```text
agent.mcp_tool_use / agent.custom_tool_use
  -> session.status_idle(stop_reason=requires_action)
  -> user.tool_confirmation 或 user.custom_tool_result
  -> session.status_running
  -> agent continues
```

### 3.7 事件流与观察

Managed Agents 是 event-based。用户侧事件包括：

- `user.message`
- `user.interrupt`
- `user.custom_tool_result`
- `user.tool_confirmation`
- `user.define_outcome`

服务侧事件包括：

- `agent.message`
- `agent.tool_use`
- `agent.mcp_tool_use`
- `agent.custom_tool_use`
- `session.status_*`
- `span.*`

每个事件有 `processed_at`。如果为 null，表示事件已排队但还未被 harness 处理。

这比同步 HTTP 请求更适合长任务：应用可以实时读 SSE stream，也可以之后 list past events 做审计和恢复。

### 3.8 结果交付

结果可以来自几种渠道：

- `agent.message`。
- session event history。
- sandbox 中生成或修改的文件。
- outcome 模式写入 `/mnt/session/outputs/` 的文件。
- Files API 按 session scope 拉取。
- 通过 MCP/custom tool 在外部系统创建的 PR、issue、comment 等。

Managed Agents 本身更强调“运行和事件基础设施”，至于 PR URL、GitHub merge、Linear ticket 状态这些业务交付物，需要应用系统自己建模或通过 MCP/custom tools 实现。

## 4. 安全边界

Managed Agents 的安全思想非常清晰：不要让不可信代码和长期凭证处于同一执行边界。

关键设计：

1. **Harness 不在 sandbox 内**
   早期耦合架构中，harness、session、sandbox 放一起会导致容器失败丢 session、调试困难、用户代码和系统逻辑混杂。新架构把 harness 移出容器。

2. **Token 不进入 sandbox**
   Git token 等凭证用于初始化 clone 或 remote，但 agent 生成代码的 sandbox 不应直接拿到 token。

3. **Vault 管 MCP 凭证**
   Agent config 只声明 MCP server，不带 secret。Session 创建时引用 vault；MCP proxy 根据 session token 从 vault 取凭证。

4. **Permission policy 控制工具执行**
   内置工具可配置 always_allow/always_ask 等策略；MCP 默认更谨慎。需要确认时 session idle，等待 `user.tool_confirmation`。

5. **Environment 网络最小权限**
   生产建议 limited networking，显式 allowed hosts，必要时允许 package managers 或 MCP servers。

## 5. 异步与恢复

Managed Agents 的长任务能力建立在几个机制上：

- **事件驱动**：提交事件后不依赖同步 HTTP 请求一直挂着。
- **持久 session**：会话历史保留到删除，容器 idle 后 checkpoint。
- **Harness 可恢复**：从 session event log 读取事件并续跑。
- **Sandbox 可替换**：容器失败是工具错误，可以重新 provision。
- **中途 steer/interruption**：通过 `user.interrupt` 停止当前执行，再发送新 message 调整方向。
- **Tracing/Observability**：通过 session events 和 console tracing 观察 agent 行为。

这部分是它相对 Multica 的优势：Multica 也有 task session/workdir 和 daemon recovery，但 Managed Agents 把 session log、harness、sandbox 分离做成了平台级抽象。

## 6. 与 Tool Runner 的关系

Claude 生态里还有 Tool Runner SDK。两者层次不同：

| 层级 | 谁维护 loop | 谁执行工具 | 适合场景 |
|---|---|---|---|
| Messages API + 手写 loop | 应用 | 应用 | 完全自定义 |
| Messages API + SDK Tool Runner | SDK 辅助 | 应用 | 常规 tool-use 应用 |
| Claude Managed Agents | Anthropic 托管 harness | 内置工具在托管环境，custom tools 在应用，MCP 走 proxy/vault | 长任务、异步、容器、持久 session |

可以理解为：Tool Runner 解决“怎么跑一轮工具调用循环”，Managed Agents 解决“怎么托管一个长时间、多工具、可恢复、有环境和事件日志的 agent session”。

## 7. 设计亮点

1. **Agent / Session 分离**
   Agent 是可版本化模板，Session 是任务实例，避免把任务状态塞进 agent 定义。

2. **Environment 一等对象**
   运行环境可复用、可配置、可限制网络，比“随便找台机器跑命令”更适合生产。

3. **Session event log**
   持久化事件日志独立于模型上下文窗口，使恢复、审计、压缩和重放成为可能。

4. **Harness / Sandbox 解耦**
   Brain 和 hands 分离，容器失败不会等同于 session 失败。

5. **Permission confirmation**
   工具执行可以停下来等人确认，适合高风险操作。

6. **Vault 凭证模型**
   Agent config 不含 secret，session 绑定 vault，降低泄露面。

7. **Custom tools 作为应用集成点**
   业务系统可以把自己的权限、审计、审批和幂等逻辑放在 custom tool 执行侧。

8. **Outcome 模式**
   用 description + rubric + evaluator 让 agent 迭代到验收标准满足，适合 PRD 和交付物验证。

9. **中断和续跑**
   通过 `user.interrupt` 和新事件把需求变更纳入同一 session log。

## 8. 潜在不足

1. **不是完整项目管理系统**
   Managed Agents 没有内建 Multica 那种 workspace、issue board、team member、agent teammate、project lead 等协作对象。

2. **业务流程需要自己建模**
   PRD、issue、DAG、Merge Gate、PR URL、验收状态等都需要上层系统实现。

3. **平台绑定 Anthropic**
   它是 Claude 官方 managed infrastructure，模型和运行时生态天然围绕 Anthropic。

4. **Outcome 仍是 Research Preview**
   很适合验收，但 API 和行为可能变化。

5. **Custom tool 责任边界重**
   自定义工具由应用执行，权限、审计、幂等、失败恢复都需要自己做好。

6. **成本和配额需要生产评估**
   长任务、多 session、云环境 checkpoint、工具执行和文件存储都需要纳入成本治理。

## 9. 对 PRD/开发流程系统的映射建议

| 内部概念 | Managed Agents 映射 |
|---|---|
| PRD / Issue / Ticket | `user.message` 或 `user.define_outcome.description` |
| 验收标准 | outcome `rubric` 或 system/user prompt acceptance criteria |
| 工程角色 | 不同 Agent，如 implementer、reviewer、tester |
| 项目环境 | Environment |
| 仓库资源 | Session resources |
| 第三方授权 | Vaults |
| 任务实例 | Session |
| 状态追踪 | Session status + event stream + tracing |
| 工具审批 | Permission policy + `user.tool_confirmation` |
| 人工纠偏 | `user.interrupt` + 新 `user.message` |
| 交付物 | `agent.message`、files、outputs、外部 PR/comment |
| 审计日志 | Session events |

## 10. 可学习的设计要点

Claude Managed Agents 最值得吸收的是：

- Agent 模板与 Session 实例分离。
- Environment 作为可复用运行环境模板。
- Session event log 作为长任务持久记忆，而不是把一切塞进模型上下文。
- Harness 与 sandbox 解耦。
- Tool permission confirmation 作为 HITL 基础设施。
- Vaults 管理 session 级授权。
- Custom tools 把业务系统接入 agent loop。
- Outcome/rubric 模式表达“完成标准”。
- 中断、续跑和 tracing 支持长期运行。

## 11. 结论

Claude Managed Agents 是三个项目中基础设施抽象最强的一个。它不提供完整研发流程，也不提供团队看板，但它把长任务 agent 最难的一批底层问题做成了平台能力：session、event log、harness、sandbox、tools、vaults、permission、streaming、recovery。

如果要构建自己的 AI 自动化软件开发平台，Managed Agents 适合作为底层执行和长任务会话模型参考；Multica 适合作为团队协作和 runtime 分派参考；AgentX 适合作为 PRD 到交付的流程控制参考。
