# Runtime 收口评估

本文只回答一个问题：截至当前阶段，runtime 是否已经从“基础设施仍不可信”推进到“基础设施可被当成稳定底座”，从而允许下一步直接做 controlplane / UI。

## 1. 结论

结论是：`是`。

当前 runtime 仍然保留很多升级空间，但这些空间已经主要属于：

1. agent 输出质量
2. 检索效果
3. 评测深度
4. 平台控制面与展示面

而不再是“主链基础设施还没站稳”。

## 2. 与冻结架构是否一致

当前实现仍然与三层架构、固定工作流和 L1-L5 分层保持一致。

### 2.1 三层架构

当前没有偏离：

1. `domain` 继续承载状态语义与端口
2. `runtime` 承载派发、执行、监督、上下文与检索
3. `controlplane` 仍未被运行时细节反向侵入

### 2.2 固定工作流

当前主链仍然固定为：

`requirement -> architect -> ticket-gate -> task-graph -> worker-manager -> coding -> merge-gate -> verify`

LangGraph 仍然只推进顶层 reconciliation，不承载底层运行真相。

### 2.3 L1-L5 真相

当前没有新增主表，也没有让运行时绕开真相层：

1. L3 仍然记录 workflow / node run
2. L4 仍然记录 requirement / ticket
3. L5 仍然记录 task / snapshot / run / workspace / lease / heartbeat

本轮唯一 schema 补强是：

1. `tickets.task_id`

它没有引入平行概念，只是把 `TASK_BLOCKING` 的显式归属从 payload 兼容字段提升为正式真相。

## 3. 哪些基础设施缺口已经关闭

以下能力现在已经不应再被归类为“基础设施缺口”：

1. 真实 Docker CLI runtime
2. 真实 Git worktree、merge candidate、verify checkout、cleanup
3. 中央 dispatcher claim 与 supervisor recovery
4. 四个 agent 的基础版内核接线
5. 独立 `ContextCompilationCenter`
6. 本地 lexical / symbol RAG baseline
7. capability -> runtime/tool/skill 的运行装配
8. 统一 `ToolCall` 协议、`callId` 归一化与同 run 幂等复用
9. `tickets.task_id` 显式 task blocker 查询路径
10. runtime readiness / preflight
11. 离线 agent eval baseline
12. 真实 DeepSeek smoke

这意味着后续如果流程跑不顺，优先应怀疑：

1. prompt 与输出质量
2. 检索召回质量
3. 任务规划质量
4. 控制面交互缺失

而不是先怀疑 runtime 连基本执行、恢复或证据链都没跑通。

## 4. 当前仍然属于升级项的内容

这些内容现在仍然重要，但已经从“主链必须补齐”转成“效果升级项”：

1. embeddings / vector recall
2. rerank 与更细粒度 chunk 策略
3. judge-based / benchmark / online eval
4. prompt 管理与版本治理
5. architect 更高质量 DAG 与 replan
6. coding/verify 更强的调用准确率与多轮质量
7. K8s、多 runtime、多仓

## 5. 刻意留到 controlplane / UI 之后的债务

当前刻意没有在 runtime 收口阶段继续扩这些面：

1. public HTTP API
2. workflow / ticket / task / run 查询控制面
3. 人工处理中心 UI
4. 运行观测面板
5. 更完整的 agent 评测治理台

这些并不是遗漏，而是阶段性压住的范围。runtime 先收口，后续 controlplane / UI 才有稳定底座可依赖。

## 6. 当前建议

下一步建议顺序：

1. 先做 controlplane / UI 的最小查询与操作闭环
2. 再把主要精力转向 agent 能力升级与更完整评测

原因很简单：

1. runtime 现在已经足够支撑控制面读写与展示
2. 只有把控制面和展示面立起来，后续 agent 调优和评测结果才更容易被持续观察和运营
