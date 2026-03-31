# Eval Center V1 维度目录

本文定义 Eval Center V1 的 9 个固定评测维度。第一版策略是：

1. 先 hard gate，再看 quality
2. 尽量使用结构化证据，不依赖主观 judge
3. 每个维度都必须能落回现有主链边界

---

## 1. `NODE_CONTRACT`

### 评测目标

检查 `requirement / architect / coding / verify` 是否输出了平台能接受的结构。

### 主要证据

1. `evaluation-plan.json`
2. node raw output / parsed decision / fallback decision
3. `WorkflowNodeRun`

### Hard Gate

1. JSON 结构非法
2. 缺少必要字段
3. 非法枚举值
4. `toolCall` 结构不完整
5. verify decision 与已有 evidence 逻辑冲突

### Quality

1. summary 是否表达清楚
2. gaps / questions 是否高信号
3. fallback 是否频繁

### 报告展示

1. 每节点 `accepted / fallback / rejected`
2. fallback reason
3. parsed value 摘要
4. 协议违规列表

---

## 2. `WORKFLOW_TRAJECTORY`

### 评测目标

检查 workflow 是否遵守固定主链和 L4/L5 状态机约束。

### 主要证据

1. `WorkflowRuntimeSnapshot`
2. `WorkflowRun`
3. `WorkflowNodeRun`
4. `Ticket`
5. `WorkTask`
6. `TaskRun`
7. `GitWorkspace`

### Hard Gate

1. requirement 未确认即进入 task graph
2. workflow 已完成但任务并未全部 `DONE`
3. 仍有 `OPEN HUMAN ticket` 时 workflow 未停驻在等待人工状态
4. blocker 存在时仍错误向下推进

### Quality

1. 节点停驻点是否稳定
2. clarification -> answer -> resume 是否顺滑
3. repeated tick 是否保持幂等

### 报告展示

1. workflow state timeline
2. node transition timeline
3. invariant violation
4. resume / recovery 摘要

---

## 3. `DAG_QUALITY`

### 评测目标

检查 architect 产出的 task graph 是否合法、保守、并且贴合平台 catalog。

### 主要证据

1. `PlanningGraphSpec`
2. 物化后的 tasks / dependencies
3. `TaskTemplateCatalog`
4. write scope 声明

### Hard Gate

1. 非法 `taskTemplateId`
2. `capabilityPackId` 与模板定义不匹配
3. DAG 出现环
4. dependency 指向不存在 task
5. write scope 超出模板允许边界

### Quality

1. 模块划分是否清晰
2. 任务粒度是否过粗或过碎
3. 依赖是否最小充分
4. critical path 是否过长
5. blocker 升级是否合理

### 复杂 DAG 构建如何评

第一版不做 AI judge，而是先看结构指标和约束：

1. `taskCount`
2. `dependencyCount`
3. `fanInMax`
4. `fanOutMax`
5. `maxDepth`
6. `criticalPathLength`
7. catalog alignment error count

当 DAG 复杂时，报告至少应能回答：

1. 复杂度是否来自真实拆分，而不是无意义任务膨胀
2. 依赖是否出现不必要的串行链
3. 是否把跨模块改动压进单个 task，导致 write scope 过宽
4. 是否在 replan 时覆盖了已执行事实

### 报告展示

1. modules / tasks / dependencies 清单
2. 邻接关系或 textual DAG
3. catalog alignment findings
4. complexity metrics
5. “复杂 DAG 构建”专项点评

---

## 4. `RAG_QUALITY`

### 评测目标

检查 context compilation 和 retrieval 是否把该给 agent 的信息带到了。

### 主要证据

1. `CompiledContextPack`
2. `FactBundle`
3. `RetrievalBundle`
4. `WorkflowEvalContextArtifact`
5. `EvalScenario.expectedFacts`
6. `EvalScenario.expectedSnippetRefs`

### Hard Gate

1. 场景要求 repo context，但 retrieval 结果为空
2. 必要结构化事实缺失
3. verify 缺 changed-files 周边上下文
4. context pack 严重截断导致关键信息丢失

### Quality

1. golden facts recall
2. golden snippet hit rate
3. symbol hit rate
4. 冗余率
5. truncation ratio
6. precision proxy

### RAG 召回率如何评

第一版只围绕 lexical / symbol retrieval 和 golden facts/snippets 做，不提前引入 embedding/vector。

建议使用两类黄金集：

1. `expectedFacts`
   - 例如“邮箱校验”“回归测试”“changed files”
2. `expectedSnippetRefs`
   - 例如 `src/main/java/.../StudentService.java`

计算方式：

1. fact recall = 命中的 expected facts / expected facts 总数
2. snippet hit rate = 命中的 expected snippet refs / expected snippet refs 总数
3. repo-backed artifact count = 有 retrieval snippets 的 coding/verify/architect context 数

### 报告展示

1. 每个 context pack 的 fact summary
2. 每个 context pack 的 snippet 数
3. expected facts / retrieved facts
4. expected snippet refs / hit list
5. missing context candidates

---

## 5. `TOOL_PROTOCOL`

### 评测目标

检查 coding / verify 过程是否符合平台工具协议，并看执行轨迹是否高效。

### 主要证据

1. `TaskRunEvent`
2. `ToolCall`
3. `evaluation-plan.json`
4. 标准化后的 tool execution evidence

### Hard Gate

1. 工具未注册
2. 操作未注册
3. 使用绝对路径 `/workspace`
4. 越界写入
5. 重放有副作用的相同 `callId`
6. 非法 shell / git / filesystem 动作

### Quality

1. 是否先探索再修改
2. 无效调用比例
3. 每 task 的 tool call 数
4. 重试 / 重复调用比例
5. deliver 时机是否过早

### tool 协议对齐如何评

重点看这几个事实：

1. `ToolCall` 是否完整包含 `callId / toolId / operation / arguments / summary`
2. 路径参数是否是 workspace 相对路径
3. 同 `callId` 的幂等复用是否正确
4. 是否出现只为“试试看”而反复调用高成本操作

### 报告展示

1. tool call timeline
2. protocol violation list
3. path safety findings
4. idempotency reuse 摘要
5. wasteful calls 提示

---

## 6. `DELIVERY_ARTIFACT`

### 评测目标

检查最终交付是否真的构成可验收工件，而不是仅跑通流程。

### 主要证据

1. merge result
2. verify result
3. review bundle
4. changed files
5. deterministic build/test outcome

### Hard Gate

1. merge 失败
2. deterministic verify 失败
3. 核心工件缺失
4. requirement 要求测试，但没有测试交付
5. deliver 说明与实际改动不一致

### Quality

1. changed files 是否克制
2. 测试是否触达 requirement 核心点
3. write scope 与 changed files 是否对齐
4. review bundle 是否完整

### 报告展示

1. merge / verify 摘要
2. changed files 摘要
3. requirement-to-delivery coverage
4. review bundle 统计

---

## 7. `RUNTIME_ROBUSTNESS`

### 评测目标

检查 dispatcher / supervisor / retry / lease / heartbeat / resume 是否稳定。

### 主要证据

1. `TaskRun`
2. `TaskRunEvent`
3. `agent_pool_instances`
4. lease / heartbeat evidence
5. blocker / escalation evidence

### Hard Gate

1. 失联或失败后没有正确回写 run evidence
2. retry / upgrade 破坏状态机
3. supervisor 直接篡改 requirement / ticket 业务语义
4. cleanup 长期悬挂且无证据

### Quality

1. recovery success rate
2. mean retries
3. lease expiry handling quality
4. cleanup completeness

### fallback / recovery 如何评

第一版重点不是自动裁判“恢复决策优不优秀”，而是先回答：

1. 是否留下了可复盘证据
2. 恢复动作是否遵守状态机边界
3. 重试是否受预算约束
4. 失败后是否正确升级给更高层或人工

### 报告展示

1. runtime anomalies
2. retry / escalation timeline
3. cleanup leftovers
4. recovery findings

---

## 8. `HUMAN_IN_LOOP`

### 评测目标

检查是否在该问人时问人，不该问人时不过度打扰。

### 主要证据

1. `Ticket`
2. requirement clarification
3. architect/runtime escalation evidence
4. answer 后恢复轨迹

### Hard Gate

1. 本应创建 HUMAN ticket 却静默失败
2. ticket 类型或 blocking scope 错误
3. answer 后 workflow 未恢复

### Quality

1. clarification 是否最小充分
2. ticket 数量是否过多
3. escalation 文案是否具体
4. 是否对同一问题反复追问

### 报告展示

1. ticket list
2. answer / resume path
3. avoidable interruption candidates

---

## 9. `EFFICIENCY_REGRESSION`

### 评测目标

检查同场景重复运行时的稳定性、效率和回归变化。

### 主要证据

1. repeated eval runs
2. tool call counts
3. node / task / duration
4. future token/cost placeholder

### Hard Gate

1. 回归后通过率明显下降
2. 同场景结果波动过大
3. 协议错误率明显上升

### Quality

1. pass@1 / pass@n
2. median duration
3. median tool calls
4. fallback rate
5. human intervention rate

### 报告展示

1. scenario summary table
2. run-to-run variance
3. regression delta vs baseline
4. cost placeholder

---

## 10. workflow 全流程每个节点如何展示

`workflow-eval-report.md` 中每个节点至少应展示：

1. 节点名
2. 运行状态
3. startedAt / finishedAt
4. 是否走 fallback
5. 选中的输出摘要
6. 关联 ticket
7. 关联 recovery / escalation

推荐主链展示顺序：

1. requirement
2. architect
3. ticket-gate
4. task-graph
5. worker-manager
6. coding
7. merge-gate
8. verify

第一版允许某些节点没有完整证据，但不允许隐藏缺失。缺失本身就是 finding。
