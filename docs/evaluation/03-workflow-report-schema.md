# Workflow Eval Report Schema

本文固定 Eval Center V1 的三件套 artifact 形状，目标是让：

1. 人能稳定复盘
2. 程序能稳定读取
3. 后续 skill 有明确输入边界

## 1. Artifact 三件套

输出目录：

`artifacts/evaluation-reports/<scenarioId>/<runStamp>/`

固定文件：

1. `raw-evidence.json`
2. `scorecard.json`
3. `workflow-eval-report.md`

## 2. `raw-evidence.json`

### 用途

原始证据总包，保留尽量完整的 workflow 运行上下文，主要给调试、深度分析和 skill 深挖用。

### 建议顶层字段

1. `scenario`
2. `workflowRunId`
3. `generatedAt`
4. `workflowSnapshot`
5. `taskRunEventsByRun`
6. `contextArtifacts`
7. `supplementalArtifacts`
8. `artifactRefs`
9. `scorecardSummary`

### 当前实现中的关键来源

1. `WorkflowRuntimeSnapshot`
2. `TaskRunEvent`
3. `WorkflowEvalContextArtifact`
4. `evaluation-plan.json`
5. `workflow-result.json`
6. `review-bundle`

### 设计约束

1. 可以冗余，不需要极致紧凑
2. 可以包含原始 payload
3. 不作为数据库真相
4. schema 允许逐步扩展，但已存在字段尽量向后兼容

## 3. `scorecard.json`

### 用途

稳定摘要，优先服务于：

1. regression 比较
2. 自动化脚本读取
3. 项目专属 skill 快速归因

### 建议顶层结构

```json
{
  "scenarioId": "student-management-happy-path",
  "workflowRunId": "workflow-123",
  "overallStatus": "WARN",
  "generatedAt": "2026-03-30T10:00:00",
  "dimensions": [],
  "hardGates": [],
  "findings": [],
  "artifactRefs": {},
  "comparison": {}
}
```

### `dimensions[]`

每个维度对象至少包含：

1. `dimensionId`
2. `status`
3. `score`
4. `summary`
5. `findings`
6. `metrics`

### `hardGates[]`

只收录足以把维度打成 `FAIL` 的 findings。

### `findings[]`

聚合所有维度 findings，供排序和 skill 消费。

### `artifactRefs`

保存关联路径，例如：

1. `evaluationPlan`
2. `workflowResult`
3. `reviewBundle`
4. `exportedRepo`

### `comparison`

第一版允许为空对象，用于未来 regression delta。

## 4. `workflow-eval-report.md`

### 用途

人读优先的完整复盘报告，同时保持适合 skill 做规则化提取。

### 固定章节

1. 评测基本信息
2. 原始文件索引
3. 报告头
4. 执行摘要
5. Workflow 全流程时间线
6. 维度详细评测
7. 节点专项章节
8. DAG 专项
9. RAG 专项
10. 工具与工件专项
11. 优化候选项

### 评测基本信息建议字段

1. `evaluationTime`
2. `scenarioTitle`
3. `evaluationContent`
4. `evaluationGoal`
5. `reportDirectory`

### 原始文件索引建议字段

1. `workflow-eval-report.md`
2. `raw-evidence.json`
3. `scorecard.json`
4. `workflowResult`
5. `scenarioPack`
6. `reviewBundle`

### 报告头建议字段

1. `scenarioId`
2. `workflowRunId`
3. `generatedAt`
4. repo / commit / model profile

### 执行摘要建议字段

1. `overallStatus`
2. `hardGateCount`
3. dimension score table
4. top findings

### 时间线建议字段

1. nodeId
2. status
3. startedAt / finishedAt
4. fallback / ticket / recovery 摘要

### 优化候选项

第一版不要求 AI 自动生成优化文案。

这里应只列 structured findings，例如：

1. `invalid-task-template-student-impl`
2. `tool-absolute-path-codingImplementation`
3. `missing-expected-fact-email-validation`

## 5. Findings 结构

当前 `EvalFinding` 的稳定字段：

1. `code`
2. `severity`
3. `title`
4. `detail`
5. `evidenceRefs`

这组字段应足够支持：

1. 报告展示
2. skill 归因
3. regression 差异比较

## 6. Evidence Ref 约定

建议使用轻量字符串引用，不在第一版强推复杂 URI 规范。

当前可接受的引用形式：

1. `workflow:<workflowRunId>`
2. `nodeRun:<nodeRunId>`
3. `planningGraph`
4. `planningGraph:task:<taskKey>`
5. `supplemental:evaluationPlan.<nodeKey>`
6. `taskRun:<runId>`

## 7. 向后兼容规则

第一版 schema 演进遵循三条规则：

1. 尽量只新增字段，不随意改名
2. `scorecard.json` 比 `raw-evidence.json` 更强调稳定
3. Markdown 章节顺序保持固定，便于 skill 解析
