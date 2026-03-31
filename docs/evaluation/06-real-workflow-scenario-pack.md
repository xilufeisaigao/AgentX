# 严格真实 Workflow Scenario Pack

## 1. 目标

这一层是给“真实 LLM 全流程评测”用的，不再是旧的 smoke 预评估 + fallback 选择器。

严格模式要求：

1. workflow 内实际调用 runtime 主链上的真实 agent
2. 不再用人工构造的结构化输出替代节点推进
3. 如果流程卡在未预期位置，就停止推进
4. 停止后也必须基于当前 snapshot / task run events / context artifacts 产出报告

## 2. 当前实现位置

### scenario pack 文件

当前真实场景包目录：

`src/test/resources/evaluation/scenarios/*.json`

当前已落地的场景：

1. `student-management-real-strict.json`

### 运行入口

当前严格真实评测入口：

`src/test/java/com/agentx/platform/DeepSeekStrictWorkflowEvalIT.java`

### runner 支撑

当前通用 runner 支撑：

1. `src/test/java/com/agentx/platform/support/eval/RealWorkflowEvalScenarioPack.java`
2. `src/test/java/com/agentx/platform/support/eval/RealWorkflowEvalScenarioPackLoader.java`
3. `src/test/java/com/agentx/platform/support/eval/RealWorkflowEvalFixtures.java`
4. `src/test/java/com/agentx/platform/support/eval/RealWorkflowEvalRunner.java`

## 3. scenario pack 负责什么

一个严格真实场景包至少定义：

1. `scenarioId`
2. `workflowTitle`
3. `requirementTitle`
4. `initialPrompt`
5. `clarificationAnswer`
6. `autoConfirmRequirementDoc`
7. `repoFixtureId`
8. `agentModelOverrides`
9. `workflowScenario`
10. `expectations`
11. `stopPolicy`

## 4. 为什么需要 `agentModelOverrides`

当前 runtime 主链的真实 agent 取的是 catalog 里的 `AgentDefinition.model`。

严格真实 DeepSeek 评测时，需要把参与本次评测的 agent 显式改成当前可用的真实模型，例如：

1. `architect-agent -> deepseek-chat`
2. `coding-agent-java -> deepseek-chat`
3. `verify-agent-java -> deepseek-chat`

这个 override 只在测试 runner 内生效，用于让真实评测场景和当前 provider 对齐，不改变主链语义。

## 5. runner 如何收集信息

严格真实 runner 当前通过这些途径收集评测证据：

1. `WorkflowRuntimeSnapshot`
   - 收集 workflow / requirement / tickets / tasks / task runs / workspaces / node runs
2. `TaskRunEvent`
   - 收集 coding turn、tool call、deterministic verify、runtime failure 等执行轨迹
3. `WorkflowEvalTraceCollector`
   - 在 `DefaultContextCompilationCenter` 中收集每次编译出的 `CompiledContextPack`
4. `workflow-result.json`
   - 收集 runner stop reason、step history、当前 snapshot 摘要、导出工件路径
5. `scenario-pack.json`
   - 把当前实际运行的场景包原样落盘，便于复盘
6. `review-bundle`
   - 从 workspace head commit 导出代码工件，供后续人工复审

## 6. runner 如何终止“卡死”

严格模式里，以下情况都会停止推进：

1. `runUntilStable(...)` 超时
2. workflow 停在未预期稳定状态
3. workflow 进入未脚本化的 `WAITING_ON_HUMAN`
4. 需要超过场景包允许次数的人类交互

停止后会执行：

1. best-effort 终止 active agent containers
2. 调一次 `RuntimeSupervisorSweep.sweepOnce()`
3. 重新抓取最新 `WorkflowRuntimeSnapshot`
4. 生成 `workflow-result.json`
5. 调 `WorkflowEvalCenter.generateWorkflowReport(...)`

## 7. 运行方式

严格真实评测命令示例：

```powershell
$env:AGENTX_LLM_SMOKE='true'
$env:AGENTX_DEEPSEEK_API_KEY='***'
./mvnw.cmd -q -DfailIfNoTests=false -Dit.test=DeepSeekStrictWorkflowEvalIT -Dagentx.eval.scenario=student-management-real-strict verify
```

如果场景包在文件系统里，也可以用绝对路径覆盖：

```powershell
./mvnw.cmd -q -DfailIfNoTests=false -Dit.test=DeepSeekStrictWorkflowEvalIT -Dagentx.eval.scenario=D:\path\to\my-scenario.json verify
```

## 8. 产物目录

当前严格真实评测 run 建议统一输出到项目内目录：

`artifacts/evaluation-runs/<runId>/`

建议固定结构：

1. `README.md`
   - 放评测时间、评测内容、停止原因和关键文件索引
2. `artifacts/scenario-pack.json`
3. `artifacts/workflow-result.json`
4. `reports/<scenarioId>/<runStamp>/raw-evidence.json`
5. `reports/<scenarioId>/<runStamp>/scorecard.json`
6. `reports/<scenarioId>/<runStamp>/workflow-eval-report.md`

## 9. 当前边界

这套 scenario pack 目前只解决“严格真实 workflow 评测如何统一扩展”的问题，不解决：

1. judge-based 自动裁判
2. regression gate 自动放行
3. UI 展示
4. 多 fixture 类型的通用 repo 生成器

这些后续能力应建立在当前 pack + runner + report 三件套之上，而不是重新做一套 smoke。
