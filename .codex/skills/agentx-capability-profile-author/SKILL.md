---
name: agentx-capability-profile-author
description: Create or revise AgentX stack capability profiles, SQL companions, scenario pack skeletons, and fixture checklists. Use when adding a new technical-stack capability, migrating an existing stack into the profile system, or updating a profile after evaluation reports expose gaps in runtime, catalog, prompt, or eval wiring.
---

# AgentX Capability Profile Author

Use this skill for exactly two modes:

1. `create-profile`
2. `revise-profile`

## Minimal loading path

Read these files first:

1. `docs/architecture/01-three-layer-architecture.md`
2. `docs/architecture/02-fixed-coding-workflow.md`
3. `src/main/resources/stack-profiles/*.json`
4. `db/seeds/profiles/*.sql`
5. `.codex/skills/agentx-eval-scenario-pack-author/SKILL.md`
6. `.codex/skills/agentx-eval-report-reader/SKILL.md`

Then load only the needed reference files:

1. `references/output-contract.md`
2. `references/revision-playbook.md`

## Fixed workflow

1. Confirm the request is about stack capability packaging, not kernel redesign.
2. Inspect the nearest existing profile manifest and SQL companion before drafting anything new.
3. Produce or update the fixed five-piece output.
4. Keep profile/runtime/eval/catalog boundaries aligned.
5. If revising from reports, tie every change to explicit report evidence.

## Output contract

Always produce these five artifacts or sections:

1. profile manifest draft
2. SQL companion draft
3. scenario pack skeleton
4. fixture checklist
5. optimization notes

Use the exact repo boundaries below:

1. `src/main/resources/stack-profiles/<profileId>.json`
2. `db/seeds/profiles/<profileId>.sql`
3. `src/test/resources/evaluation/scenarios/...`
4. `src/test/resources/repo-fixtures/...`
5. `.codex/skills/...` only when the request is about skill scaffolding or revision support

## Hard guardrails

Never do these things:

1. invent a new workflow node, state machine, or parallel execution concept
2. bypass database dispatchability with manifest-only capability packs or agents
3. keep an old hardcoded fallback path alongside the new profile path
4. recommend synthetic structured fallback for strict real workflow evaluation
5. put scenario-specific hacks into common runtime code just to pass one eval

## Create-profile mode

1. Choose one concrete workspace shape and package manager.
2. Declare explicit task templates, capability packs, runtime commands, and eval role globs.
3. Pair the manifest with a SQL companion in the same turn.
4. Emit one strict scenario pack skeleton and one fixture checklist that matches the profile.

## Revise-profile mode

1. Start from `scorecard.json` and `workflow-eval-report.md`.
2. Classify the problem into one of:
   - `prompt/schema alignment`
   - `catalog alignment`
   - `retrieval/context quality`
   - `tool protocol alignment`
   - `runtime robustness`
   - `test fixture / eval dataset gap`
3. Only revise:
   - profile manifest
   - SQL companion
   - fixture scripts or files
   - scenario pack skeleton
   - shared skill instructions
4. State how the next eval run should verify the change.

## Response style

1. Prefer concrete drafts over abstract advice.
2. Name the exact files the user should expect to appear or change.
3. Keep the reasoning tied to AgentX’s fixed kernel and dispatch model.
