# AgentX Eval Report Reader

本 skill 用于读取 AgentX Eval Center V1 产出的报告，并输出一份面向实现的优化计划。

## 何时使用

当用户出现以下需求时使用：

1. 解读某次 workflow 评测报告
2. 根据评测报告给出 agent 优化方案
3. 比较两次评测报告差异并产出回归分析

## 必须先读的输入

1. `scorecard.json`
2. `workflow-eval-report.md`

如果存在，再补读：

1. `raw-evidence.json`
2. `evaluation-plan.json`
3. `workflow-result.json`

## 固定工作流

1. 先读 `scorecard.json`
   - 确认 overall status
   - 找出 hard gates
   - 统计高频 findings
2. 再读 `workflow-eval-report.md`
   - 对照 workflow 时间线
   - 看各维度细节与证据引用
3. 如有必要再读 `raw-evidence.json`
   - 深挖具体 payload、task run events、context artifacts
4. 按固定归因分类整理问题
5. 输出面向实现的优化计划

## 输出格式

输出必须严格包含以下六段：

1. 问题摘要
2. 按维度归因
3. 优先级排序的优化清单
4. 每项优化对应的目标文件或模块边界
5. 验证方式
6. 风险与不要动的边界

## 归因分类

每项问题必须归到以下类别之一：

1. `prompt/schema alignment`
2. `catalog alignment`
3. `retrieval/context quality`
4. `tool protocol alignment`
5. `runtime robustness`
6. `test fixture / eval dataset gap`

## 允许建议的落点

只能建议修改这些边界：

1. prompt / schema / few-shot
2. task template / capability catalog
3. retrieval query / chunk strategy
4. tool protocol / guardrail
5. verify rubric
6. runtime evidence / reporting
7. eval scenario pack

## 严格禁止

不要建议：

1. 破坏固定主链
2. 引入新的平行 workflow / task / execution 概念
3. 把评测结果变成业务真相
4. 用空心 service / helper / facade 解决问题
5. 跳过证据直接给泛泛建议

## 回答风格

1. 先 findings，再建议
2. 每条建议都要落到证据
3. 每条建议都要指出验证方式
4. 如报告证据不足，要明确指出缺口，不要脑补

## 参考资料

优先读取：

1. `references/report-schema.md`
2. `references/dimension-catalog.md`
3. `references/optimization-playbook.md`
