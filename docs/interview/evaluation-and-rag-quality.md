# Evaluation And RAG Quality Interview Bank

本文归档更偏底层的评测、RAG 质量、scenario pack、回归比较和 badcase 归因高频题。  
这类问题和 runtime / retrieval 强相关，但重点不是“系统怎么设计”，而是“怎么证明它真的有效、哪里失效、怎么持续迭代”。

状态说明：

1. 本文是面试表达版，不替代 `docs/evaluation/*.md` 与 `docs/runtime/*.md` 真相文档。
2. 回答会明确区分当前代码真相和下一阶段目标方向。
3. 当前代码真相里的代码检索仍然以 lexical / symbol baseline 为主，不假装已经做了向量化主链。

## Eval 与场景设计

### [重要程度：高] 你能不能从发源、核心流程和工程实现三个层面，系统讲一下什么是 RAG？

这题不要一上来就说“就是检索增强生成”。  
更稳的讲法是按三个层次讲：为什么会有它、它的标准结构是什么、落地时真正难在哪里。

第一层，`为什么会有 RAG`。  
RAG 出现的直接背景，是纯大模型在很多任务里会遇到三个问题：

1. 参数知识有时过时
2. 遇到私有知识、企业知识或仓库知识时，模型参数里根本没有
3. 只靠参数记忆回答，容易出现幻觉，而且很难说明依据

所以业界开始把一个思路系统化：  
不要指望模型“脑子里刚好知道一切”，而是先去外部知识源把相关信息找回来，再让模型基于这些材料生成答案。  
这就是 RAG 的基本出发点。

第二层，`RAG 的标准结构是什么`。  
最经典的 RAG 可以拆成四步：

1. `Query`
   - 先把当前问题转成检索查询
2. `Retrieve`
   - 从外部知识库召回候选材料
3. `Augment`
   - 把召回结果和原始问题一起组装进 prompt / context
4. `Generate`
   - 让模型基于这些材料生成答案

所以 RAG 不是单一组件，而是一整条链：

`用户问题 -> 查询构造 -> 检索 -> 上下文组装 -> 模型生成`

第三层，`工程里真正难的地方`。  
很多人以为 RAG 难点只是“接个向量库”，其实真正难的是前后整条链的质量控制。至少包括：

1. 文档怎么切 chunk
2. metadata 怎么建
3. query 怎么改写
4. 召回后怎么 rerank
5. 放多少上下文进窗口
6. 如何减少噪音和截断
7. 如何评估到底有没有提升

所以更稳的总结是：

“RAG 的本质不是给模型塞更多文本，而是把外部知识接进推理链路，让模型尽量基于可追踪证据回答。” 

### [重要程度：高] 如果你要详细解释 RAG 的工程实现，一般会怎么拆模块？

更稳的回答不是“向量化 + 召回 + 生成”，而是拆成七个模块。

第一，`知识准备层`。  
先决定数据源是什么，例如：

1. 文档
2. FAQ
3. 数据库导出
4. 代码仓库
5. 日志和运行证据

第二，`预处理与切分层`。  
把原始材料变成可检索单元，常见动作包括：

1. 清洗文本
2. 分 chunk
3. 补标题和层级信息
4. 提取 metadata

第三，`索引层`。  
把 chunk 建成可查结构。常见有两类：

1. 关键词 / lexical / BM25 类索引
2. embedding / vector 索引

很多成熟系统会混合使用，而不是二选一。

第四，`查询理解层`。  
把用户问题改写成更适合检索的 query，例如：

1. query rewrite
2. 子问题拆分
3. 关键词扩展
4. 多路检索 query

第五，`召回与重排层`。  
先用较便宜的方式召回一批候选，再用 rerank 或规则筛掉噪音。

第六，`上下文编译层`。  
不是把召回结果原样塞进模型，而是要决定：

1. 哪些保留
2. 排序如何
3. 怎么和结构化事实拼在一起
4. 超窗口了先裁什么

第七，`生成与验证层`。  
最后才是模型生成；更成熟的系统还会补：

1. 引用来源
2. verifier
3. self-check
4. answer grounding

一句适合面试的总结是：

“RAG 的工程实现至少包含知识准备、chunking、索引、query 理解、召回重排、上下文编译和生成验证七层，而不是一个向量库加一个 prompt。” 

### [重要程度：高] 如果让你评估一个 RAG 或 context system 做得好不好，你会看哪些指标？

这题不要只答准确率，也不要只答召回率。  
更稳的回答是分三层看。

第一层，`coverage`。  
也就是该带进来的信息有没有带进来。典型指标包括：

1. fact recall
2. snippet hit rate
3. symbol hit rate
4. changed-files 周边上下文覆盖率

第二层，`noise`。  
也就是虽然召回了，但有没有把窗口污染得太厉害。典型指标包括：

1. 冗余率
2. truncation ratio
3. precision proxy
4. irrelevant snippet count

第三层，`task impact`。  
也就是这些上下文到底有没有让下游 agent 真的做得更好。典型指标包括：

1. node accepted rate
2. fallback count
3. tool waste ratio
4. overall workflow pass rate
5. badcase reduction

如果结合 AgentX，我会直接映射到 Eval Center 里的 `RAG_QUALITY` 维度。  
当前最核心的证据包括：

1. `CompiledContextPack`
2. `FactBundle`
3. `RetrievalBundle`
4. `expectedFacts`
5. `expectedSnippetRefs`

所以最稳的总结是：

“RAG 质量不能只看召回，更要同时看 coverage、noise 和 task impact。AgentX 当前会用 expected facts、expected snippet refs、retrieval bundle 和下游 workflow 结果一起判断上下文到底有没有真正起作用。” 

### [重要程度：高] RAG 评测里的 golden set 应该怎么建？

这题不能答成“人工写一些正确答案”。  
更稳的说法是：RAG 的 golden set 评的不是最后答案，而是“哪些事实和证据本来就应该被带进来”。

在 AgentX 里，我会把 golden set 分两类。

第一类，`expectedFacts`。  
它描述的是结构化或半结构化事实，例如：

1. requirement 里的关键验收条件
2. 任务的 write scope
3. verify 必须知道的 changed-files
4. 某个接口必须校验邮箱格式

第二类，`expectedSnippetRefs`。  
它描述的是关键文件、类或片段引用，例如：

1. `StudentService.java`
2. `StudentControllerTest.java`
3. `schema/student.sql`

它的作用不是要求平台一字不差捞回原文，而是要求系统至少命中“该看的那几处”。

更重要的是，golden set 不能凭想象写，最好来自三种材料：

1. badcase 回放后人工标注
2. requirement / verify / changed-files 已知依赖
3. 历史成功案例里稳定高价值的证据点

所以最稳的总结是：

“RAG 的 golden set 不是标准答案，而是 expected facts 和 expected snippet refs。它评的是‘该进上下文的证据有没有进来’，而不是直接评模型最终说得漂不漂亮。” 

### [重要程度：高] 你会怎么设计一个 retrieval 相关的 scenario pack？

这题最好结合固定字段来答，不要泛泛而谈。

一个 retrieval scenario 至少要回答五件事：

1. 评什么问题
2. 预期 workflow 主链怎么走
3. 预期哪些 facts 必须进入上下文
4. 预期哪些 snippet refs 必须命中
5. 这个场景是否必须依赖 repo context

结合 AgentX 当前 eval 设计，一个场景通常至少会带：

1. `scenarioId`
2. `title`
3. `prompt`
4. `expectedBehavior`
5. `expectedFacts`
6. `expectedSnippetRefs`
7. `expectedNodeOrder`
8. `repoContextRequired`

如果是 retrieval 场景，我会优先做这几类：

1. golden fact miss
2. changed-files miss
3. verify context miss
4. architect context insufficient
5. noisy retrieval over-limit

一句最稳的总结是：

“retrieval scenario pack 不是随便给一句 prompt 跑跑看，而是要把 expected facts、expected snippet refs、node order 和 repoContextRequired 这些东西一起写清楚，这样评测结果才可归因、可回归。” 

## 归因与回归

### [重要程度：高] 如果一个 RAG 场景失败了，你怎么判断是检索没召回，还是召回了但 agent 没用好？

这题非常关键，因为很多团队把所有失败都归到“检索不行”，其实不对。

更稳的拆法是三步。

第一步，看 `coverage`。  
如果 `expectedFacts` 和 `expectedSnippetRefs` 根本没进 `CompiledContextPack`，那大概率是 retrieval / context compilation 问题。

第二步，看 `pack 里有没有，但被淹没了`。  
如果关键信息进来了，但周围噪音太多、pack 被严重截断，或者关键 snippet 排在很后面没真正被消费，那更像是排序、压缩或噪音控制问题。

第三步，看 agent 决策是否和上下文冲突。  
如果 pack 里关键信息已经很清楚，但 node output 仍然忽略了这些事实，甚至做出和 evidence 冲突的 decision，那才更像是 agent policy / prompt / node contract 的问题。

结合 AgentX，我会重点交叉看：

1. `CompiledContextPack`
2. `FactBundle`
3. `RetrievalBundle`
4. node raw output / parsed decision
5. fallback reason

所以最稳的总结是：

“先看 evidence 有没有进 pack，再看 pack 有没有被噪音和截断污染，最后才看 agent 有没有正确消费这些 evidence。这样才能把 retrieval 问题和 decision 问题分开归因。” 

### [重要程度：高] RAG / retrieval 的 regression 你会怎么做比较？

这题不能只答“前后准确率比较一下”。  
更稳的做法是先看 hard gate，再看质量，再看效率。

第一层，先看有没有新的 hard gate。  
例如：

1. expected facts 直接 missing
2. verify 关键上下文缺失
3. repo context required 但 retrieval 为空

第二层，再看质量变化。  
例如：

1. fact recall
2. snippet hit rate
3. symbol hit rate
4. precision proxy
5. truncation ratio

第三层，再看效率是否恶化。  
例如：

1. context pack size
2. retrieval snippet 数量
3. tool call 数
4. fallback count
5. duration

如果结合 AgentX，我会优先比较：

1. `overallStatus`
2. `dimensions[].status`
3. `dimensions[].score`
4. `findings`
5. `fallbackCount`
6. `toolCallCount`

所以最稳的总结是：

“regression 不只是看有没有涨分，而是先看 hard gate 有没有新坏掉，再看 RAG 质量指标有没有改善，最后看成本和时延有没有明显恶化。” 

### [重要程度：高] badcase review 在这种系统里为什么重要？你会怎么做？

这题不要说成“人工看看错误案例”。  
更稳的说法是：badcase review 的价值，是把统计指标转成真正可行动的问题类型。

我一般会这样做：

第一，先按场景聚类。  
把 badcase 按题型、节点、工具类型、上下文缺失类型、fallback 类型聚类。

第二，再做人类归因。  
重点判断：

1. 是 expected fact 缺失
2. 还是 snippet 没命中
3. 还是 pack 噪音过大
4. 还是 agent 明明看到了但没用
5. 还是 tool / verify / workflow 契约问题

第三，把归因沉淀成可复用资产。  
例如：

1. golden facts
2. 新 scenario
3. 新 regression case
4. 规则或 prompt 修正项

如果结合 AgentX，这个流程其实和我们做 eval center 的思路是一致的：  
badcase review 不是为了证明模型笨，而是为了把失败回写成 scenario pack、report findings 和下一轮改进输入。

一句总结就是：

“badcase review 的价值不是看错题本身，而是把错误转成可归因、可复现、可回归验证的系统问题。” 

## 取舍与边界

### [重要程度：高] 为什么 AgentX 当前没有把 embedding / vector retrieval 当成第一优先级？

这题不能只答“还没做完”，要讲清楚为什么。

更稳的回答是：当前阶段我们的优先目标不是追求最强语义召回，而是先把主链边界、结构化真相、context pack 和运行证据跑通。

所以当前选择 lexical / symbol baseline，主要有四个原因：

1. 结构化事实本来就应该精确取数，不该被向量检索替代
2. 代码任务里很多查询天然更适合关键词和 symbol 搜索
3. 先把 overlay / base repo / changed-files 证据链跑通，比先引 embedding 更关键
4. 如果没有稳定 eval，先上向量库也很难知道收益是真是假

这不是说向量化永远不做，而是说它现在属于效果升级项，不是主链可信性的前提。

所以最稳的总结是：

“AgentX 当前不把 embedding / vector retrieval 放第一优先级，不是因为它没价值，而是因为在现阶段，结构化真相、代码探索主路径、上下文编译边界和 eval 闭环比更强的语义召回更基础。” 

### [重要程度：高] 如果 retrieval 变强了，但 overall workflow 没提升，说明什么？

这题很适合考系统观。

更稳的说法是：retrieval 只是系统中的一个环节，召回变强不等于端到端就一定更好。

可能的原因通常有四类：

第一，`召回了，但噪音更大了`。  
也就是 recall 上来了，但 precision 降了，agent 反而更难判断。

第二，`上下文进来了，但 agent 没消费好`。  
这时候问题不在 retrieval，而在 node prompt、decision contract 或工具策略。

第三，`真正瓶颈不在 retrieval`。  
例如 tool protocol、verify contract、task planning、write scope 冲突，可能才是主因。

第四，`metric 选错了`。  
如果只看 snippet hit rate，却不看 workflow success / fallback / hard gate，就容易误判“检索变强了”。

如果结合 AgentX，我会强调：  
我们最终不是优化一个独立检索器，而是优化固定主链上的 requirement / architect / coding / verify 整体效果。所以 retrieval 的价值，最终一定要回到 workflow eval 上看，而不能停留在局部指标。

一句最稳的总结是：

“retrieval 变强但 overall workflow 没提升，说明系统瓶颈可能不在召回本身，或者召回带来了更多噪音。最终必须回到 workflow eval 和 hard gate 上看局部优化有没有转成端到端收益。” 
