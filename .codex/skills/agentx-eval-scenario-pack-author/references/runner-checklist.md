# Runner Checklist

When extending strict real workflow evals, keep this checklist:

1. The scenario must run through `DeepSeekStrictWorkflowEvalIT`
2. The runner must use real runtime agents, not `@MockBean`
3. The runner may answer ordered `scriptedHumanResponses` and confirm requirement review
4. The runner must abort on unexpected human wait or timeout
5. The runner must call `RuntimeSupervisorSweep.sweepOnce()` after terminating active runs
6. The runner must still emit:
   - `scenario-pack.json`
   - `workflow-result.json`
   - `raw-evidence.json`
   - `scorecard.json`
   - `workflow-eval-report.md`

Only change runner code when a scenario cannot be represented by the current JSON schema.
