# Runtime / Context / Agent Frameworks Interview Bank

本文归档与 runtime、上下文编译、代码探索、LangChain4j、LangGraph 相关的高频面试题。

状态说明：

1. 当前代码真相里，coding 仍然使用本地 lexical / symbol baseline。
2. 但下一阶段的 coding 目标已经从“继续做重代码 RAG”切到“结构化事实层 + Unix 类工具主动探索代码 + 读宽写窄权限隔离”。
3. 与资源授权、外部集成相关的异步处理目标，统一收口到 approval processing center。
4. 面试口径里必须把“当前真相”和“目标方案”分开讲。

## RAG、上下文与代码探索

### [重要程度：高] 我们项目里的上下文系统是怎么做的？

最稳的讲法不是先讲向量库，而是先讲“结构化事实层 + 节点化上下文编译”。

当前项目里，requirement、architect、coding、verify 四个阶段都不是自己随意去拼 prompt，而是先由 `ContextCompilationCenter` 根据当前 workflow / task scope 编译一份专用 context pack。

其中结构化事实层承载 requirement、ticket、task、task run、workspace、workflow run 这些业务真相。这一层继续走精确查询，不进入模糊召回，因为这些对象本身就是平台控制语义，不能被 memory 或向量检索替代。

非结构化部分在当前代码真相里仍然存在，真实落地的是本地 lexical / symbol baseline，用来补 repo、docs、schema、配置、日志等长文本片段。

但下一阶段的 coding 目标已经变了：coding 主路径不再继续强化代码 RAG，而是保留结构化事实层，把代码阅读改成 agent 基于 Unix 类工具主动探索仓库。

一句适合面试的压缩表达是：

“我们不是做一个泛化 chat memory，而是做 workflow-aware context compilation。结构化真相继续精确取数；当前代码真相里还有 lexical/symbol baseline，但 coding 的下一阶段会切到结构化事实 + Unix 探索工具，而不是更重的代码 RAG。”

### [重要程度：高] 我们有没有上向量数据库？

当前代码真相没有接入向量数据库，真实落地的是 lexical / symbol baseline。

更重要的是，向量化已经不再是 coding 主链的下一步优先方向。我们现在的目标调整为：

1. 保留结构化事实层
2. coding 阶段减少平台预注入的代码片段
3. 改用 Unix 类只读探索工具主动读仓库
4. 继续保持写权限硬限制

所以现在最稳的口径是：

“当前运行真相还是 lexical / symbol baseline；我们没有上向量数据库。下一阶段 coding 的重点也不再是继续做重向量 RAG，而是做 Unix 探索式代码阅读和读写权限隔离。”

### [重要程度：高] 为什么代码仓库检索很多时候更适合 grep / 范围化探索，而不是优先上向量数据库？

这题面试里非常容易答偏。  
更稳的回答不是“向量库没用”，而是“代码任务里的主问题很多不是自然语言召回问题，而是范围缩小和证据确认问题”。

我会先讲代码检索和通用文档检索的差别。

在代码场景里，很多高价值查询其实更像：

1. 找某个类、方法、字段、路由
2. 看某个关键词在哪些模块出现
3. 沿某个 import / symbol / path 继续展开
4. 把当前范围从仓库缩小到目录、再缩小到文件、再缩小到几行

这些动作本质上更接近工程师做的：

1. `ls` / `tree`
2. `grep` / `rg`
3. `glob`
4. `read_range`
5. git 状态和 diff 确认

也就是说，代码任务更常见的是“精确定位 + 逐步扩圈”，而不是“我给一句自然语言，你帮我模糊召回一段可能相关的话”。

为什么很多时候 grep / 范围化探索更稳，我一般会讲五点。

第一，`代码查询通常是关键词驱动的`。  
比如类名、接口名、表名、路径名、错误码、配置项，这些天然就适合关键词搜索，而不是语义相似度搜索。

第二，`grep 更容易逐步放大上下文`。  
你先看 5 行不够，可以再看 20 行、50 行；先搜当前目录不够，可以再扩大到模块甚至全仓。这种“读一点再扩大”的交互方式，非常符合真实工程分析。

第三，`向量检索在代码里容易受 chunk 粒度影响`。  
切片太小，前后文断掉；切片太大，噪音过多；排序一旦不准，就会把 agent 很早引到错误文件。

第四，`代码任务往往需要确定性证据`。  
比如到底哪个类实现了这个接口、哪个文件真的引用了这个方法，这种问题本质上要的是“确定性命中”，不是“语义上有点像”。

第五，`索引和刷新成本不便宜`。  
代码仓库持续在变，尤其是多 agent 并发写代码时，如果你把检索重心放在向量库，就要持续处理：

1. chunk 更新
2. overlay 合并
3. 旧 embedding 失效
4. 新代码可见性

这会带来额外复杂度。

所以更稳的结论不是“绝不用向量库”，而是：

1. 在代码主路径里，优先用 grep / glob / read_range / symbol / path 这类确定性探索
2. 向量或语义检索更适合做辅助手段，而不是 coding 的第一主路径

如果结合 AgentX，我会直接落到当前和目标设计。

当前代码真相里，我们的 baseline 本来就不是重向量库，而是 lexical / symbol retrieval。  
而目标方向更进一步：coding 阶段不再把“平台预注入代码 snippets”作为主路径，而是改成：

1. 平台先给结构化事实
2. agent 再用 Unix 类工具自己确认代码真相
3. 写入继续受 `writeScopes` 限制

所以最稳的面试总结是：

“代码仓库检索很多时候更适合 grep 和范围化探索，因为代码任务更像精确定位和逐步扩圈，而不是纯自然语言模糊搜索。向量检索可以做辅助，但不应该天然成为 coding 主路径。AgentX 的方向就是结构化事实 + Unix 探索，而不是重代码向量 RAG。” 

### [重要程度：高] 我们为什么不再把代码 RAG 作为 coding 主路径？

因为代码任务里，agent 更需要的是自己确认仓库真相，而不是先吃一包平台筛好的代码 snippets。

把代码 RAG 作为 coding 主路径，容易出现几个问题：

1. 模型被过早引导到错误文件
2. 当前任务不需要的代码噪音占用窗口
3. “平台已经给你 snippets”与“你仍需自己确认目录/文件/依赖关系”之间角色冲突
4. 一旦 retrieval 排序不准，就会直接污染 coding 决策

而 Unix 探索式路径更接近真实工程师的行为：

1. 先确认目录结构
2. 再 grep / glob / read_range 缩小范围
3. 最后才修改

所以新的设计不是删除上下文，而是把上下文层和代码探索层分工重做：

1. 结构化事实负责告诉 agent“任务是什么、写域在哪里、推荐先看哪”
2. Unix 工具负责让 agent 自己确认代码真相

### [重要程度：高] 当前代码真相里的 repo 检索是怎么做的？

当前真实实现里，关键词检索不是 Elasticsearch/BM25，而是 runtime retrieval 层里的 lightweight lexical scoring。

chunk 在建立索引时会带上：

1. 路径
2. 文本内容
3. Java symbols

检索时会把 query term 与这些字段做匹配：

1. 命中文本时加分
2. 命中路径时加额外分
3. 命中 symbol 时给更高分
4. 如果 chunk 属于 overlay 再做 boost

所以更准确的描述是：

“当前不是标准 BM25，而是 lexical + path + symbol + overlay boost 的代码检索基线。”

### [重要程度：高] 新方案下 coding 的上下文到底保留什么？

新方案不是把上下文砍空，而是保留高价值结构化信息，删掉 coding 主路径上的代码 snippets。

coding 仍然需要知道：

1. 当前需求和 workflow 背景
2. 当前 task 的目标、依赖、blocker、历史 run/workspace 证据
3. 当前允许修改哪些路径
4. 推荐优先探索哪些目录
5. 当前运行环境是什么，例如 Linux shell 还是 PowerShell
6. 当前有哪些 Unix 类工具可以用
7. 如果需要改写写域外文件，应该如何升级成 blocker

所以一句话概括就是：

“保留结构化事实层，移除 coding 主路径上的代码 RAG，把代码阅读切到工具探索。”

### [重要程度：高] 老项目、几万行代码，新的 coding 方案怎么分析仓库？

这题不要先答图数据库，也不要先答“给模型更多上下文”。

更稳的路径是三步。

第一步，先给 coding agent 足够强的只读探索能力。  
它先做：

1. 目录浏览
2. 关键词搜索
3. 入口文件阅读
4. 测试与配置定位
5. git 状态和仓库形状确认

第二步，再给范围提示，而不是全文喂料。  
平台通过：

1. task objective
2. 模块边界
3. exploration roots
4. sibling task 风险摘要

把探索范围先收窄到相关目录和相关模块。

第三步，repo graph lite 作为辅助视图。  
它不是 coding 主路径，而是帮助 architect 和 coding 更快理解：

1. 模块地图
2. path / symbol / import relation
3. 高扇入公共组件
4. 关键入口和公共契约

所以最准确的口径是：

“老项目分析的主路径是 Unix 探索；repo graph lite 只是辅助视图，用来缩小范围，不替代真实文件级确认。” 

### [重要程度：高] 怎么提前发现公共组件、基础能力？

这件事优先靠多信号叠加，不靠单一图数据库答案。

第一层信号是仓库结构：

1. 目录层级
2. 包结构
3. 配置和入口文件

第二层信号是代码关系：

1. symbol definition / reference
2. import relation
3. 高扇入组件统计
4. 被多个业务模块共同依赖的模块

第三层信号是运行与验证侧：

1. 哪些测试总围绕同一组组件展开
2. 哪些 verify 脚本反复依赖同一批能力
3. 哪些 adapter / contract 同时连接多个业务模块

识别完成后，不是把这些公共组件全文注入给模型，而是转成：

1. architect 视角的公共能力候选清单
2. coding 视角的 exploration roots
3. 仓库范围提示和边界摘要

所以更稳的总结是：

“公共组件发现优先靠符号索引、引用关系、目录结构和高扇入统计；repo graph 只是把这些信号更好地组织起来，最终仍然要回到文件级探索确认。” 

### [重要程度：高] 新方案里的权限边界是什么？

新的目标不是“完全放开”，而是读宽写窄。

也就是：

1. 读权限在当前 workspace 内尽量放宽，允许高效探索仓库
2. 写权限继续严格限制在当前 task 的 `writeScopes`
3. 一旦实现需要突破写域，不自动扩权，而是立刻转 blocker
4. 如果缺的是资源授权或外部集成事实，也先转 blocker，再由 architect 决定是否送入 approval processing center
5. coding 不直接找人，也不直接对接审批流

面试里可以直接总结成：

“我们希望 agent 像工程师一样广泛阅读，但不能像脚本一样悄悄越界改写。读权限要足够大，写权限要足够窄；缺资源和越界修改都必须显式升级。” 

### [重要程度：高] 如果 agent 需要额外资源或外部系统访问，怎么避免审批链反复打断？

这题现在最好直接讲 `approval processing center`，不要只答 `grant ledger`。

更完整的说法是：

1. coding 发现缺资源时，不直接找人，而是 `ASK_BLOCKER`
2. architect 决定是否把这个请求送进 approval processing center
3. center 负责规范化请求、契约校验、异步审批和结果回流
4. 批准结果会沉淀成 grant / integration contract，后续可复用
5. 请求里还能带一个策略，决定资源下来后是否立刻唤起 architect

所以更稳的总结是：

“我们把反复同步问权限的问题，升级成 approval processing center 的异步闭环；grant ledger 只是里面的授权事实，不是全部能力。” 

## LangChain4j 与 LangGraph

### [重要程度：高] 在我们的项目里是怎么使用 LangGraph 的？

LangGraph 在项目里只做一件事：编排顶层固定工作流。

它承载的是 requirement、architect、ticket-gate、task-graph、worker-manager、coding、merge-gate、verify 这些宏观节点之间的路由关系。`WorkflowDriverService` 每次以 `workflowRunId` 为 threadId 推进一次 graph tick。

LangGraph 不做这些事：

1. 不承载 `WorkTask / TaskRun / GitWorkspace` 真相
2. 不把每个 task 都建成 graph 节点
3. 不直接跑 Docker、Git worktree、merge、verify
4. 不作为业务真相源去直接改表

它的角色更像顶层 reconciliation engine，而不是底层任务执行器。

一句话可以概括为：

“LangGraph 只负责编排固定主链，不负责底层运行真相。”

### [重要程度：高] 在我们的项目里是怎么使用 LangChain4j 的？

LangChain4j 在项目里主要落在模型网关层，而不是 memory / retriever / agent 主架构。

当前主要用途是：

1. 组装 `SystemMessage / UserMessage`
2. 构造 `ChatRequest`
3. 根据返回对象生成 `JsonSchema`
4. 通过 OpenAI-compatible chat model 调 DeepSeek
5. 把模型返回解析成结构化 Java 对象

这意味着 LangChain4j 在项目里的职责非常克制：它负责“怎么把 prompt 发给模型并拿回结构化结果”，但不负责“这个节点应该拿什么上下文”。

真正决定上下文内容的是项目自己的：

1. `ContextCompilationCenter`
2. `FactRetriever`
3. `RetrievalBundle`

所以最准确的描述是：

“LangChain4j 在 AgentX 里是模型调用 SDK，不是主链 memory 框架。”

### [重要程度：高] 为什么说我们项目里用到 LangChain4j 的地方不多？

因为我们的核心问题不是做一个通用的长短期记忆架构，而是做动态上下文编译。

很多 LangChain / LangChain4j 项目关注的是：

1. chat memory
2. long-term memory
3. retriever-bound agent
4. 持续对话状态

但 AgentX 更像一个固定主链平台，状态真相在 requirement、ticket、task、run、workspace 这些结构化对象里。我们真正需要的是：

1. 在当前 workflow 节点上
2. 按当前 task / run / scope
3. 动态编译一份正确的 context pack

所以我们更依赖自己的 context compilation 边界，而不是 memory-first agent 框架。

一句适合面试的话是：

“我们的问题不是 memory orchestration，而是 context compilation，所以 LangChain4j 更像局部工具，而不是主架构。”

### [重要程度：高] 为什么 task 不直接建成 LangGraph 子图？

因为项目已经明确冻结为“顶层固定图 + 底层 runtime 扇出执行”的架构。

如果把每个 task 都建成 LangGraph 子图，会带来几个问题：

1. 顶层流程和 L5 执行真相混在一起
2. `TaskRun / lease / heartbeat / workspace` 这些运行状态会被 graph 节点语义污染
3. task fan-out 和恢复逻辑更容易被 graph 拓扑绑死
4. 每个 worker/task 都变成图节点后，中央派发制会被弱化，朝“worker 自抢任务”方向漂移

我们现在的设计是：

1. LangGraph 只编排 requirement 到 verify 的顶层固定节点
2. task 的真正执行真相留在 `task_runs / git_workspaces / agent_pool_instances`
3. `TaskDispatcher`、`CodingSessionService`、`RuntimeSupervisorSweep` 负责底层扇出、推进和恢复

这样分层以后，LangGraph 只回答“下一步流程往哪走”，runtime 只回答“底层 run 现在怎么跑”。这比给每个 task 建子图更符合当前平台边界。

### [重要程度：高] 为什么不直接用 LangChain4j 自带的 memory / retriever 做上下文？

因为我们这里需要的是“按 workflow 节点动态组装上下文”，而不是“给一个长期对话 agent 挂一套统一 memory”。

如果直接用 LangChain4j 自带 memory / retriever，会有几个问题：

1. requirement、architect、coding、verify 四个阶段的上下文差异很大，很难用同一套 memory abstraction 准确表达
2. `RequirementDoc / Ticket / WorkTask / TaskRun / GitWorkspace` 这些结构化真相不是普通对话记忆，不能被 retriever 化之后替代
3. 我们需要把 `write scope`、`changed files`、`taskId`、`workflowRunId`、`runId` 这类 runtime 约束显式编进 context pack，而不是靠通用 memory 框架隐式注入
4. 评测、证据留存、fingerprint、pack 裁剪都依赖 `ContextCompilationCenter` 这一条固定边界，直接用框架 memory 会把这条边界打散

所以项目更适合的设计是：

1. 结构化真相自己取
2. 上下文边界自己编排
3. facts 与运行守卫自己组装
4. coding 需要的代码真相优先交给工具探索
5. 最后再把 pack 喂给模型

这也是为什么我们一直强调：

“LangChain4j 负责模型调用，ContextCompilationCenter 负责上下文真相；coding 的代码阅读不应被通用 memory 框架劫持。”

### [重要程度：高] 我们项目里是否用了 ReAct 架构？

这个问题最好不要简单回答“用了”或者“没用”，而要分顶层架构和局部 agent 回路两层来讲。

如果说顶层平台架构，当前代码真相不是经典 ReAct。

AgentX 的目标主链已经冻结为固定主链：

`requirement -> architect -> ticket-gate -> task-graph -> worker-manager -> coding -> merge-gate -> integration-test-gate -> verify`

这一层由 LangGraph 只负责编排宏观节点，task/run/workspace/ticket 的业务真相继续落在数据库和 runtime 内核里。也就是说，平台不是让一个 agent 通过自由的 `Thought -> Action -> Observation` 循环去自发驱动全流程，而是让固定流程节点和状态机来约束主链推进。

如果说 coding agent 的局部执行回路，那它保留了一个“ReAct-like”的受限版本，但已经被强约束收口了，并且下一阶段会更接近“有权限边界的工具型探索”。

当前 coding 节点的真实协议不是自由文本式思考，而是结构化 `CodingAgentDecision`，每轮只允许输出：

1. `TOOL_CALL`
2. `ASK_BLOCKER`
3. `DELIVER`

其中工具调用又被进一步收口为统一 `ToolCall(callId, toolId, operation, arguments, summary)` 协议，并要求同一个 `runId` 内相同 `callId` 复用已有执行证据，避免重复副作用。下一阶段的变化不是放弃这条协议，而是把 coding 主路径从“平台预注入代码 snippets”切到“结构化事实 + Unix 类工具探索”。

所以最准确的面试口径是：

“我们没有把 ReAct 当成平台顶层架构，但在 coding agent 的单步决策里保留了一个受限的 ReAct-like 模式。顶层靠固定 workflow 和状态真相推进，局部工具调用才允许 agent 做 action/observation 回路。”

### [重要程度：高] 如果详细讲 AgentX 里的 ReAct-like 模式，你会怎么讲？

这题不要只答“thought-action-observation”，更稳的讲法是把它拆成一个受控 runtime 回路。

第一，先讲它出现在哪一层。  
AgentX 的 ReAct-like 模式只出现在局部执行层，主要是 coding agent 的单步决策回路；它不是 requirement 到 verify 的顶层系统架构。顶层仍然是：

`fixed workflow + state machine + central dispatch`

第二，回路的输入不是自由对话，而是受控上下文。  
每一轮真正给 coding agent 的输入，不应该是无限累积的 chat history，而是当前 task 的 `CompiledContextPack`，里面至少有：

1. requirement / workflow 背景
2. 当前 task objective、dependency、blocker
3. 当前 run / workspace 证据
4. 工具清单和 guardrails
5. `writeScopes`
6. 当前允许执行的 command 或 operation

也就是说，AgentX 里的 “Reason” 不是在真空里想，而是在结构化边界里做局部判断。

第三，决策输出被强约束成有限动作，而不是自由 thought。  
当前 coding agent 每轮不是随便写一大段思维链，而是输出结构化 `CodingAgentDecision`，动作集合被收口成：

1. `TOOL_CALL`
2. `ASK_BLOCKER`
3. `DELIVER`

这一步对应经典 ReAct 里的 “Act”，但动作空间已经被平台提前限制了。

第四，工具动作也不是任意 shell，而是 typed tool call。  
`TOOL_CALL` 会被进一步规范成统一 `ToolCall` 协议，至少固定：

1. `callId`
2. `toolId`
3. `operation`
4. `arguments`
5. `summary`

它的意义有两个：

1. agent 的动作变成可校验的结构化请求
2. runtime 可以对同一个 `callId` 做幂等复用，避免 repeated tick 里重复执行副作用

第五，真正执行动作的是 runtime，不是 agent 自己。  
tool request 发出来以后，执行链路会继续经过平台执行器做硬校验，例如：

1. tool 是否注册
2. operation 是否允许
3. 参数是否合法
4. 路径是否越过 `writeScopes`
5. 命令是否在 allowlist 内

也就是说，AgentX 的 “Action” 和 “Execution” 不是一回事。  
agent 只提出动作意图，runtime 决定它是否可以落地。

第六，Observation 也会被结构化落证据，而不是只回到聊天里。  
工具执行完以后，结果不会只作为一段自然语言塞回上下文，而是会沉淀成运行证据，进入：

1. task run event
2. workspace evidence
3. context artifact
4. 下一轮 pack 的最新观察摘要

所以它的 observation 是“有 run 真相绑定的 observation”，不是松散聊天记录。

第七，下一轮再基于最新 observation 做局部重决策。  
如果证据还不够，继续 `TOOL_CALL`；如果发现缺信息、缺权限或要突破边界，就 `ASK_BLOCKER`；如果已经满足交付标准，就 `DELIVER`。  
这就是 AgentX 局部 ReAct-like 回路真正的闭环：

`structured context -> bounded decision -> typed action -> runtime-validated execution -> persisted observation -> next decision`

如果面试官继续追问“那它和经典 ReAct 到底差在哪”，我会明确讲五点。

第一，不保留自由 thought 作为主真相。  
经典 ReAct 很强调 thought/action/observation 串起来持续推进；AgentX 则把业务真相放在 requirement、ticket、task、run、workspace 等结构化对象里，而不是放在推理文本里。

第二，动作空间被平台压缩了。  
不是任何时刻都能随便 search、write、shell、call api，而是只能在当前 contract 和 guardrails 允许的范围内动作。

第三，副作用被幂等和证据化了。  
`callId` 的存在不是细节，而是为了把“模型试探工具”变成可重放、可去重、可审计的 runtime 行为。

第四，人类介入被收口成 blocker。  
经典 ReAct 遇到缺信息时很容易继续自由追问；AgentX 则要求统一走 `ASK_BLOCKER -> Ticket / architect` 这条正式链路。

第五，它天然挂在固定 workflow 里面。  
也就是说，局部有 ReAct-like 回路，但回路之外仍然受顶层状态机和中心派发制约束，不会自己膨胀成一个自由自治系统。

如果结合我们项目下一阶段的方向，这个回路还会继续演进，但不是推翻重来。  
变化主要是 coding 阶段会越来越强调：

1. 结构化事实先给边界
2. agent 再用 Unix 类只读探索工具确认代码真相
3. 写入继续受 `writeScopes` 限制

也就是说，下一阶段变化的是 “Act” 的主要工具形态，不变的是这条受控 ReAct-like 回路的骨架。

所以这题最稳的总结是：

“AgentX 的 ReAct-like 模式不是让 agent 自由思考并驱动全平台，而是让 coding agent 在结构化 context pack 和 runtime guardrails 内，做 `TOOL_CALL / ASK_BLOCKER / DELIVER` 三选一决策。动作通过 typed `ToolCall` 下发，执行由 runtime 校验和落证据，下一轮再基于最新 observation 重决策。它本质上是局部自治、全局受控的 ReAct 变体。” 

### [重要程度：高] ReAct 架构有什么缺点，为什么我们没有把它当成平台主架构？

ReAct 不是不能用，而是更适合做局部 agent 回路，不适合直接当 AgentX 这种平台内核的主架构。

结合当前项目，主要有六个问题。

第一，它容易把“推理过程”和“业务真相”混在一起。  
在 AgentX 里，`RequirementDoc`、`Ticket`、`WorkTask`、`TaskRun`、`GitWorkspace` 都有明确状态机和持久化真相；如果让 agent 用自由 ReAct 驱动全流程，很多关键状态就会漂到 prompt、observation 或临时 memory 里，后续审计、恢复、补偿都很难做。

第二，它对副作用幂等不友好。  
经典 ReAct 很容易出现模型重复读文件、重复执行 shell、重复改写同一批文件。AgentX 当前专门引入统一 `ToolCall` 协议和 `callId` 证据复用，就是为了避免同一 run 在 repeated tick 下反复重放副作用。

第三，它会削弱中心派发制。  
当前平台冻结的是“架构代理拆任务，工作代理管理器派发，worker 不自抢任务”。如果把 ReAct 放大成主架构，agent 很容易演化成自己决定去哪找任务、自己决定是否重规划，最终破坏 `fixed workflow + central dispatch` 的边界。

第四，它不利于人工介入边界收口。  
AgentX 明确要求 worker 不直接找人，人工介入统一走 `tickets`。纯 ReAct 往往把“缺信息”处理成一次自然对话或自由追问，但在平台里这必须沉淀为正式 blocker ticket，否则就无法恢复、追踪和统计。

第五，它的评测、审计和回放稳定性较差。  
自由 ReAct 的中间 thought 和 observation 往往是松散自然语言，漂移很大；而 AgentX 当前更强调结构化决策、标准化工具执行证据、显式 run/event 真相，这样才能支撑 smoke、eval、failure attribution 和 replay。

第六，它的成本和时延不够稳定。  
一旦让 agent 自由地多轮试探工具、边看边想，token 成本、执行时延和失败模式都会快速发散。对平台型 runtime 来说，这种不确定性会直接放大到 dispatcher、supervisor 和 verify 侧。

因此更稳的总结是：

“ReAct 适合做局部问题求解，但不适合直接承载平台主链真相。AgentX 需要的是固定 workflow、结构化状态机、中心派发和可恢复 runtime，所以只在 coding agent 内部保留了受限 ReAct-like 工具回路，而没有把它升格成顶层系统架构。”

### [重要程度：高] 我们项目里 Git worktree 的作用是什么？

Git worktree 在 AgentX 里不是一个可有可无的工程小技巧，而是 task 级执行隔离的基础设施。

当前 runtime 的设计是每个 `TaskRun` 拥有独立 worktree，路径按 `workspace-root / workflowRunId / taskId / runId` 分配，分支名按 `task/<taskId>/<runId>` 命名，容器只挂载自己 task 的 worktree，而不是把整个仓库根目录作为可写卷。

它的核心作用主要有四个。

第一，隔离并发执行的写入面。  
多个 task 可以同时运行，但每个 task run 只在自己的 worktree 内改文件，不直接共享同一个可写工作目录。这能显著减少“两个容器同时改同一份工作目录”这种最粗暴的并发破坏。

第二，固化 task run 的执行快照。  
worktree 分配时会把 `base_commit`、`branch_name`、`worktree_path` 写回 `git_workspaces`。这意味着每次执行尝试都有明确的代码基线和可追踪的交付候选，不会和其他 task 的临时修改混在一起。

第三，支撑 merge-gate、模块集成测试和 verify 的闭环。  
task 完成后，不是直接把 task worktree 当成最终结果，而是由 merge-gate 在临时 merge worktree 上把交付并入模块集成候选；等模块达到可集成状态后，再进入模块集成 checkout 跑确定性集成测试；只有这些证据已经产出，verify agent 才会读取只读 verify checkout 做最终裁决。也就是说，编码、合并、集成测试、验证四步使用的是不同职责的工作区，而不是一个目录从头跑到尾。

第四，支撑失败恢复和清理。  
`GitWorkspace` 自己有独立状态机：`PROVISIONING -> READY -> MERGED -> CLEANED / FAILED`。这样 runtime supervisor 和 cleanup 流程可以明确知道某次执行尝试的工作区是否准备好、是否已产出 merge candidate、是否完成回收。

面试里可以压缩成一句：

“Git worktree 在 AgentX 里承担的是 task-run 级代码隔离和交付候选物化，不只是为了方便切分支，而是为了把并发执行、merge candidate、integration checkout、verify checkout 和 cleanup 都放到受控工作区里。”

### [重要程度：高] Git worktree 一定可以避免并发写文件冲突吗？如果不能完全避免，怎么处理？

不能把它说成“完全避免冲突”，更准确的说法是：

“Git worktree 可以显著降低直接的并发写目录冲突，但不能从根上消灭语义冲突和 Git merge 冲突。”

它能解决的，是物理层面的隔离问题。  
因为每个 task run 都写自己的 worktree，多个运行实例不会同时往同一个可写目录里落文件，所以像“共享工作目录被并发改坏”“一个容器覆盖另一个容器刚写的未提交文件”这类低级冲突，基本就被隔离掉了。

但它解决不了两类更高层的冲突。

第一类是语义冲突。  
即使两个 task 在不同 worktree 里改文件，如果它们改的是同一个类、同一个接口契约、同一段配置逻辑，最后合到一起仍然可能在语义上互相打架。worktree 只能隔离写入现场，不能保证两个任务的设计意图天然兼容。

第二类是 Git merge 冲突。  
两个 task 都可能从各自的 `base_commit` 出发独立修改同一文件或相邻代码块。到 merge-gate 构造真实 merge candidate 时，仍然可能出现标准 Git 冲突。当前项目文档已经明确：merge-gate 在临时 merge worktree 上执行真实 `git merge`，如果冲突或基础设施失败，则 `WorkTask -> BLOCKED`，并创建 runtime alert ticket。

AgentX 当前处理冲突的方式，不是让 worker 在主链上自由互相协调，而是走受控恢复路径：

1. 先通过中心派发和 task 依赖，尽量减少本来就应该串行的任务被并发执行。
2. 通过 task-run 独立 worktree，避免共享目录写坏。
3. 在 merge-gate 上用真实 `git merge` 暴露最终冲突，而不是假设“各写各的就一定能合”。
4. 如果 merge 失败，`GitWorkspace -> FAILED`，`WorkTask -> BLOCKED`，并创建 ticket 或 alert 进入可追踪的人类/架构代理处理链路。
5. 必要时由 `architect` 触发 replan，把原本并行的 task 改成串行、重拆 write scope，或者补一个专门的收敛任务。

所以面试里最稳的答法是：

“Git worktree 不能保证绝对无冲突，它解决的是并发写工作目录的隔离问题，不解决最终合并时的语义冲突。AgentX 的真正策略是：前面用中心派发、依赖约束和 worktree 隔离降低冲突概率，后面在 merge-gate 用真实 merge 暴露冲突，再把失败显式回写成 `BLOCKED` 和 ticket，交给架构代理或人工做 replan/收敛。” 

如果面试官继续追问“为什么不让 worker 自己解决冲突”，一个很稳的补充是：

“因为当前平台冻结的是中心派发制。worker 负责完成已分配 task，不负责自己重定义任务边界；一旦出现跨 task 的 merge 冲突，本质上已经超出了单个 worker 的局部执行边界，应该回到 merge-gate + architect / ticket 这条主链来处理。” 

## Skill 与上下文注入

### [重要程度：高] skill 有什么优势，为什么不直接注入上下文？

这个问题的关键在于区分“给材料”和“给工作流”。

直接注入上下文，通常只是在补充背景资料，告诉模型：

1. 项目现状是什么
2. 有哪些文档和代码可以参考
3. 当前任务的大致背景是什么

但这并不能保证模型会按我们希望的方式完成任务。它可能知道很多资料，却仍然：

1. 选择了错误的分析路径
2. 输出格式不稳定
3. 落到错误的文件位置
4. 忘记更新索引或关联文档
5. 把 target design 说成 current truth

skill 的价值就在于它不只补知识，而是把一类高频任务固化成项目内 SOP。一个项目级 skill 通常会明确：

1. 什么时候触发
2. 必须先读哪些真相文件
3. 固定工作流是什么
4. 输出 contract 是什么
5. 哪些 hard guardrails 不能碰

所以 skill 和上下文注入的关系不是二选一，而是分工不同：

1. 上下文解决“给模型什么材料”
2. skill 解决“模型应该怎么使用这些材料把事情做对”

在 AgentX 这种边界很强的项目里，skill 比单纯上下文注入更有价值，原因主要有四个：

第一，skill 可以固化工作流，而不是只补知识。  
比如 interview bank 归档，不只是知道仓库里有哪些文档，而是要先判断能不能插入现有主题文档、没有再新建文档、最后更新索引。这些步骤如果只靠上下文，很容易漂。

第二，skill 可以显式承载项目边界。  
我们项目里很多任务都有硬约束，比如不能发明平行概念、不能把设计稿写成现状、不能污染 truth docs。这类规则如果只放在长上下文里，很容易被后续对话稀释；写进 skill 之后，它们会作为显式 guardrail 长期生效。

第三，skill 可以提高输出一致性。  
如果只是临时注入上下文，同类任务可能每次都用不同格式、不同分析顺序、不同文件落点，长远看很难维护。skill 把输入、流程、输出结构固定下来之后，同类任务的结果会稳定很多。

第四，skill 更适合知识沉淀和复用。  
上下文注入更像一次性喂料；skill 则是把已经验证有效的做法沉淀成 repo 内资产，后续遇到同类任务不需要再重复解释。

因此在面试里，最稳的回答是：

“直接注入上下文只能提高信息量，skill 才能提高执行一致性。上下文解决‘给什么’，skill 解决‘怎么用这些上下文把事情做对’。在 AgentX 这种固定主链、强调真相来源和输出落点一致性的项目里，skill 本质上就是受 repo 真相约束的项目内 SOP。” 

### [重要程度：高] 如果项目很复杂，每次都要吃进去很多文件才能理解关系，但上下文窗口不够用，应该怎么优化？

这个问题本质上不是“如何塞更多文件”，而是“如何避免每次都从原始文件全文重新理解项目”。

如果一个复杂项目每次都要把上百个文件整包塞进模型，通常说明上下文组织方式还停留在“原始材料直喂”阶段，没有形成稳定的上下文分层。

更合理的优化思路，一般要同时做四层。

第一层，先把“事实”和“原文”拆开。  
不要把所有理解都建立在文件全文上。项目里很多高价值信息其实可以先被提炼成更稳定的结构化事实或中间摘要，例如：

1. 模块边界
2. 调用关系
3. 状态机
4. 关键入口
5. 关键表和核心字段
6. 文件与能力的映射关系

这样模型每次先读的是“已编译好的项目索引和事实摘要”，而不是一上来吃完整原文。

第二层，建立分层索引，而不是全文平铺。  
复杂项目通常至少要拆成：

1. 稳定全局知识
   - 架构说明
   - 模块地图
   - 数据库真相
   - 关键入口清单
2. 任务局部知识
   - 当前任务相关模块
   - 当前文件依赖链
   - changed files
   - write scope
3. 运行期增量知识
   - 本轮刚修改的代码
   - 最近失败日志
   - 本轮新生成的中间产物

这样每轮只注入与任务真正相关的一层，而不是把全项目原文全部带进来。

第三层，做 query-aware context compilation。  
不要把“需要什么上下文”交给人工拍脑袋决定，而是先根据当前任务生成查询信号，再用这些信号去检索或装配上下文。例如：

1. 当前任务标题和目标
2. 当前模块或目录
3. 涉及的类名、方法名、表名、接口名
4. write scope
5. changed files
6. 最近失败点

这意味着优化的重点不是单纯扩大窗口，而是把“找什么上下文”前置成一个明确步骤。

第四层，把长文档和长代码变成可复用的中间层。  
如果项目里某些文档每次都会用到，比如架构总览、模块地图、表结构说明、关键流程图，不应该每次都重新喂原文，而应该提前生成：

1. 模块摘要
2. 文件职责摘要
3. 关键依赖图摘要
4. 状态流摘要
5. FAQ / interview-style Q&A

这些中间层文档的作用，就是把“大量原始上下文”压缩成“高信号项目语义”。

在 AgentX 这样的项目里，更具体的做法不是“给模型喂更多文件”，而是建立一条显式的上下文编译链路。也就是：

1. 先根据当前节点和 scope 生成 `ContextCompilationRequest`
2. 再由 `ContextScope` 决定本轮是 workflow-scoped 还是 task-scoped
3. `FactRetriever` 先取结构化事实
4. `RetrievalQueryPlanner` 根据事实生成查询信号
5. `Retrieval` 层再去取 repo / docs / schema / log / memory 片段
6. `ContextCompilationCenter` 统一做裁剪、组装、落盘
7. 最终输出 `CompiledContextPack`

这条链路的关键不是“多加一层封装”，而是把项目理解过程显式拆成几个受控阶段。

具体来说：

第一步，按节点切上下文，而不是按会话切上下文。  
AgentX 当前固定有四类 pack：

1. `REQUIREMENT`
2. `ARCHITECT`
3. `CODING`
4. `VERIFY`

它们并不共享同一份大上下文，而是各自按节点语义取包。例如：

1. `REQUIREMENT / ARCHITECT` 走 workflow-scoped context
2. `CODING / VERIFY` 走 task-scoped context

这件事本身就已经是在节省窗口，因为模型不会每次都把 requirement、architect、coding、verify 全部上下文混在一起重新读。

第二步，结构化事实优先，而不是全文优先。  
`FactRetriever` 先取的不是代码全文，而是 workflow、requirement、ticket、task、snapshot、run、workspace 这些结构化真相。这样很多“项目关系理解”其实在进入模型前就已经被压缩成结构化事实了。

比如一个 coding task，真正必要的结构化事实往往包括：

1. 当前 task 是什么
2. 它依赖谁
3. write scope 在哪
4. 当前 blocker 是什么
5. 上一轮 run 和 workspace 的最新状态是什么

这些信息如果还让模型去一百个文件里重新猜，窗口当然会爆。

第三步，非结构化部分按节点做不同策略，而不是一刀切。  
在 AgentX 里，非结构化上下文的目标不是“把项目完整喂进去”，而是只补那些结构化事实不足以表达、但当前节点又确实需要的材料。新的方向是：

1. architect 阶段仍可补轻量 repo / docs / schema 片段
2. coding 阶段重点补 tool catalog、环境事实、allowed command catalog、exploration roots 和 write scope guardrails，而不是默认补代码 snippets
3. verify 阶段继续补 changed-files 周边证据、deterministic verify evidence 和最近运行证据摘要

这意味着不同节点看到的非结构化上下文也是裁剪过的，而不是整包复用。

第四步，用 artifact 化和 fingerprint 化提升复用率。  
`CompiledContextPack` 不是一次性字符串，而是可落盘、可追踪的 artifact，固定包含：

1. `packType`
2. `scope`
3. `sourceFingerprint`
4. `artifactRef`
5. `contentJson`
6. `factBundle`
7. `retrievalBundle`
8. `compiledAt`

这样一来，如果 source fingerprint 没变，就没必要每轮重新全量理解一遍项目。也就是说，窗口优化不只是靠“少塞点内容”，还靠“尽量复用已编译的上下文结果”。

第五步，超长时优先裁非结构化补充，不裁结构化真相。  
AgentX 当前的 pack 裁剪策略是：如果内容超过 `max-pack-size`，优先从 retrieval snippets 开始裁，而不是把结构化 facts 裁掉。未来 coding 切到 Unix 探索后，这条原则仍然成立，只是被优先裁掉的将是非结构化补充信息，而不是任务边界和权限事实。

这件事背后的设计原则非常重要：

1. 结构化事实负责保证正确性
2. retrieval snippets 负责补充相关性
3. 中间摘要和索引文档负责压缩全局背景
4. context pack 负责按当前节点把这些信息编译成模型真正要看的最小集合

这也是为什么我们一直强调，复杂项目的上下文优化不应该理解成“想办法一次塞更多文件”，而应该理解成：

“把全量原始信息转成可复用、可检索、可分层装配的项目知识结构。”

面试里如果要给出更工程化的答案，可以总结成一套固定优化路径：

1. 建立项目级索引文档，而不是只依赖源码全文
2. 为复杂项目做 module map、truth source、FAQ、interview bank 这类中间层
3. 对 requirement / architect / verify 节点按需建立 retrieval，对 coding 则更强调工具探索，而不是每轮全文注入
4. 用 task-aware / query-aware 的方式动态组装上下文
5. 把本轮增量内容和稳定全局知识分开处理
6. 只把最终高价值片段编译进当前模型窗口

一句最适合面试的总结是：

“上下文窗口不够时，正确优化方向不是继续堆窗口，而是把项目知识从‘原始文件集合’升级成‘分层索引 + 动态上下文编译’。也就是先沉淀稳定事实和中间摘要，再按当前任务检索和装配，而不是每次重新让模型读一百个文件。” 

### [重要程度：高] `AGENTS.md` 和 skill 相比有什么优劣势，应该怎么选？

这个问题的关键不是比较谁“更高级”，而是看它们解决的层级不同。

在项目里，`AGENTS.md` 更像全局治理规则，skill 更像某一类任务的标准化操作手册。两者不是替代关系，而是分层协作关系。

先说 `AGENTS.md` 的优势。

第一，覆盖范围大。  
`AGENTS.md` 一般放的是整个仓库都成立的规则，例如：

1. 架构边界
2. 真相来源
3. 目录分层
4. 命名禁忌
5. 编码原则
6. 提交前检查清单

这类规则不是某个任务专属，而是无论你在做 runtime、controlplane、database 还是 docs，都应该成立。

第二，适合承载“不能违反”的硬约束。  
例如在 AgentX 里：

1. 固定主链不能被改成自由工作流
2. LangGraph 不能承载业务真相
3. 不要发明平行概念
4. 结构化真相优先
5. 不要写胶水层和空心 DTO

这些东西最适合写在 `AGENTS.md`，因为它们属于 repo-level constitution，而不是 task-level workflow。

第三，进入仓库就能看到，适合做统一入口。  
这类文件通常是项目内所有 agent 和协作者都默认会先读的，所以它很适合承担“总规则入口”的角色。

但 `AGENTS.md` 也有明显局限。

第一，它不适合写太细的操作流程。  
如果把所有高频任务的具体步骤、输入输出格式、文件落点都塞进去，`AGENTS.md` 会迅速膨胀，最后变成一份所有人都不愿意读的大全。

第二，它不适合承载很多可选分支。  
例如“如何解读 eval report”“如何归档面试题”“如何写 scenario pack”这些都是特定任务，不是所有工作都会触发。如果都放进 `AGENTS.md`，会把全局规则和局部 workflow 混在一起。

第三，它缺少任务级触发语义。  
`AGENTS.md` 更多是在说“仓库总规则是什么”，而不是“什么时候应该执行某套专门流程”。

再说 skill 的优势。

第一，skill 适合承载高频、可重复、强边界的任务工作流。  
比如：

1. eval report reader
2. scenario pack author
3. capability profile author
4. interview bank curator

这些任务都有稳定输入、稳定步骤、稳定输出 contract，很适合写成 skill。

第二，skill 可以做最小加载。  
它可以明确规定：

1. 先读哪些真相文件
2. 只在什么场景触发
3. 输出到哪些目录
4. 哪些 hard guardrails 不能碰

这样比把所有细节都塞进 `AGENTS.md` 更节省上下文，也更利于复用。

第三，skill 更适合承载“局部 SOP”。  
它解决的是某类任务“应该怎么做”，而不是整个仓库“整体应该怎么治理”。

但 skill 也有局限。

第一，覆盖面天然比 `AGENTS.md` 小。  
它只在触发时才有价值，不能替代 repo 级总规则。

第二，如果项目总边界没有先写清楚，skill 很容易各写各的，最后局部合理、全局冲突。  
所以 skill 的前提通常是：`AGENTS.md` 先把大边界钉住。

第三，skill 过多时需要治理。  
如果什么事都 skill 化，后面会出现：

1. skill 重复
2. 主题重叠
3. 边界冲突
4. 找不到该用哪个 skill

所以 skill 不应该取代项目文档体系，而应该建立在文档体系之上。

在 AgentX 里，更合适的分工是：

1. `AGENTS.md`
   - 放 repo-level constitution
   - 写固定主链、三层架构、真相来源、编码边界、提交规则
2. `docs/*`
   - 放系统真相和模块说明
3. `.codex/skills/*`
   - 放高频任务的可执行工作流

所以如果面试官问“应该怎么选”，最稳的回答是：

如果这是整个仓库都成立、任何任务都不能违反的全局规则，就写进 `AGENTS.md`。如果这是某一类高频任务的标准做法，比如如何读报告、如何归档、如何写配置 profile，就写成 skill。

一句话总结就是：

“`AGENTS.md` 管全局边界，skill 管局部 workflow。前者像宪法，后者像 SOP。” 

## Chunk 与分块

### [重要程度：中] 我们新方案为什么不直接说用 LangChain 做分块？

不是不能用 LangChain，而是我们故意不把分块能力绑定到 LangChain。

原因在于，我们这里的 chunking 不只是文本切分，而是 runtime-aware 的索引构建问题。它必须同时解决：

1. 哪些内容该切
2. 属于 `base repo`、`workflow overlay` 还是 `workflow memory`
3. 绑定哪个 `workflowRunId / taskId / runId`
4. 带哪些 metadata
5. 什么时候 refresh
6. 什么时候 TTL 清理

LangChain 的 splitter 可以帮助切文本，但它不天然理解 workflow layer、task scope、verify failure summary、write scope 这些平台语义。

所以更合理的设计是：

1. 先定义项目自己的 `ChunkingPolicy`
2. 先定义 chunk schema、metadata contract 和 lifecycle policy
3. 再决定底层某个具体 splitter 是否借助 LangChain

这意味着 LangChain 可以是实现细节，但不应该成为 chunking architecture 的中心。
