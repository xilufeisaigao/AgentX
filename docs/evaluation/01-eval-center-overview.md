# Eval Center V1 总览

## 1. 目标

Eval Center V1 的目标不是先做一个自动打总分的 AI 裁判，而是先把 AgentX workflow 主链上的真实执行证据统一收集、按固定维度整理、再输出一份可读且可追溯的评测报告。

第一版强调三件事：

1. 文件优先
   - 先产出 `raw-evidence.json`、`scorecard.json`、`workflow-eval-report.md`
   - 不先引入新的数据库真相表
2. 混合模式
   - runtime 主链内只做证据采集和归一化
   - 正式评分、汇总和报告生成走离线评测入口
3. 先可复盘，再谈自动优化
   - 报告首先服务于人读和复盘
   - 后续再由项目专属 skill 读取报告并生成优化计划

## 2. 模块边界

Eval Center 挂在 `runtime.evaluation`，而不是新造一层系统。

当前代码入口：

1. `WorkflowEvalTraceCollector`
   - 负责在 runtime 主链中收集上下文编译证据
2. `WorkflowEvalCenter`
   - 负责把证据组装成评测输入、运行多维度评分、输出 artifacts
3. `WorkflowEvalProperties`
   - 控制 artifact 目录和 trace 开关

Eval Center 负责：

1. 收集 workflow 运行后可复盘的结构化证据
2. 按固定 9 个维度输出评分与 findings
3. 产出人读报告和程序读取摘要

Eval Center 不负责：

1. 直接修改业务真相或状态机
2. 在 runtime 主链里做自动重评分判定
3. 自动改 prompt、改模型、改检索参数
4. 发明新的 workflow / task / run 平行概念

## 3. 与现有主链的关系

Eval Center 只消费现有 runtime/domain 证据：

1. `WorkflowRuntimeSnapshot`
2. `TaskRunEvent`
3. `CompiledContextPack`
4. `evaluation-plan.json`
5. `workflow-result.json`
6. `review-bundle`
7. merge / verify / workspace 导出工件

当前接入方式：

1. `DefaultContextCompilationCenter` 在编译完 `CompiledContextPack` 后，把事实和 retrieval snippets 记到 `WorkflowEvalTraceCollector`
2. `DeepSeekRealWorkflowRuntimeIT` 在 smoke workflow 收尾时，调用 `WorkflowEvalCenter.generateWorkflowReport(...)`
3. `AgentKernelEvalBaselineTests` 复用同一套 artifact writer，为离线 agent kernel baseline 输出 `scorecard.json`

这意味着评测中心是“事后消费执行证据”，而不是 workflow 主链上的新判官。

## 4. 评测对象模型

Eval Center V1 使用 4 个稳定对象：

1. `EvalScenario`
   - 定义要评什么场景，包括预期行为、expected facts、expected snippet refs、预期主链节点顺序等
2. `EvalEvidenceBundle`
   - 单次运行的证据包，包括 snapshot、task run events、context artifacts、supplemental artifacts、artifact refs
3. `EvalScorecard`
   - 机器优先读取的稳定评分对象
4. `WorkflowEvalReport`
   - 当前以 Markdown 形式输出给人和 skill 阅读

## 5. Artifact 目录

默认输出目录：

`artifacts/evaluation-reports/<scenarioId>/<runStamp>/`

真实 workflow 评测运行目录建议固定到：

`artifacts/evaluation-runs/<runId>/`

其中：

1. `README.md`
   - 当前 run 的快速入口，集中列出评测时间、评测内容和关键文件路径
2. `artifacts/`
   - runner 生成的 `workflow-result.json`、`scenario-pack.json`
3. `reports/`
   - 评测中心三件套输出目录

第一版固定产出：

1. `raw-evidence.json`
   - 原始证据总包，适合深挖和后续 skill 使用
2. `scorecard.json`
   - 稳定摘要，适合程序和回归比较
3. `workflow-eval-report.md`
   - 人读优先的完整报告

Spring 配置入口：

```yaml
agentx:
  platform:
    evaluation:
      artifact-root: ${AGENTX_EVAL_ARTIFACT_ROOT:${user.dir}/artifacts/evaluation-reports}
      trace-collection-enabled: ${AGENTX_EVAL_TRACE_COLLECTION_ENABLED:true}
```

## 6. 第一版运行模式

当前推荐三种运行方式：

1. agent eval baseline
   - 节点契约级、纯离线、可不依赖真实 LLM
2. workflow smoke eval
   - 真实或半真实 workflow 跑完后生成全流程报告
3. regression pack
   - 同一场景多 trial，比较 `scorecard.json` 之间的 delta

## 7. 当前已固定的 9 个维度

1. `NODE_CONTRACT`
2. `WORKFLOW_TRAJECTORY`
3. `DAG_QUALITY`
4. `RAG_QUALITY`
5. `TOOL_PROTOCOL`
6. `DELIVERY_ARTIFACT`
7. `RUNTIME_ROBUSTNESS`
8. `HUMAN_IN_LOOP`
9. `EFFICIENCY_REGRESSION`

详细定义见：

1. [02-dimension-catalog.md](02-dimension-catalog.md)
2. [03-workflow-report-schema.md](03-workflow-report-schema.md)

## 8. 当前结论

Eval Center V1 的定位是“把主链执行证据收口成可评分、可读、可复盘的统一报告”，而不是直接替代人工判断。

它为后续三条演进路径提供基础：

1. 基于 report 的人工复盘
2. 基于 `scorecard.json` 的 regression 比较
3. 基于项目专属 skill 的自动优化建议
