# `agentx-eval-report-reader` Skill 设计

## 1. 目标

定义一个项目专属 skill，专门读取 Eval Center V1 产出的报告，并输出一份面向实现的优化计划。

这个 skill 的职责不是替用户拍脑袋改架构，而是：

1. 读取报告
2. 识别主要问题来源
3. 将问题归因到现有边界
4. 输出可执行的优化清单

## 2. 输入

必选输入：

1. `scorecard.json`
2. `workflow-eval-report.md`

可选输入：

1. `raw-evidence.json`
2. `evaluation-plan.json`
3. `workflow-result.json`

## 3. 输出固定结构

skill 输出必须稳定包含以下章节：

1. 问题摘要
2. 按维度归因
3. 优先级排序的优化清单
4. 每项优化对应的目标文件或模块边界
5. 验证方式
6. 风险与不要动的边界

## 4. 允许的优化落点

优化建议只允许落到现有边界：

1. prompt / schema / few-shot
2. task template / capability catalog
3. retrieval query / chunk strategy
4. tool protocol / guardrail
5. verify rubric
6. runtime evidence / reporting
7. eval dataset / scenario pack

## 5. 不允许的建议

skill 不允许建议：

1. 破坏固定主链
2. 发明平行 workflow 概念
3. 把 evaluator 直接塞进业务真相
4. 用“多造一层 service / helper / facade”来掩盖问题
5. 跳过证据直接下结论

## 6. 标准归因分类

skill 必须把问题归到以下几类之一：

1. `prompt/schema alignment`
2. `catalog alignment`
3. `retrieval/context quality`
4. `tool protocol alignment`
5. `runtime robustness`
6. `test fixture / eval dataset gap`

## 7. 典型输入到输出的映射

### 例 1

输入 finding：

`invalid-task-template-student-impl`

推荐归因：

`catalog alignment`

推荐优化方向：

1. 对齐 architect 提示词中的 task template 白名单
2. 为 architect few-shot 加入合法 catalog 示例
3. 在 materializer 前加更明确的 catalog fail-fast 提示

### 例 2

输入 finding：

`tool-absolute-path-codingImplementation`

推荐归因：

`tool protocol alignment`

推荐优化方向：

1. 强化 coding tool prompt 中的相对路径约束
2. 在 normalizer 或 validator 中补更明确的错误文案
3. 在 eval scenario 中保留此类回归样例

### 例 3

输入 finding：

`missing-expected-fact-email-validation`

推荐归因：

`retrieval/context quality`

推荐优化方向：

1. 调整 fact extraction
2. 调整 retrieval query 生成
3. 在 verify context 中补 changed-files 周边事实

## 8. 与 Eval Center 的关系

Eval Center 负责产生证据和评分。

`agentx-eval-report-reader` 负责在不破坏架构边界的前提下，把这些证据转换成优化计划。

两者之间的分工必须保持稳定：

1. Eval Center 不直接输出代码修改建议
2. skill 不篡改 scorecard
3. skill 输出可作为后续人工实施或自动优化工作流的输入
