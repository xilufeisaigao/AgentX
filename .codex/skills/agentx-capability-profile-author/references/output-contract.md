# Output Contract

Every invocation of `agentx-capability-profile-author` must end with five concrete outputs.

## 1. Profile manifest

- Path: `src/main/resources/stack-profiles/<profileId>.json`
- Must include:
  - `identity`
  - `planner`
  - `nodeAgents`
  - `taskTemplates`
  - `prompts`
  - `capabilityRuntime`
  - `eval`
  - `reporting`

## 2. SQL companion

- Path: `db/seeds/profiles/<profileId>.sql`
- Must make the profile dispatchable in the database catalog.
- At minimum cover:
  - capability packs
  - runtime pack bindings
  - tool grants
  - agent definitions
  - agent-capability bindings

## 3. Scenario pack skeleton

- Path: `src/test/resources/evaluation/scenarios/...`
- Must include:
  - `scenarioId`
  - `profileId`
  - `repoFixtureId`
  - `workflowScenario`
  - `expectations`
  - strict `stopPolicy`

## 4. Fixture checklist

- Path target: `src/test/resources/repo-fixtures/<fixtureId>/`
- Must specify:
  - directory shape
  - required root scripts
  - baseline files or anchors
  - what must pass before the agent starts

## 5. Optimization notes

- Must explain:
  - likely failure modes
  - allowed revision surfaces
  - what the next eval report should prove
