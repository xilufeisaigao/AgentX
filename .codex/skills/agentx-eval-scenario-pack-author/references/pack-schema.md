# Pack Schema

Strict real workflow scenario packs live in:

`src/test/resources/evaluation/scenarios/*.json`

Current schema fields:

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

`scriptedHumanResponses` is an ordered array. Each item should define:

1. `answer`
2. Optional `originNodeId`
3. Optional `ticketType`

The runner consumes these responses in order. Use them to encode repeatable
human clarification or review answers for strict real runs without synthesizing
node outputs.

`expectations` should include:

1. `expectedBehavior`
2. `expectedFacts`
3. `expectedSnippetRefs`
4. `expectedNodeOrder`
5. `repoContextRequired`

`stopPolicy` should include:

1. `maxHumanInteractions`
2. `terminateActiveRunsOnAbort`
