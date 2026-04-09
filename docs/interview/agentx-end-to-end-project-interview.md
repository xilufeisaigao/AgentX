# AgentX End-to-End Project Interview Bank

本文整理这次 AgentX 项目全流程面试的高频问题，并给出更稳的答法。

状态说明：

1. 本文是面试表达版，不是代码真相文档。
2. 回答默认按已经确认的目标能力蓝图来组织，包括：
   - Unix exploration coding
   - requirement completeness gate
   - approval processing center
   - external integration contract
   - resource grant ledger
   - spec-first / verify-first
   - repo graph lite
   - write scope overlap governance
3. 代码实现仍在分阶段落地中；运行真相继续以 `docs/runtime/*.md` 和 `progress.md` 为准。

## 项目介绍与价值

### [重要程度：高] 你先做个自我介绍，并简单介绍 AgentX 是什么

我会把自我介绍和项目介绍分开，不会一上来把所有细节都讲满。

更稳的说法是：

“我做的是 AgentX，一个固定主链的软件交付 Agent 平台。它不是单个 coding bot，而是把需求确认、架构规划、任务拆分、编码、验证和合并收敛成一条可控主链。我做这件事的出发点不是让 AI 多写一点代码，而是让它在受控环境里持续交付，尽量减少权限打断、人工盯盘和不可验证输出。” 

如果面试官继续追问，再补：

1. task 级 Docker 运行时
2. Git worktree 隔离
3. blocker / ticket 回流
4. verify 和 merge gate

### [重要程度：高] 这个项目解决的业务问题是什么，主流程怎样

最稳的主线是先讲问题，再讲流程。

问题主要有三类：

1. AI 开发工具的权限边界容易失控
2. 人必须一直盯着它补权限、补上下文
3. 输出结果往往不可验证、不可追溯

对应主链是：

`requirement -> architect -> ticket-gate -> task-graph -> worker-manager -> coding -> merge-gate -> integration-test-gate -> verify`

每一层的职责都明确：

1. requirement 把模糊需求补成可确认的 requirement truth
2. architect 做技术方案、任务 DAG、write scope 和验收边界
3. coding 在受控 workspace 内实现
4. merge-gate 先把 task 改动并到模块集成候选里
5. integration-test-gate 在模块达到可集成状态时执行确定性集成测试
6. verify 决定模块级真实集成结果是否真的满足要求

### [重要程度：高] 你觉得这套系统和传统 AI coding 工具有哪些差异

我会强调三点，而不是泛泛说“更智能”。

第一，它不是人盯着一个 copilot 写代码，而是把需求、规划、编码、验证拆成固定主链。  
第二，它不把权限放给 agent 自由发挥，而是靠 execution contract、write scope 和 tool protocol 收口。  
第三，它把“人类介入”沉淀成正式 ticket / blocker，而不是随手打断当前对话。

## 岗位匹配与经历证明

### [重要程度：高] 如果这是一个探索型 AI / Agent 实习，而且对传统研发找工作的帮助可能有限，你为什么还想做？

这题不能只答“我很感兴趣”，那样太虚。  
更稳的说法是把“长期兴趣”和“短期收益”分开讲。

我会这样回答：

“我知道这种探索型岗位不像传统后端实习那样，能直接对应常规校招 JD，所以我不会把它包装成一个找普通研发岗的捷径。我想做它，主要是因为我对 Agent 和 AI 应用工程化这件事本身有持续兴趣，而且我已经在自己做相关项目，不是临时起意。对我来说，这个岗位真正有价值的地方，不只是写一点代码，而是学会怎么做评测、怎么捞 badcase、怎么归因、怎么把一个想法快速试出来，再用数据判断它到底有没有效果。这种能力即使以后不叫 AI agent engineer，它对系统设计、实验驱动优化和工程抽象也都是长期有价值的。” 

如果面试官继续追问“那你是不是以后还是想找后端”，我会收住回答：

“我不排斥后端，但我现在更明确的一点是，我希望做那种和智能系统、工作流编排、评测优化相关的工程问题。如果一个岗位只是纯 CRUD，我的热情反而没那么高。相比之下，探索型 AI 岗位和我现在主动投入的方向更匹配。” 

这题的关键不是证明“这个岗位最稳”，而是证明你知道它不稳，但你依然是主动选择，而不是被动凑过来。

### [重要程度：高] 你简历里写了 agent 编排、RAG、tool calling，这些有真正工程落地过吗？

这题很关键，因为面试官通常就在判断你做的是“项目级系统”，还是“把几个热门词拼在一起的 demo”。  
更稳的答法不是先报技术名词，而是先给一个完整闭环，再补关键实现点。

我会这样答：

“有，我自己做过一个多 agent 的软件交付系统，也就是 AgentX 这个项目。它不是单个 chat agent，而是把 requirement、architect、coding、verify 四个角色放进一个固定主链里，能从需求确认一直推进到任务拆分、编码、合并和验证。我不是只做了 prompt，而是把运行时、上下文、任务派发和验证链路一起打通了。” 

如果面试官继续深挖，我会补四层证据。

第一层，`角色和主链不是摆设`。  
我会明确说明：

1. requirement 负责把自由表述收敛成结构化需求
2. architect 负责把需求转成 task DAG、write scope 和验收边界
3. coding agent 只关注自己负责的 task
4. verify 负责做独立裁决，而不是让 coding 自评通过

第二层，`运行时是真正跑起来的`。  
不是内存里假装几个 agent 对话，而是：

1. task run 有自己的运行状态
2. 每个 task run 有独立 Docker / workspace
3. 用 Git worktree 做隔离
4. 冲突和失败会回写到 task / ticket / run 真相里

第三层，`tool calling 是受控执行，不是自由 shell`。  
我会说明：

1. agent 输出的是结构化决策
2. 工具调用会收口成统一协议
3. 有 allowlist、write scope、runtime guardrails
4. 超出边界不是继续乱试，而是 ASK_BLOCKER 回 architect

第四层，`上下文和检索不是简单塞一堆材料`。  
我会说明：

1. requirement / architect / coding / verify 用的是不同 pack
2. 结构化事实优先
3. coding 的目标方向已经从“重代码 RAG”切到“结构化事实 + Unix 探索”
4. 长期看不是 memory-first，而是 context compilation

一句最稳的压缩表达是：

“我不是只做了一个能聊天的 agent demo，而是做了一个有固定 workflow、结构化状态、受控 tool calling、独立 workspace 和 verify 闭环的多 agent 原型系统。它现在能跑简单需求，不是概念图。” 

## 权限、上下文与 Coding

### [重要程度：高] 你们的命令限制和权限隔离怎么做

我不会只回答“黑名单白名单”，而是会说“双层约束”。

第一层是执行合同：

1. 先规定可见工具
2. 先规定允许执行的 commandId
3. 先规定 writeScopes
4. 先规定可用 endpoint

第二层是执行器硬校验：

1. operation 不在 catalog 里，拒绝
2. commandId 不在 allowlist 里，拒绝
3. 写入路径越出 writeScopes，拒绝
4. 需要额外资源、外部集成信息或超出写域时，不重试乱试，直接 ASK_BLOCKER，再由 architect 决定是否送入 approval processing center

### [重要程度：高] 如果资源审批链很长，怎么避免 agent 一直打断用户

这题现在最稳的说法不是“做个审批队列”，而是“做审批处理中心”。

它比 grant ledger 更大，至少做四件事：

1. 接收 architect 发起的规范化资源请求
2. 对接异步审批流，而不是同步卡住当前任务
3. 把批准结果沉淀成可复用的 grant / contract
4. 按请求策略决定是立即唤起 architect，还是等下次统一批量补入

所以一句话可以概括为：

“我们不是每次缺资源都同步追问，而是把资源类 blocker 送进 approval processing center；它既能复用历史授权，也能处理异步审批结果回流。” 

### [重要程度：高] 审批处理中心为什么要用消息队列，放在闭环的哪个位置

我不会回答成“为了高并发”，因为审批处理中心的核心矛盾不是吞吐，而是慢事务解耦和可靠回流。

它需要队列，主要是因为：

1. architect 发起资源请求后，不能被长审批链路同步卡死
2. 审批回执、补材料、重试、唤起 architect 这些动作都需要异步推进
3. 外部审批系统短暂不可用时，不能直接把 workflow 打成失败

队列放的位置也要讲清楚：

1. architect 先发起规范化 `resource request`
2. 审批处理中心先做本地契约校验和可用性检查
3. 真正需要外部审批的请求，再进入消息队列
4. consumer 去对接企业审批流
5. 审批结果回来以后，再异步分发给 grant ledger、integration contract 和 architect 唤起逻辑

所以队列承接的是“异步审批处理”和“异步结果分发”，不是替代 architect 做主链判断。

### [重要程度：高] 审批处理中心的消息队列为什么选 RocketMQ

这题我会明确说：第一版我更倾向于选 `RocketMQ`，但审批请求、grant、contract 的业务真相仍然落 MySQL。

我选 RocketMQ 的原因主要是：

1. 审批处理中心面对的是业务消息，不是高吞吐日志流，所以我不会先选 Kafka
2. 它天然更适合承接延迟、重试、死信、补材料重提、结果回流这类审批场景
3. 后面如果要按资源域、审批类型、组织边界拆 topic / tag，RocketMQ 也更顺手
4. 它适合把“审批请求投递、审批结果回流、architect 唤起”做成统一业务消息闭环

所以最稳的表达是：

“审批处理中心的真相继续放在 MySQL，RocketMQ 负责异步传输层。这样数据库负责结构化真相，RocketMQ 负责慢审批链路的解耦、重试和结果分发，两边职责是清楚分开的。” 

如果面试官继续追问为什么不是 RabbitMQ，我会补一句：

“RabbitMQ 当然也能做，但我这里更看重后续按业务主题扩展、延迟和重试治理的一致性，所以第一版更倾向于 RocketMQ。” 

### [重要程度：高] coding agent 的上下文到底是什么

我会强调“结构化事实层优先”，而不是先讲向量库。

coding pack 里最重要的是：

1. 当前 requirement 背景和 workflow 位置
2. 当前 task 的 objective、dependencies、upstream task、blocker
3. 当前 run / workspace 的历史证据
4. 当前 runtime image、shell family、workspace root、工具族
5. 当前 writeScopes、explorationRoots、allowed commands
6. 当前 sibling task 的风险摘要

也就是说，coding agent 先知道“任务边界和环境边界”，再去看代码。

### [重要程度：高] 你们是不是用代码 RAG 给 coding agent 看仓库

现在对外讲项目，我会直接讲新的方向，而不是把旧的 ES / 向量 RAG 设计当现状。

更稳的说法是：

“coding 的主路径现在不是继续做重代码 RAG，而是保留结构化事实层，再让 agent 自己用 Unix 类工具探索仓库。平台负责告诉它任务是什么、哪些能改、推荐先看哪；具体代码真相由 agent 自己通过目录浏览、grep、读文件和 git 状态确认。” 

如果面试官追问历史设计，可以再补一句：

“我们之前考虑过把代码 RAG 做重，但后来发现它更适合做辅助检索，而不是 coding 主路径。” 

### [重要程度：高] 如果多个 task 并发写代码，没有代码 RAG 的增量互看，会不会冲突

这个问题要分三层回答。

第一层，不把并发 task 的未 merge 代码直接给别的 agent 看。  
因为那还不属于全局真相，直接喂原始增量代码会污染判断。

第二层，前移冲突治理。  
architect 和 dispatcher 会尽量用 dependency 和 write scope 拆开任务；coding pack 里会补 sibling task 摘要，让 agent 知道哪些路径有并发风险。

第三层，merge-gate、模块集成测试和 verify 做最终裁决。  
如果文本级冲突在 merge-gate 暴露，就回 architect 处理；如果 merge 过去了，但模块级集成测试或 verify 暴露了行为冲突，再回 architect 做 replan 或修复任务。

一句话总结就是：

“并发冲突的第一道防线不是 RAG，而是 DAG 和 write scope 治理；merge-gate 是最终兜底，不是唯一机制。” 

### [重要程度：高] 老项目、几万行代码，你怎么分析

这题我不会一上来答图数据库。

更稳的主路径是：

第一步，先给 agent 足够强的只读探索能力。  
它先做目录浏览、grep 关键词、读关键配置、看入口文件、看 git 状态、看测试结构，而不是先吃大段代码上下文。

第二步，用结构化提示缩小范围。  
architect 会结合 requirement、task objective、模块边界和 exploration roots，先给出一批推荐探索目录，避免 agent 在大仓库里盲扫。

第三步，再用 repo graph lite 做辅助。  
它不是取代文件探索，而是给出：

1. 模块地图
2. import / symbol relation
3. 高扇入公共组件
4. 关键入口和边界文件

所以最稳的表达是：

“老项目分析的主路径是 Unix 探索，不是先建重图；repo graph lite 作为辅助视图，帮助 architect 和 coding 缩小搜索范围。” 

### [重要程度：高] 怎么提前发现公共组件和基础能力

这里也不要直接跳到图数据库。

更工程化的做法是多信号叠加：

1. 看目录层级和包结构
2. 看 symbol definition / reference
3. 看 import relation 和高扇入依赖
4. 看哪些模块被多个业务模块共同引用
5. 看哪些测试、配置和 adapter 反复围绕同一组组件展开

然后把这些信号沉淀成两类结果：

1. architect 视角的公共能力候选清单
2. coding 视角的 exploration roots 和范围提示

所以 repo graph lite 在这里的作用是“帮你更快发现公共能力”，而不是“替代文件级确认”。

## Requirement、架构与任务规划

### [重要程度：高] 你们项目里的架构师 agent 到底负责什么？为什么它不是一个可有可无的拆任务机器人？

这题一定不要答成“它就是把需求拆成 task”。  
更稳的讲法是：架构师在 AgentX 里负责的是“把需求语言转成工程语言，并且持续做主链上的规划、重规划和边界治理”。

我一般会把它拆成五层职责来讲。

第一层，`做需求到工程的转译`。  
requirement 阶段拿到的是用户语言和业务目标，但 coding / verify 真正需要的是工程边界。  
架构师要把这些东西转成：

1. task objective
2. module 边界
3. task DAG
4. acceptance criteria
5. verify expectations

第二层，`做任务切分，不只是列清单`。  
它不是简单地把需求拆碎，而是要决定：

1. 哪些 task 可以并行
2. 哪些必须串行
3. 哪些地方要先补 clarification
4. 哪些地方需要 blocker

也就是说，它维护的不是任务列表，而是任务图和推进策略。

第三层，`做边界治理`。  
架构师要明确每个 task 的：

1. `writeScopes`
2. `explorationRoots`
3. capability requirement
4. 与 sibling task 的潜在冲突

这一步很关键，因为 coding agent 能不能安全并发，很大程度取决于架构师前面边界划得清不清楚。

第四层，`接住失败后的重规划`。  
如果 coding 卡住、verify 打回、merge 冲突、资源不足、上下文不完整，架构师不是只收一个“失败了”的消息，而是要根据失败摘要判断：

1. 是缺事实
2. 是 task 划分有问题
3. 是 write scope 不够
4. 是需要资源申请
5. 是该补修复任务还是整体 replan

所以架构师不是一次性 planner，而是整个 workflow 里的持续规划者。

第五层，`做人与系统之间的正式接口收口`。  
AgentX 明确要求 worker 不直接找人，所以 coding / verify 一旦缺信息或缺资源，都会先升级成 blocker，再由架构师决定：

1. 转 clarification ticket
2. 转 resource request
3. 直接重规划
4. 暂停主链等待人工

一句适合面试的总结是：

“架构师在 AgentX 里不是一个可有可无的拆任务机器人，而是需求语言到工程语言的转译器、task graph 规划者、边界治理者、失败后的重规划中心，以及人工介入的正式收口点。” 

### [重要程度：高] 为什么新加入的 approval processing center 和 repo graph lite 都挂在架构师旁边，而不是直接给 coding agent？

这题很容易答成“因为架构师更聪明”，但更稳的答法是讲边界。

先说 `approval processing center`。  
它挂在架构师旁边，是因为资源申请本质上不是局部编码动作，而是主链级规划问题。

coding agent 发现缺资源时，只知道“我现在做不下去”，但它不适合直接决定：

1. 这个资源是不是真的必须申请
2. 应该申请多大范围
3. 是立刻申请，还是先走别的 task
4. 这次申请是否会影响任务拆分
5. 申请回来后是立即唤起，还是延后批量补入

这些都是主链级判断，所以应该由架构师来发起规范化 request，审批处理中心再负责：

1. 契约校验
2. 异步审批流对接
3. grant / contract 沉淀
4. 结果回流和 architect 唤起

也就是说，审批处理中心不是 coding 的外挂工具，而是架构师的外围治理系统。

再说 `repo graph lite`。  
它也不应该直接交给 coding 当“真相源”，因为它的作用是做范围提示和结构理解，而不是替代文件级确认。

repo graph lite 更适合先服务架构师，原因是：

1. 架构师要先理解模块边界
2. 架构师要识别公共能力候选
3. 架构师要判断任务应该怎么切
4. 架构师要决定 exploration roots 怎么给
5. 架构师要更早识别高影响面和高扇入区域

所以 graph 的第一手产物应该优先变成：

1. 模块地图摘要
2. exploration roots 推荐
3. 公共组件候选清单
4. 高影响面节点提示

然后再由架构师把这些结果转成 coding 可消费的任务边界和范围提示，而不是让 coding 直接把图当答案。

如果把这两者放在一起看，逻辑就很清楚了：

1. approval processing center 解决的是“资源边界”
2. repo graph lite 解决的是“代码边界”
3. 两者都属于架构师做规划和重规划时需要的辅助能力
4. 它们都不应该绕过架构师，直接把局部 worker 变成自由自治体

所以最稳的总结是：

“approval processing center 和 repo graph lite 都挂在架构师旁边，不是因为架构师更万能，而是因为这两个能力处理的都是主链级边界问题。一个决定资源和外部依赖怎么进入主链，一个决定代码范围和任务边界怎么收敛，它们天然属于规划与重规划层，而不是局部 coding 执行层。” 

### [重要程度：高] requirement 文档怎么生成

我不会说成“和 agent 聊完自动出一份 PRD”。

更稳的说法是：

“requirement agent 先按模板做多轮补洞，再根据 completeness checklist 产出草案。关键点不是生成文档本身，而是没补齐验收标准、异常流程、外部依赖、权限和数据约束之前，不放行 architect。” 

这就是 requirement completeness gate 的意义。

### [重要程度：高] 需求和代码之间的 gap 怎么解决

不是靠架构师临场猜，也不是靠 coding 自己补。

在 AgentX 里，gap 会被转成结构化 ticket：

1. 技术选型类问题，走 option ticket
2. 缺事实类问题，走 clarification ticket
3. 外部依赖不完整，走 approval processing center 相关 ticket，再收口成 integration contract

答案一旦确认，就回写真相源，后续 architect、coding、verify 共享同一结论。

### [重要程度：高] 需求里涉及第三方系统，但接口信息不全，怎么办

这题我现在会直接讲 integration contract。

做法不是让 agent 自由猜接口，而是先把缺口升级成结构化 ticket，再由 approval processing center 完成校验和沉淀，最后收敛成一份结构化 contract，至少写清：

1. endpoint / method
2. auth mode
3. request / response schema
4. environment
5. owner
6. 谁批准了它

如果信息还不全，就先走 requirement / architect 阶段的澄清 ticket，不直接放给 coding；contract 校验失败时也会重新回到 ticket，而不是把坏数据直接放行。

### [重要程度：高] 如果第三方系统没有现成 skill 或 MCP，你怎么接

我会回答：

“没有 MCP 也不能回到自由 HTTP。我们会先走 approval processing center，把第三方系统信息校验成 integration contract，再把批准过的 endpoint 以 allowlisted 工具形式暴露给 agent。这样即使没有现成 skill，模型调用的仍然是受控 contract，而不是自己拼请求。” 

### [重要程度：高] 你们的依赖图是什么，怎么用

依赖图不是为了炫技，而是为了做两件事：

1. 决定哪些 task 可以并行推进
2. 决定哪些 task 因为前置条件未满足必须等待

同时在升级后，architect 不只维护 task DAG，还会维护：

1. write scope
2. exploration roots
3. acceptance criteria
4. verify expectations

所以 DAG 不是单纯“节点连边”，而是任务规划和冲突治理的骨架。

### [重要程度：高] task 拆分原则是什么

我现在会按四个原则回答：

1. 依赖关系要清楚
2. write scope 尽量独立
3. 每个 task 都要有明确交付物
4. 每个 task 最好都有 verify expectation

也就是说，拆分不是越细越好，而是要保证：

1. 能并行
2. 能验证
3. 出问题能定位
4. 出现越界能回到 architect 重规划

### [重要程度：高] 前后端任务是一起做还是拆开做

我不会绝对地说“一次性做完”。

更稳的是：

1. 小的全栈故事可以由一个 task 端到端完成
2. 只要涉及 shared contract、API、页面和联调，就优先拆成 shared / api / web / test
3. verify 最后裁决这些层是否一致

这样更适合大模型，也更利于并行与回退。

### [重要程度：高] requirement 阶段怎么保证完整度

这一题现在最好的口径就是 requirement completeness gate。

我会说：

“我们不再把 requirement 阶段理解成写一份模板文档，而是设置门禁。业务目标、范围、主流程、异常流程、验收标准、非功能要求、外部依赖和权限数据约束没补齐之前，不让 architect 往下走。” 

## SDD / TDD / 验证

### [重要程度：高] 你有没有借鉴 SDD 和 TDD

我不会说成“用了某个框架”，而是强调平台机制。

在 AgentX 里：

1. spec-first 对应 architect 先给 task spec、writeScopes、acceptance 和 verify expectations
2. verify-first 对应关键任务先准备测试脚本、smoke、场景验收，再让 coding 围绕这些实现

如果一次任务反复修补仍然过不了 verify，我也不会让 coding agent 在原代码上无限增量打补丁。

更稳的做法是：

1. 回到 architect 重新看 spec、write scope 和 acceptance 是否有问题
2. 必要时直接回退到上一个稳定版本
3. 重新产出更清晰的 requirement doc 或 task spec，再发新一轮 coding

所以它们在平台里是 workflow 机制，不是概念标签；重点是先固定边界、再实现、最后按同一套 verify 裁决，而不是让模型边写边猜。

### [重要程度：中] 你了解哪些 SDD 框架

这题我会收着答。

更稳的是：

“我会参考一些产品和实践思路，比如 Kiro 这类更强调 spec-first 的 IDE 方向，但我自己不是在套某个 SDD 框架。我更关注的是把 requirement doc、task spec、write scope 和 verify expectation 这些机制真正收进平台主链。” 

### [重要程度：高] 你们怎么保证最终代码和需求一致

我会按三层说：

1. requirement 把目标和验收边界固定下来
2. architect 把它转换成 task spec、write scope 和 verify expectation
3. coding 先按任务内的单测、构建和基础 smoke 自检
4. 模块集成测试闸门先给出确定性集成证据
5. verify agent 再按模块级或场景级标准裁决

如果模块级 verify 发现和需求不一致，我不会让同一个 coding task 无限自我修补。

更稳的是：

1. architect 先看是 spec 问题、实现问题，还是上下游契约问题
2. 必要时补一个 diagnose / repair task
3. 如果发现一开始规格就不对，直接回到 architect 或 requirement 重新出文档

这样一致性不是靠 coding agent 自我感觉，而是靠前面固定好的 truth、中间的任务内自检，以及最后的模块集成测试 + verify 裁决。

### [重要程度：高] 你们现在测试做到了什么程度

现在最稳的口径是：

“平台已经把 deterministic verify command、单测、构建和 API smoke 收进正式主链。对后端模块，我会更强调 mock / fixture 验证和真实请求 smoke；浏览器级联调验证和更完整的 UI 场景验证是下一阶段方向，会通过 Playwright 一类工具补上，但我不会把它说成已经完整落地。” 

### [重要程度：高] 既然已经有 verify，为什么还要单独有一个验证 agent 角色

这题我会先把“verify 是阶段”讲清楚，再解释“验证 agent 是角色”。

在 AgentX 里，verify 不是单一动作，而是模块级收口阶段里的最后一层裁决。

更准确地说，完整收口至少有两层：

1. 先跑模块集成测试闸门里的确定性验证，例如构建、API smoke、模块集成脚本、场景回放
2. 再由 verify agent 结合 requirement、task spec、integration evidence 和变更上下文做语义级裁决

为什么要把验证 agent 单独拿出来？因为“代码能跑”不等于“需求满足了”。

典型问题包括：

1. 测试全绿，但实现偏离了 requirement 的业务边界
2. 单个 task 看起来没问题，但几个 task 合起来后模块行为不一致
3. 没有文本冲突，但 merge 后出现了语义冲突

所以验证 agent 的职责不是重复跑测试，而是做独立裁决，输出：

1. `PASS`
2. `REWORK`
3. `ESCALATE`

这样可以避免 coding agent 一边写代码、一边给自己判通过，最后把“能解释过去”当成“真的交付完成”。

### [重要程度：高] 验证 agent 是在合并前起作用，还是合并后起作用

这题一定要把 merge-gate、模块集成测试闸门和 verify agent 的先后顺序讲准。

在 AgentX 里，顺序是：

1. coding 先交付 `DELIVERED`
2. merge-gate 在临时 merge worktree 上把 task 改动并入模块集成候选
3. 当一个模块达到可集成状态时，integration-test-gate 才会在模块集成 checkout 上跑确定性集成测试
4. 只有集成测试证据已经产出之后，verify agent 才启动，输出 `PASS / REWORK / ESCALATE`

所以 verify agent 严格来说不是在“task 一 merge 完就启动”，而是在“模块级集成测试已经跑完并产出证据之后”才发挥作用。

但这里要注意一句面试表达：

它不是“代码已经最终并入主线以后再验收”，而是“merge-gate 先把多个 task 的改动汇成模块集成候选，集成测试闸门先跑确定性集成测试，verify agent 再在只读集成候选上做最终裁决”。  
也就是说，它验证的是模块级真实集成结果，而不是 coding task 自己工作区里的局部结果。

这样设计的原因是：

1. 单个 task 自检只能证明局部实现没明显坏掉，不能证明模块整体成立
2. 只有在模块集成候选上验证，才能看到真实集成结果
3. 可以把 coding worktree、merge worktree、integration checkout 和 verify checkout 四个职责隔离开
4. `DELIVERED != DONE`，必须等模块级集成测试和 verify agent 都通过，`WorkTask` 才能进入 `DONE`

### [重要程度：中] 你们现在主要做哪些测试，后续前后端功能测试怎么补

这题我会把“当前真相”和“下一阶段”分开讲。

当前已经比较稳的是后端链路：

1. coding task 内先跑单测和构建
2. 对关键流程补 mock / fixture 数据验证
3. verify 阶段再跑 API smoke，必要时用 Python 脚本发真实请求验证主流程

如果面试官追问前后端联调，我会明确说这是下一阶段重点，而不是假装已经全量落地。

更合理的方向是：

1. 用 Playwright 这类浏览器工具跑页面级操作和关键路径回放
2. 把前端动作、后端日志、接口响应和数据库证据串起来看
3. 对视觉结果或复杂交互，再补截图和多模态校验能力

所以整体思路是：后端先把 deterministic verify 和真实请求验证打牢，前后端联调再逐步补浏览器级场景测试。

## 老项目增量开发与复用

### [重要程度：高] 如果新需求和历史任务很像，怎么让流程更简单

我不会说“继续给更多代码 RAG”。

更好的做法是优先复用：

1. 历史 task spec
2. 历史 blocker 的已批准答案
3. 相似模块的 verify 脚本
4. 失败原因摘要
5. exploration roots 提示

也就是优先复用“决策和边界”，而不是一上来复用大量代码片段。

### [重要程度：高] 如果是一个已有十万行代码的系统，这套机制还能工作吗

可以，但主路径必须从“全文看代码”切到“范围化探索”。

我会这样讲：

1. 先靠 repo graph lite 和模块地图给出推荐探索范围
2. 再让 coding agent 自己在这个范围里做 Unix 探索
3. 结构化事实继续告诉它当前 task 目标、边界和风险
4. 最后通过 merge-gate、模块集成测试和 verify 收口

这比直接做重代码 RAG 更稳，也更符合老仓库的复杂性。

### [重要程度：高] 老项目接进来之前，会不会先做一轮项目分析

会，但我不会把它理解成“先把十几万行代码全文喂给模型”。

更稳的是分层分析：

1. 先看目录树、模块边界、入口文件、配置和测试结构
2. 再由 architect 下发小粒度 exploration task，让不同 agent 分别摸清一个包或一个模块的职责
3. 各 exploration task 只返回高密度摘要，例如模块作用、关键类、外部依赖、公共契约
4. architect 最后再把这些摘要拼成模块地图和 exploration roots

这样做的重点不是一次吃完所有上下文，而是把大仓库拆成可控的探索单元，再把结果结构化沉淀下来。

### [重要程度：高] 老项目里怎么提前发现公共组件，避免新功能重复造轮子

我不会回答成“主要靠人记忆”或者“继续扩大 RAG”。

更稳的是结合 repo graph lite 和文件级确认：

1. 先看哪些模块有高扇入，被很多业务共同依赖
2. 再看 import relation、symbol reference 和 shared contract
3. 同时看入口、配置、adapter、测试是否反复围绕同一组能力展开
4. 最后回到具体文件确认这个组件到底是真公共能力，还是只是耦合中心

平台输出的不是一大包原始代码，而是：

1. architect 视角的公共组件候选清单
2. coding 视角的 exploration roots 和复用提示

这样新功能在开始写之前，就更容易先看到现成基础能力，而不是写完才发现仓库里早就有一套。

### [重要程度：中] 你们会直接上图数据库来做代码图吗

这题我会明确收住，不会把图数据库讲成第一前提。

AgentX 当前更合理的路线是 repo graph lite：

1. 第一版先做 path、module、symbol、import 和高扇入统计
2. 先产出 `module map`、`public component candidates`、`exploration roots`
3. 先验证它是否真的提升 architect 和 coding 的效率

只有当查询复杂度、增量更新成本和跨仓库规模真的逼到这一步，才考虑图查询服务或图数据库。

所以更准确的表述是：

“图视角我认同，但第一版不会直接把项目做成图数据库项目；先做轻量 artifact，把它当辅助视图，而不是新的真相源。” 

### [重要程度：中] 大仓库探索会不会很耗 token，你怎么控制成本

会，所以主策略不是让最强模型全文读代码，而是把探索任务分层。

更稳的做法是：

1. 先用便宜一些的小模型或轻量 agent 做目录、包、类级别的探索摘要
2. architect 只接收这些结构化摘要，而不是原始大段代码
3. 只有进入具体 coding task 时，才让更强模型去读和当前任务直接相关的文件
4. repo graph lite 继续帮助缩小范围，减少无效搜索

所以成本控制的关键不是“少做分析”，而是把探索、规划和实现分层，用不同粒度和不同成本的能力处理不同问题。
