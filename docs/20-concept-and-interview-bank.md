# AgentX 概念与面试题复习库

这份文档用于沉淀之后会反复复习的内容。

更新方式：

1. 每完成一个正式微轮次，就补充或整理一次。
2. 只保留值得长期复习的概念、说法和题目。
3. 重点沉淀“怎么说”而不是“背定义”。

## 回答总原则

以后回答 AgentX 面试题，优先按下面这套顺序：

1. 先说业务问题
2. 再说为什么朴素做法不够
3. 再说 AgentX 怎么设计
4. 再说当前代码已经落地到哪
5. 最后说边界和后续增强

## 30 秒开场公式

- 第一句先定位：
  AgentX 是一个面向长流程软件交付的 Agent 控制面系统。
- 第二句再讲控制机制：
  它通过模块化状态机、上下文快照、Worker 调度、Merge Gate 和工单化 HITL，把需求、决策、执行、验证和交付串成闭环。
- 第三句最后讲它解决什么问题：
  它主要解决长会话工程场景里的幻觉、遗忘、越权和不可追责问题。

## 高频概念

### 1. 控制面

- 含义：
  不让 Agent 直接自由执行，而是用状态机、调度、门禁和审计链约束它。
- 在 AgentX 里的落实：
  `session / planning / execution / ticket / mergegate / query` 共同组成控制面。

### 2. 分层记忆

- 含义：
  记忆不是一段聊天历史，而是分层事实。
- 在 AgentX 里的落实：
  需求基线、ticket 事件、run 证据、repo context 共同组成“可执行记忆”。

### 3. 上下文快照

- 含义：
  把当前唯一有效事实编译成一份可绑定到 run 的上下文版本。
- 在 AgentX 里的落实：
  `task_context_snapshots` 负责 `PENDING / COMPILING / READY / FAILED / STALE`。

### 4. RAG 与裁决分离

- 含义：
  RAG 负责找候选，裁决负责决定哪份事实现在有效。
- 在 AgentX 里的落实：
  `WorkspaceRepoContextQueryAdapter` 负责召回，`ContextCompileService` 负责快照编译和事实裁决。

### 5. Toolpack

- 含义：
  把 worker 的能力边界显式化。
- 在 AgentX 里的落实：
  任务声明 `required_toolpacks_json`，worker 只在满足工具包约束时才能 claim。

### 6. WAITING_FOREMAN vs WAITING_USER

- 含义：
  一个是执行层等待决策，一个是工单层等待用户。
- 在 AgentX 里的落实：
  `task_runs.status=WAITING_FOREMAN`，`tickets.status=WAITING_USER`。

### 7. `DELIVERED != DONE`

- 含义：
  做完代码不等于最终集成完成。
- 在 AgentX 里的落实：
  只有经过 Merge Gate 的 `rebase -> VERIFY -> ff merge` 才能 `DONE`。

### 8. Query 聚合视图

- 含义：
  给前端看的字段不一定是单表字段。
- 在 AgentX 里的落实：
  `phase`、`canCompleteSession`、`deliveryTagPresent` 都来自 query 聚合逻辑。

## 高频问答池

### Q1. 这个项目到底是做什么的？

参考骨架：

AgentX 是一个面向长流程软件交付的 Agent 控制面系统。它不是让模型自由写代码，而是通过模块化状态机、上下文快照、Worker 执行层、Merge Gate 和 HITL，把 LLM 约束成可审计、可恢复、可交付的工程执行单元。

### Q2. 为什么不是直接用现成 Agent 产品？

参考骨架：

因为企业里真正难的是可控、可审计、可嵌入工程流程。AgentX 的重点不是演示“模型会写代码”，而是把需求、决策、执行、验证和交付串成一个责任链闭环。

### Q3. 记忆模块怎么设计？

参考骨架：

我们没有把 memory 设计成聊天历史缓存，而是拆成四层：确认需求、ticket 事件、run 证据、repo context。然后由 `ContextCompileService` 把这些事实编译成 `task_context_snapshots`，每次 run 必须绑定最新 `READY` 快照。

### Q4. RAG 在这个项目里怎么用？

参考骨架：

RAG 在 AgentX 里不是事实裁判，而是代码上下文召回器。当前代码是 `LangChain4jSemanticRepoIndexSupport + WorkspaceRepoContextQueryAdapter`，先走 semantic retrieval，失败或未命中时退回 lexical fallback，再把结果交给上下文编译层做裁决。

### Q5. 为什么还要上下文快照？

参考骨架：

因为 RAG 只能回答“像不像相关”，解决不了“哪一份事实当前有效”。快照把确认需求、ticket 事件和运行证据固化成单一事实来源，再用 `source_fingerprint` 判定新鲜度，避免旧要求继续执行。

### Q6. 怎么防止 Agent 越权？

参考骨架：

核心是三层边界：任务模板和 stop rules 约束行为，toolpack 和 write scope 限制能力，worktree / VERIFY 只读等运行机制限制破坏面。也就是说，不能只靠 prompt 说“请不要乱来”。

### Q7. 为什么需要 HITL？

参考骨架：

因为长流程软件交付里有很多点不是技术问题，而是取舍问题和缺信息问题。AgentX 把这类不确定性统一转成 `DECISION / CLARIFICATION` ticket，用户回复后先刷上下文再恢复执行，保证过程可审计。

### Q8. 为什么 `DELIVERED != DONE`？

参考骨架：

因为交付候选分支成功不代表主线集成成功。AgentX 把最终一致点放在 Merge Gate 上，只有 merge candidate 经过 VERIFY 并成功 fast-forward 到 `main`，任务才算 `DONE`。

### Q9. React 在这个项目里做了什么？

参考骨架：

React 控制台不是简单页面，而是整个控制面的可视化操作面。`useMissionRoom` 统一维护 session 级缓存、ticket inbox、task board、run timeline、runtime config 和自动轮询，再通过 query 聚合接口把后端状态机翻译成用户能理解的视图。

### Q10. 这项目最大的边界是什么？

参考骨架：

当前实现已经把控制面范式跑通，但还不是重型分布式平台。后续最大的增强方向在可观测性、持久化检索索引、更强的执行隔离和更稳的可靠性机制，不在核心范式本身。

## 用户反复追问池

### Q1. 记忆和 RAG 到底怎么区分？

- RAG 是召回机制。
- 记忆是事实治理和执行输入治理。
- 在 AgentX 里，RAG 给候选代码片段，快照决定最终有效输入。

### Q2. React 这块怎么讲得不空？

- 不要只说“用了 React + Vite”。
- 要讲 `useMissionRoom` 怎么承接 session 级状态、自动轮询、视图切换和 query 聚合结果。

### Q3. 为什么要保留代码锚点文档？

- 因为面试时最怕“会说不会落地”。
- 代码锚点能让回答快速回到真实类、方法和表。
