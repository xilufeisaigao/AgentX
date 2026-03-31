# Revision Playbook

Use this playbook when a profile already exists and an eval report says it is weak.

## First pass

1. Read `scorecard.json`
2. Read `workflow-eval-report.md`
3. Load `raw-evidence.json` only when the first two files do not explain the failure

## Allowed revision targets

1. prompt supplement bullets inside the profile manifest
2. task template write scopes and verify expectations
3. capability runtime command catalog and cleanup paths
4. eval role globs and required artifact roles
5. SQL companion agent/capability/runtime/tool bindings
6. fixture scripts and baseline files

## Disallowed revision targets

1. fixed workflow node graph
2. L1-L5 state truth
3. synthetic fallback advancement in strict runs
4. database bypasses that only exist in memory

## Heuristics

### If DAG quality fails

- tighten `planner.allowedTaskTemplateIds`
- tighten template write scopes
- strengthen architect prompt bullets

### If RAG quality fails

- improve fixture anchors and file naming
- refine prompt bullets so architect/coding/verify ask for the right repo context
- do not solve this by weakening the report

### If tool protocol fails

- adjust coding prompt bullets
- adjust allowed command catalog
- keep write scopes narrow

### If delivery artifact fails

- check role globs first
- check verify commands second
- check fixture scripts third

### If runtime robustness fails

- prefer clearer evidence and command behavior over adding new orchestration layers
