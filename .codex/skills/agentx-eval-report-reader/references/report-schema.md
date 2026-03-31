# Report Schema Reference

读取报告时优先按以下顺序理解：

1. `scorecard.json`
   - 看 `overallStatus`
   - 看 `dimensions[]`
   - 看 `hardGates[]`
   - 看 `findings[]`
   - 看 `artifactRefs`
2. `workflow-eval-report.md`
   - 看执行摘要
   - 看 workflow 时间线
   - 看维度详细评测
   - 看 DAG / RAG / 工具与工件专项
3. `raw-evidence.json`
   - 只在需要深挖具体 payload 时读取

重点字段解释：

1. `hardGates`
   - 优先级最高，先解释为什么失败
2. `dimensions[].metrics`
   - 用于解释严重程度和趋势
3. `findings[].evidenceRefs`
   - 用于把建议落回真实证据
4. `artifactRefs`
   - 用于跳转到 `evaluation-plan.json`、`workflow-result.json`、`review-bundle`

解读时不要把 Markdown 报告里的人类叙述覆盖 `scorecard.json` 的结构化结论。
