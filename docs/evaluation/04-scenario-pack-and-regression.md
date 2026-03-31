# Scenario Pack 与 Regression 策略

## 1. 目标

Eval Center V1 不只服务于一次 smoke，而是要逐步形成可重复运行的场景包，用于：

1. baseline
2. smoke workflow 复盘
3. regression 对比

## 2. `EvalScenario` 应承载什么

一个场景至少回答以下问题：

1. 评什么
2. 预期 workflow 主链怎么走
3. 预期哪些事实必须进入上下文
4. 预期哪些 snippet 必须被召回
5. 是否必须使用 repo context

当前 `EvalScenario` 已承载：

1. `scenarioId`
2. `title`
3. `prompt`
4. `expectedBehavior`
5. `expectedFacts`
6. `expectedSnippetRefs`
7. `expectedNodeOrder`
8. `seed`
9. `repoContextRequired`

## 3. 推荐场景包

第一版推荐至少维护以下场景：

1. `student-management-happy-path`
2. `student-management-clarification`
3. `architect-catalog-misalignment`
4. `coding-absolute-path-violation`
5. `verify-pass-vs-fail`
6. `runtime-recovery-retry`
7. `retrieval-golden-fact-miss`
8. `complex-dag-replan`

## 4. 三类运行入口

### agent eval baseline

用于验证单节点契约和结构输出。

特点：

1. 纯离线
2. 可不依赖真实 LLM
3. 重点检查 `NODE_CONTRACT`

### workflow smoke eval

用于真实或半真实 workflow 的全流程报告生成。

特点：

1. 消费真实 `WorkflowRuntimeSnapshot`
2. 带 task run events
3. 带 context artifacts
4. 产出完整三件套

当前已落地的严格真实入口：

1. `src/test/java/com/agentx/platform/DeepSeekStrictWorkflowEvalIT.java`
2. `src/test/resources/evaluation/scenarios/*.json`

### regression pack

用于同一场景多次运行后的对比。

特点：

1. 比较 `overallStatus`
2. 比较每个 dimension 的分数和 findings
3. 关注波动和回归，而不是单次绝对高分

## 5. Baseline 与 Regression 如何比较

第一版建议先比较以下字段：

1. `overallStatus`
2. `dimensions[].status`
3. `dimensions[].score`
4. `hardGates`
5. `findings`
6. `durationSeconds`
7. `toolCallCount`
8. `fallbackCount`
9. `humanTicketCount`

推荐策略：

1. 先看是否出现新的 hard gate
2. 再看相同维度是否明显掉分
3. 最后看效率指标是否恶化

## 6. 复杂 DAG 场景建议

`complex-dag-replan` 场景建议包含：

1. 多模块任务
2. 串并行混合依赖
3. 一个需要 clarification 或 blocker 的支路
4. 至少一次 replan 或 fallback

这个场景的报告应重点关注：

1. DAG complexity metrics
2. catalog alignment
3. 已执行 task 是否被错误覆盖
4. critical path 是否无意义变长

## 7. RAG 黄金集场景建议

`retrieval-golden-fact-miss` 场景建议准备：

1. requirement 中明确写出的验收事实
2. 仓库中一组关键类或关键文件
3. verify 必须依赖的 changed-files 周边上下文

评价重点：

1. fact recall
2. snippet hit rate
3. noisy retrieval 是否过多

## 8. 当前原则

场景包是“平台效果优化的输入集”，不是 runtime 主链真相。

它们应当：

1. 保持可版本化
2. 保持输入和预期可审阅
3. 优先覆盖真实会出问题的场景，而不是追求数量

对真实 workflow 场景，优先复用：

1. `RealWorkflowEvalScenarioPack`
2. `RealWorkflowEvalRunner`
3. `DeepSeekStrictWorkflowEvalIT`
