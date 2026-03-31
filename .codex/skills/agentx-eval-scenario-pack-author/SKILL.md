---
name: agentx-eval-scenario-pack-author
description: Create or extend AgentX strict real-workflow eval scenario packs and their supporting fixture/runner wiring. Use when the task is to add a new end-to-end eval scenario under src/test/resources/evaluation/scenarios, update the strict real runner, or define how a new real workflow case should be seeded, stopped, and reported.
---

# AgentX Eval Scenario Pack Author

Use this skill when adding or updating a strict real workflow eval scenario for AgentX.

## Core workflow

1. Read `references/pack-schema.md`
2. Read `references/runner-checklist.md`
3. If the repo fixture changes, read `references/fixture-guidelines.md`
4. Add or update one scenario pack JSON under `src/test/resources/evaluation/scenarios/`
5. Only extend runner code if the new scenario cannot fit the existing pack schema
6. Keep the run strictly real
   - do not reintroduce manual structured fallback
   - if the real workflow stops unexpectedly, let the runner abort and produce a partial report

## Required outputs

Every new scenario pack must define:

1. `scenarioId`
2. `workflowTitle`
3. `requirementTitle`
4. `initialPrompt`
5. `scriptedHumanResponses`
6. `autoConfirmRequirementDoc`
7. `repoFixtureId`
8. `agentModelOverrides`
9. `workflowScenario`
10. `expectations`
11. `stopPolicy`

## Guardrails

1. Do not invent a parallel workflow concept outside the fixed main chain
2. Do not make the runner depend on hand-authored node outputs
3. Do not hide abort reasons; encode them in `workflow-result.json`
4. Prefer extending `RealWorkflowEvalScenarioPack` over creating one-off test classes
5. Reuse `RealWorkflowEvalFixtures` when possible instead of duplicating repo seeding logic

## Validation

After editing a pack:

1. Run `mvnw.cmd -q -DskipTests test-compile`
2. If the environment has real-model credentials, run the strict IT for the pack
3. Confirm the report artifacts exist even when the workflow aborts early
