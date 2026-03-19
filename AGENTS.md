# AgentX Module Development Playbook

This file defines project-local rules for module agents.
Goal: keep global consistency while preventing every agent from loading the full project context.

## 1. Core Principles

1. Build as a modular monolith with DDD-style packaging.
2. Respect dependency direction:
   - `api -> application -> domain <- infrastructure`
3. Cross-module calls only through `application.port.in` (or domain events consumed by `process`).
4. Never directly read/write another module's mapper or infrastructure class.
5. Do not introduce placeholder abstractions with no immediate use.

## 2. Minimal Context Intake (Do Not Load Everything)

When working on one module, only load:
1. `docs/architecture/03-end-to-end-chain.md` (global runtime chain and module handoff baseline)
2. `docs/reference/truth-sources.md` (source-of-truth priority and common pitfalls)
3. `docs/schema/agentx_schema_v0.sql` (table truth for owned tables)
4. The module-specific docs listed in section 4 of this file.

Only load additional docs if blocked by ambiguity.

## 3. Global Boundary Rules

1. Source of truth priority:
   - Table truth: `docs/schema/agentx_schema_v0.sql`
   - Shape truth: `docs/openapi/agentx-control-plane.v0.yaml`
   - Runtime/flow truth: `docs/architecture/03-end-to-end-chain.md`
   - Current operational truth: `docs/current-state/02-runtime-audit-2026-03-17.md`
2. Process managers in `process` own cross-module orchestration.
3. `DELIVERED != DONE` is non-negotiable.
4. `RUN_FINISHED` payload is persisted via `task_run_events.data_json`.
5. `workers.status` remains minimal (`PROVISIONING/READY/DISABLED`); busy/unhealthy are derived runtime views.
6. Run creation/resume must bind latest `READY` context snapshot (`task_context_snapshots` + `task_runs.context_snapshot_id`); never dispatch with stale/missing context.

## 4. Module Focus Map (What to Read First)

### session
Read first:
1. `docs/modules/09-session.md`
2. `docs/05-code-index.md` session section
3. `docs/schema/agentx_schema_v0.sql` table `sessions`
4. `docs/modules/08-query.md` completion/readiness notes

### requirement
Read first:
1. `docs/modules/10-requirement.md`
2. `docs/architecture/03-end-to-end-chain.md`
3. `docs/05-code-index.md` requirement section
4. `docs/schema/agentx_schema_v0.sql` tables `requirement_docs`, `requirement_doc_versions`

### ticket
Read first:
1. `docs/modules/18-ticket.md`
2. `docs/modules/07-process.md`
3. `docs/05-code-index.md` ticket/process sections
4. `docs/schema/agentx_schema_v0.sql` tables `tickets`, `ticket_events`

### workforce
Read first:
1. `docs/modules/13-workforce.md`
2. `docs/modules/14-execution.md`
3. `docs/schema/agentx_schema_v0.sql` tables `workers`, `toolpacks`, `worker_toolpacks`
4. `docs/current-state/02-runtime-audit-2026-03-17.md` runtime worker facts

### planning
Read first:
1. `docs/modules/11-planning.md`
2. `docs/modules/07-process.md`
3. `docs/architecture/03-end-to-end-chain.md` planning stage
4. `docs/schema/agentx_schema_v0.sql` tables `work_modules`, `work_tasks`

### execution
Read first:
1. `docs/modules/14-execution.md`
2. `docs/modules/15-workspace.md`
3. `docs/modules/16-mergegate.md`
4. `docs/schema/agentx_schema_v0.sql` tables `task_context_snapshots`, `task_runs`, `task_run_events`

### workspace
Read first:
1. `docs/modules/15-workspace.md`
2. `docs/architecture/04-runtime-artifacts.md`
3. `docs/schema/agentx_schema_v0.sql` table `git_workspaces`

### mergegate
Read first:
1. `docs/modules/16-mergegate.md`
2. `docs/architecture/03-end-to-end-chain.md`
3. `docs/architecture/04-runtime-artifacts.md`

### contextpack
Read first:
1. `docs/modules/12-contextpack.md`
2. `docs/modules/07-process.md`
3. `docs/05-code-index.md` context section
4. `docs/schema/agentx_schema_v0.sql` table `task_context_snapshots`

### delivery
Read first:
1. `docs/modules/17-delivery.md`
2. `docs/architecture/04-runtime-artifacts.md`
3. `docs/current-state/02-runtime-audit-2026-03-17.md`

### process
Read first:
1. `docs/modules/07-process.md`
2. `docs/architecture/03-end-to-end-chain.md`
3. `docs/current-state/02-runtime-audit-2026-03-17.md`
4. `docs/05-code-index.md`

### query
Read first:
1. `docs/modules/08-query.md`
2. `docs/reference/truth-sources.md`
3. Related table schemas for requested views

## 5. Human Intervention Triggers (Decision Surface)

After process start, trigger human intervention only via ticket flow.

Use `DECISION` when the system has enough facts but requires a tradeoff/choice:
1. Conflicting options with different cost/risk/timeline.
2. Scope expansion such as widening write scope beyond current policy.
3. Architecture-impacting change (module boundaries, data semantics, merge strategy).
4. Conflict resolution path that changes behavior or accepted risk.
5. Capacity/governance decisions (e.g., worker pool ceiling reached and policy change required).

Use `CLARIFICATION` when facts are missing and no valid action can be selected:
1. Missing acceptance criteria or expected outputs.
2. Missing reproduction inputs for BUGFIX tasks.
3. Undefined verification commands for VERIFY gate.
4. Missing dependency/env facts (credentials, endpoints, versions).
5. Ambiguous requirement references or contradictory source docs requiring user clarification.

Hard rule:
1. Worker never asks user directly.
2. Worker emits `NEED_DECISION` or `NEED_CLARIFICATION`.
3. Control plane/process converts this into `tickets` + `ticket_events`.

## 6. Module PR Checklist

Before merging a module change:
1. Updated only owned module + allowed `port.in` contracts.
2. No forbidden dependency to another module's infrastructure.
3. State transitions match docs and schema comments.
4. Event writing behavior preserved (`ticket_events` / `task_run_events`).
5. Added/updated tests for changed transition or invariants.
