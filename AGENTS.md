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
1. `docs/10-class-structure-and-dependency-design.md` (global class/dependency baseline)
2. `docs/schema/agentx_schema_v0.sql` (table truth for owned tables)
3. `docs/09-control-plane-api-contract.md` (API semantics relevant to the module)
4. The module-specific docs listed in section 4 of this file.

Only load additional docs if blocked by ambiguity.

## 3. Global Boundary Rules

1. Source of truth priority:
   - Table truth: `docs/schema/agentx_schema_v0.sql`
   - Semantic truth: `docs/09-control-plane-api-contract.md`
   - Shape truth: `docs/openapi/agentx-control-plane.v0.yaml`
2. Process managers in `process` own cross-module orchestration.
3. `DELIVERED != DONE` is non-negotiable.
4. `RUN_FINISHED` payload is persisted via `task_run_events.data_json`.
5. `workers.status` remains minimal (`PROVISIONING/READY/DISABLED`); busy/unhealthy are derived runtime views.
6. Run creation/resume must bind latest `READY` context snapshot (`task_context_snapshots` + `task_runs.context_snapshot_id`); never dispatch with stale/missing context.

## 4. Module Focus Map (What to Read First)

### session
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.1`
2. `docs/09-control-plane-api-contract.md` session endpoints
3. `docs/schema/agentx_schema_v0.sql` table `sessions`
4. `docs/07-definition-of-done.md` delivery/tag semantics

### requirement
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.2`
2. `docs/03-project-design-module.md` requirement lifecycle
3. `docs/09-control-plane-api-contract.md` requirement endpoints
4. `docs/schema/agentx_schema_v0.sql` tables `requirement_docs`, `requirement_doc_versions`

### ticket
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.3`
2. `docs/03-project-design-module.md` ticket semantics
3. `docs/09-control-plane-api-contract.md` ticket endpoints
4. `docs/schema/agentx_schema_v0.sql` tables `tickets`, `ticket_events`

### workforce
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.4`
2. `docs/04-foreman-worker-module.md`
3. `docs/schema/agentx_schema_v0.sql` tables `workers`, `toolpacks`, `worker_toolpacks`

### planning
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.5`
2. `docs/04-foreman-worker-module.md`
3. `docs/06-git-worktree-workflow.md` task state transitions
4. `docs/schema/agentx_schema_v0.sql` tables `work_modules`, `work_tasks`

### execution
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.6`
2. `docs/05-worker-execution-and-monitoring.md`
3. `docs/07-definition-of-done.md` run evidence rules
4. `docs/schema/agentx_schema_v0.sql` tables `task_context_snapshots`, `task_runs`, `task_run_events`

### workspace
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.7`
2. `docs/06-git-worktree-workflow.md` workspace contract
3. `docs/schema/agentx_schema_v0.sql` table `git_workspaces`

### mergegate
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.8`
2. `docs/06-git-worktree-workflow.md` merge-gate sequence
3. `docs/07-definition-of-done.md` VERIFY gate constraints

### contextpack
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.9`
2. `docs/08-context-management-module.md`
3. `docs/09-control-plane-api-contract.md` context compile endpoints
4. `docs/schema/agentx_schema_v0.sql` table `task_context_snapshots`

### delivery
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.10`
2. `docs/07-definition-of-done.md` delivery tag convention
3. `docs/schema/agentx_schema_v0.sql` session completion note

### process
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.11` and section `4`
2. `docs/03-project-design-module.md` ticket transitions
3. `docs/05-worker-execution-and-monitoring.md` NEED_* semantics
4. `docs/06-git-worktree-workflow.md` DELIVERED->DONE pipeline

### query
Read first:
1. `docs/10-class-structure-and-dependency-design.md` section `3.12`
2. `docs/09-control-plane-api-contract.md` user-visible progress scope
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
