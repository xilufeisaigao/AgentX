# Dimension Catalog Reference

读取每个维度时，建议使用以下最短解释模板：

## `NODE_CONTRACT`

看点：

1. 输出结构是否合法
2. fallback 是否过多
3. 节点协议违规集中在哪

## `WORKFLOW_TRAJECTORY`

看点：

1. 固定主链是否被破坏
2. workflow 是否停在合理稳定点
3. human ticket / blocker / resume 是否一致

## `DAG_QUALITY`

看点：

1. catalog/template 是否对齐
2. DAG 是否有环或缺依赖
3. task 粒度和 critical path 是否合理

## `RAG_QUALITY`

看点：

1. expected facts 是否进 context
2. expected snippet refs 是否命中
3. retrieval 是否为空、是否噪声过高

## `TOOL_PROTOCOL`

看点：

1. `ToolCall` 结构是否完整
2. 相对路径协议是否被违反
3. 是否有无效、重复或高浪费调用

## `DELIVERY_ARTIFACT`

看点：

1. merge / verify 是否通过
2. changed files 与 requirement 是否对齐
3. review bundle 是否完整

## `RUNTIME_ROBUSTNESS`

看点：

1. retry / upgrade / cleanup 是否留下证据
2. supervisor 是否越权
3. lease / heartbeat / recovery 是否合规

## `HUMAN_IN_LOOP`

看点：

1. 该问人时有没有问
2. 问题是否最小充分
3. answer 后是否恢复推进

## `EFFICIENCY_REGRESSION`

看点：

1. 是否相比 baseline 明显退化
2. 波动是否过大
3. fallback / tool calls / duration 是否恶化
