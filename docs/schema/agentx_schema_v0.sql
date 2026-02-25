/*
AgentX Control Plane - Minimal Schema (v0)

Last updated: 2026-02-20

Goal:
  - This file is the SINGLE source of truth for "what tables/columns exist" in AgentX v0.
  - No placeholder columns. Every column must be used by at least one workflow in docs/01-08.
  - Every column has a lifecycle note (who sets it / when it changes / immutability).
  - Every status field documents its state machine and trigger scenarios (to prevent drift).

SQL dialect:
  - Intentionally "common SQL" (varchar/text/timestamp).
  - ENUM/CHECK constraints are NOT used in v0 to keep portability.
  - Status/value constraints are enforced at the control plane level (see docs modules).

Docs mapping:
  - Project design / tickets / requirement docs: docs/03-project-design-module.md
  - Foreman-worker / toolpacks / tasks: docs/04-foreman-worker-module.md
  - Runs / events / templates: docs/05-worker-execution-and-monitoring.md
  - Git worktree / claiming / merge gate: docs/06-git-worktree-workflow.md
  - DoD + delivery + evidence dir: docs/07-definition-of-done.md
  - Context packs + task_skill compilation: docs/08-context-management-module.md
*/

/* =====================================================================================
 * 01. Sessions + Requirement Docs (Value Layer)
 * ===================================================================================== */

create table sessions (
  session_id  varchar(64) primary key,
  -- lifecycle: set once when a new project session is created by the control plane; immutable.
  -- usage: the root correlation id for all tickets/tasks/runs.

  title       varchar(256) not null,
  -- lifecycle: set at session creation (by user or requirement_agent); may be updated by user rename.
  -- usage: human-readable label only; must not affect workflow behavior.

  status      varchar(32) not null,
  -- lifecycle: set ACTIVE on create; transitions driven by user/system.
  -- values: ACTIVE | PAUSED | COMPLETED

  created_at  timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at  timestamp not null
  -- lifecycle: updated on any UPDATE of this row.
);

-- sessions.status state machine (minimal):
--   ACTIVE -> PAUSED:
--     - trigger: user explicitly pauses the session OR system pauses due to admin/maintenance policy.
--   PAUSED -> ACTIVE:
--     - trigger: user resumes the session.
--   ACTIVE -> COMPLETED:
--     - trigger: user accepts delivery and the session is closed (after at least one delivery tag on main).
--     - proof source: control plane verifies at least one annotated git tag matching `delivery/<YYYYMMDD-HHmm>` on main.
--       (v0 does not add a dedicated DB column/table for delivery tags; Git is the authoritative evidence source.)
-- NOTE: COMPLETED is terminal in v0 (no reopen; create a new session if needed).


create table requirement_docs (
  doc_id            varchar(64) primary key,
  -- lifecycle: set once when requirement document is created; immutable.

  session_id        varchar(64) not null,
  -- lifecycle: set once at create; immutable.
  -- usage: ownership boundary for the doc.

  current_version   int not null,
  -- lifecycle:
  --   - starts at 0 when the doc metadata is created (no versions yet).
  --   - becomes 1 when the first requirement_doc_versions row (version=1) is created.
  --   - increments by +1 for each subsequent requirement_doc_versions row.
  -- invariant: equals MAX(requirement_doc_versions.version) for this doc_id (or 0 if none).
  -- writer: control plane (requirement_agent or user edits via the API).

  confirmed_version int null,
  -- lifecycle: null until first user confirmation; set to current_version when user confirms.
  -- changes: only when user explicitly confirms a new version (including incremental value changes).

  status            varchar(32) not null,
  -- lifecycle: DRAFT -> IN_REVIEW -> CONFIRMED, and may return to IN_REVIEW when value-layer changes occur.
  -- values: DRAFT | IN_REVIEW | CONFIRMED

  title             varchar(256) not null,
  -- lifecycle: set at create; may be updated when the doc's headline changes.

  created_at        timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at        timestamp not null
  -- lifecycle: updated whenever current_version/confirmed_version/status/title changes.
);

-- requirement_docs.status state machine (value-layer only):
--   DRAFT -> IN_REVIEW:
--     - trigger: requirement_agent asks user to review/confirm (user-facing loop starts).
--   IN_REVIEW -> CONFIRMED:
--     - trigger: user explicitly confirms "this version matches my needs".
--     - side-effect: confirmed_version := current_version.
--   CONFIRMED -> IN_REVIEW:
--     - trigger: value-layer incremental changes requested/accepted; a new version is created.
-- NOTE: requirement_agent is ONLY allowed to edit value-layer fields (see docs/03).


create table requirement_doc_versions (
  doc_id          varchar(64) not null,
  -- lifecycle: set once; immutable.

  version         int not null,
  -- lifecycle: set once; immutable; must be monotonic increasing per doc_id.

  content         text not null,
  -- lifecycle: set once; immutable.
  -- usage: markdown text for this version (v0 intentionally stores only markdown).
  -- format rule (v0): must follow REQ-DOC-v1/REQ-DOC-v1-zh template
  -- (`schema_version: req_doc_v1` or `schema_version: req_doc_v1_zh` + mandatory sections),
  -- enforced by control plane requirement module.

  created_by_role varchar(32) not null,
  -- lifecycle: set once; immutable.
  -- values: user | requirement_agent

  created_at      timestamp not null,
  -- lifecycle: set once at insert; immutable.

  primary key (doc_id, version),
  foreign key (doc_id) references requirement_docs(doc_id)
);

/* =====================================================================================
 * 02. Tickets (Work Orders) + Ticket Events (Audit Chain)
 * ===================================================================================== */

create table tickets (
  ticket_id           varchar(64) primary key,
  -- lifecycle: set once at ticket creation; immutable.

  session_id          varchar(64) not null,
  -- lifecycle: set once at create; immutable.

  type                varchar(32) not null,
  -- lifecycle: set once at create; immutable.
  -- values: HANDOFF | ARCH_REVIEW | DECISION | CLARIFICATION
  -- trigger scenarios:
  --   HANDOFF: requirement_agent hands off architecture-layer topics to architect_agent.
  --   ARCH_REVIEW: requirement confirmed_version changes; architect must assess and update architecture artifacts.
  --   DECISION: system needs user to choose/confirm among options.
  --   CLARIFICATION: system lacks factual input; user must provide missing info.

  status              varchar(32) not null,
  -- lifecycle: OPEN -> IN_PROGRESS -> (WAITING_USER) -> DONE, with BLOCKED as exception terminal-ish.
  -- values: OPEN | IN_PROGRESS | WAITING_USER | DONE | BLOCKED

  title               varchar(256) not null,
  -- lifecycle: set at create; may be updated for clarity by assignee agent; changes must be recorded in ticket_events.

  created_by_role     varchar(32) not null,
  -- lifecycle: set once at create; immutable.
  -- values: user | requirement_agent | architect_agent

  assignee_role       varchar(32) not null,
  -- lifecycle: set at create; may change ONLY via explicit reassignment (must emit ticket_events).
  -- values: requirement_agent | architect_agent

  requirement_doc_id  varchar(64) null,
  -- lifecycle: set at create if the ticket is anchored to a requirement doc; immutable.

  requirement_doc_ver int null,
  -- lifecycle: set at create if anchored to a specific requirement_doc_versions; immutable.
  -- NOTE: if either requirement_doc_id or requirement_doc_ver is set, both must be set by control plane.

  payload_json        text not null,
  -- lifecycle: set at create; may be updated while IN_PROGRESS (e.g., add decision options).
  -- constraint: every material change MUST be appended to ticket_events (payload_json is not the audit chain).

  claimed_by          varchar(128) null,
  -- lifecycle: set when an agent instance claims the ticket (lease acquired); cleared when released/expired/done.
  -- usage: prevents two agents from working on the same ticket concurrently.

  lease_until         timestamp null,
  -- lifecycle: set together with claimed_by; extended by the claimer while processing; cleared when DONE.
  -- trigger: lease expiration allows ticket to be reclaimed.

  created_at          timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at          timestamp not null,
  -- lifecycle: updated on any state/claim/payload changes.

  foreign key (session_id) references sessions(session_id)
);

-- tickets.status state machine (minimal, non-bloating):
--   OPEN -> IN_PROGRESS:
--     - trigger: assignee agent claims the ticket (claimed_by/lease_until set).
--   IN_PROGRESS -> WAITING_USER:
--     - trigger: agent emits a user-facing request (DECISION/CLARIFICATION needed) and waits for user input.
--   WAITING_USER -> IN_PROGRESS:
--     - trigger: user responds; assignee resumes work (may renew lease).
--   IN_PROGRESS -> DONE:
--     - trigger: ticket goal achieved; outputs are linked (ticket_events + artifact refs).
--   * -> BLOCKED:
--     - trigger: cannot progress automatically (long-term missing input, irreconcilable conflict).
-- NOTE: "details" belong in ticket_events, NOT as new statuses.


create table ticket_events (
  event_id    varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  ticket_id   varchar(64) not null,
  -- lifecycle: set once; immutable.

  event_type  varchar(64) not null,
  -- lifecycle: set once; immutable.
  -- values (v0 examples): STATUS_CHANGED | COMMENT | DECISION_REQUESTED | USER_RESPONDED | ARTIFACT_LINKED

  actor_role  varchar(32) not null,
  -- lifecycle: set once; immutable.
  -- values: user | requirement_agent | architect_agent

  body        text not null,
  -- lifecycle: set once; immutable.
  -- usage: human-readable summary for replay/audit.

  data_json   text null,
  -- lifecycle: set once; immutable.
  -- usage: optional structured payload (must be valid JSON if present).

  created_at  timestamp not null,
  -- lifecycle: set once at insert; immutable.
  -- query: replay should sort by (created_at, event_id) to avoid same-second instability.

  foreign key (ticket_id) references tickets(ticket_id)
);

/* =====================================================================================
 * 03. Toolpacks + Workers (Execution Capability Boundary)
 * ===================================================================================== */

create table toolpacks (
  toolpack_id  varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  name         varchar(128) not null,
  -- lifecycle: set once; immutable.
  -- examples: "java", "maven", "python", "gcc"

  version      varchar(64) not null,
  -- lifecycle: set once; immutable.
  -- examples: "21", "3.9.6"

  kind         varchar(64) not null,
  -- lifecycle: set once; immutable.
  -- values: language | build | compiler | script | misc

  description  varchar(512) null,
  -- lifecycle: set at create; may be updated for clarity (should not change meaning).

  created_at   timestamp not null
  -- lifecycle: set once at insert; immutable.
);


create table workers (
  worker_id   varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  status      varchar(32) not null,
  -- lifecycle: PROVISIONING -> READY -> DISABLED (minimal set).
  -- values: PROVISIONING | READY | DISABLED
  -- NOTE: "unhealthy" is derived from run heartbeats/leases in v0, not stored as an extra status.

  created_at  timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at  timestamp not null
  -- lifecycle: updated when status changes.
);

-- workers.status state machine (minimal):
--   PROVISIONING -> READY:
--     - trigger: worker environment/toolpacks are prepared and self-check passes.
--   READY -> DISABLED:
--     - trigger: explicit deprovision/retire OR repeated failures/lease timeouts lead foreman to disable it.
-- NOTE: v0 does not support "READY -> PROVISIONING" (rebuild = create a new worker).


create table worker_toolpacks (
  worker_id   varchar(64) not null,
  -- lifecycle: set once per binding; immutable.

  toolpack_id varchar(64) not null,
  -- lifecycle: set once per binding; immutable.

  primary key (worker_id, toolpack_id),
  foreign key (worker_id) references workers(worker_id),
  foreign key (toolpack_id) references toolpacks(toolpack_id)
);

/* =====================================================================================
 * 04. Modules + Tasks (Work Breakdown)
 * ===================================================================================== */

create table work_modules (
  module_id    varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  session_id   varchar(64) not null,
  -- lifecycle: set once at create; immutable.

  name         varchar(128) not null,
  -- lifecycle: set at create; may be updated for clarity.

  description  varchar(512) null,
  -- lifecycle: optional; may be updated.

  created_at   timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at   timestamp not null,
  -- lifecycle: updated on any UPDATE of this row.

  foreign key (session_id) references sessions(session_id)
);


create table work_tasks (
  task_id                varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  module_id              varchar(64) not null,
  -- lifecycle: set once at create; immutable.

  title                  varchar(256) not null,
  -- lifecycle: set at create; may be updated for clarity (must emit task_run_events/ticket_events when material).

  task_template_id       varchar(64) not null,
  -- lifecycle: set at create; immutable.
  -- values (v0): tmpl.init.v0 | tmpl.impl.v0 | tmpl.verify.v0 | tmpl.bugfix.v0 | tmpl.refactor.v0 | tmpl.test.v0
  -- usage: selects the delivery/stop/evidence contract used when generating Task Package (docs/05).

  status                 varchar(32) not null,
  -- lifecycle: see state machine below.
  -- values: PLANNED | WAITING_DEPENDENCY | WAITING_WORKER | READY_FOR_ASSIGN | ASSIGNED | DELIVERED | DONE

  required_toolpacks_json text not null,
  -- lifecycle: set at create by foreman; may be updated if task scope changes.
  -- usage: JSON array of toolpack_id; MUST be the minimal set required to execute.
  -- trigger: updating this field requires re-evaluating dispatch status
  --          (WAITING_DEPENDENCY | WAITING_WORKER | READY_FOR_ASSIGN).

  active_run_id           varchar(64) null,
  -- lifecycle:
  --   - set when status transitions READY_FOR_ASSIGN -> ASSIGNED (atomic claim+run creation).
  --   - cleared when leaving ASSIGNED (DELIVERED or READY_FOR_ASSIGN).
  -- usage: fast pointer to current active attempt; do NOT use it as the source of truth for history (use task_runs).

  created_by_role         varchar(32) not null,
  -- lifecycle: set once at create; immutable.
  -- values: architect_agent (as foreman)

  created_at              timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at              timestamp not null,
  -- lifecycle: updated on any UPDATE of this row.

  foreign key (module_id) references work_modules(module_id)
);

-- work_tasks.status state machine (v0, prevents status explosion):
--   PLANNED -> WAITING_DEPENDENCY:
--     - trigger: system evaluates dependency graph and finds unmet upstream prerequisite(s).
--   PLANNED -> WAITING_WORKER:
--     - trigger: dependencies satisfied, but no READY worker can cover required_toolpacks_json.
--   PLANNED -> READY_FOR_ASSIGN:
--     - trigger: dependencies satisfied and an eligible READY worker exists.
--   WAITING_DEPENDENCY -> WAITING_WORKER:
--     - trigger: upstream prerequisite(s) become satisfied, but no eligible READY worker exists.
--   WAITING_DEPENDENCY -> READY_FOR_ASSIGN:
--     - trigger: upstream prerequisite(s) become satisfied and eligible READY worker exists.
--   WAITING_WORKER -> READY_FOR_ASSIGN:
--     - trigger: dependency prerequisites satisfied and
--                (compatible worker becomes READY OR required_toolpacks_json is reduced).
--   READY_FOR_ASSIGN -> ASSIGNED:
--     - trigger: atomic allocation succeeds (select task + claim + create task_run + allocate worktree).
--     - side-effect: active_run_id is set.
--   ASSIGNED -> READY_FOR_ASSIGN:
--     - trigger: active run fails/cancelled OR lease expires and the run is reclaimed.
--     - side-effect: active_run_id cleared.
--   ASSIGNED -> DELIVERED:
--     - trigger: an IMPL-kind run SUCCEEDED and produced a delivery candidate; foreman updates task/<task_id> branch.
--     - side-effect: active_run_id cleared.
--   DELIVERED -> DONE:
--     - trigger: merge gate succeeded (rebase -> VERIFY merge candidate -> fast-forward main).
-- NOTE:
--   - "Waiting for user" is a RUN status (WAITING_FOREMAN), NOT a TASK status (docs/05).

create table work_task_dependencies (
  task_id                  varchar(64) not null,
  -- lifecycle: set once at insert; immutable.
  -- usage: downstream task that is blocked by upstream prerequisite(s).

  depends_on_task_id       varchar(64) not null,
  -- lifecycle: set once at insert; immutable.
  -- usage: upstream prerequisite task.

  required_upstream_status varchar(32) not null,
  -- lifecycle: set once at insert; immutable.
  -- values (v0): DONE
  -- usage: downstream task can be dispatched only when upstream reaches this status.

  created_at               timestamp not null,
  -- lifecycle: set once at insert; immutable.

  primary key (task_id, depends_on_task_id),
  foreign key (task_id) references work_tasks(task_id),
  foreign key (depends_on_task_id) references work_tasks(task_id)
);

-- work_task_dependencies constraints (v0):
--   1. no self dependency (task_id != depends_on_task_id).
--   2. both tasks must belong to same session boundary.
--   3. dependency graph must remain acyclic (DAG).

/* =====================================================================================
 * 05. Task Context Snapshots (Compilation Progress + Freshness + Retention)
 * ===================================================================================== */

create table task_context_snapshots (
  snapshot_id         varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  task_id             varchar(64) not null,
  -- lifecycle: set once at create; immutable.
  -- usage: links a context snapshot to one task boundary.

  run_kind            varchar(32) not null,
  -- lifecycle: set once; immutable.
  -- values: IMPL | VERIFY
  -- usage: context requirements differ by run kind (e.g., VERIFY must be read-only).

  status              varchar(32) not null,
  -- lifecycle: see state machine below.
  -- values: PENDING | COMPILING | READY | FAILED | STALE

  trigger_type        varchar(64) not null,
  -- lifecycle: set once when snapshot compilation is requested; immutable.
  -- values (v0 examples):
  --   REQUIREMENT_CONFIRMED | TICKET_DONE | RUN_FINISHED | MERGE_DONE | INIT_DONE | MANUAL_REFRESH

  source_fingerprint  varchar(128) not null,
  -- lifecycle: set on compile request; immutable.
  -- usage: hash/fingerprint over source refs used to detect stale/changed facts.

  task_context_ref    varchar(256) null,
  -- lifecycle: nullable while PENDING/COMPILING/FAILED; set when status enters READY.
  -- usage: artifact ref to compiled task_context pack (typically under .agentx/context/).

  task_skill_ref      varchar(256) null,
  -- lifecycle: nullable while PENDING/COMPILING/FAILED; set when status enters READY.
  -- usage: artifact ref to compiled task_skill.

  error_code          varchar(64) null,
  -- lifecycle: nullable; set when status=FAILED; can be cleared on retry/new snapshot.

  error_message       text null,
  -- lifecycle: nullable; set when status=FAILED for troubleshooting.

  compiled_at         timestamp null,
  -- lifecycle: set when status enters READY; null otherwise.

  retained_until      timestamp not null,
  -- lifecycle: set at create by retention policy.
  -- policy (v0): while session ACTIVE/PAUSED, snapshots must remain online; after COMPLETED, full artifact bodies
  -- can be archived after retained_until, but metadata rows must remain queryable for audit/replay.

  created_at          timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at          timestamp not null,
  -- lifecycle: updated on status transitions.

  foreign key (task_id) references work_tasks(task_id)
);

-- task_context_snapshots.status state machine (v0):
--   (create) -> PENDING:
--     - trigger: context refresh trigger received (requirement/ticket/run/merge/init/manual).
--   PENDING -> COMPILING:
--     - trigger: context processor starts compilation.
--   COMPILING -> READY:
--     - trigger: task_context + task_skill compiled successfully with valid source refs.
--   COMPILING -> FAILED:
--     - trigger: compile error, missing facts, or toolchain failure.
--   READY -> STALE:
--     - trigger: source facts changed (new requirement confirmation, ticket response, run outcome, merge result).
--   FAILED/STALE -> COMPILING:
--     - trigger: retry or refresh requested.

/* =====================================================================================
 * 06. Task Runs + Run Events (Lease/Heartbeat + Audit)
 * ===================================================================================== */

create table task_runs (
  run_id            varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  task_id           varchar(64) not null,
  -- lifecycle: set once at create; immutable.

  worker_id         varchar(64) not null,
  -- lifecycle: set once at create; immutable.
  -- rule: rescheduling = create a NEW run with a new run_id.

  status            varchar(32) not null,
  -- lifecycle: see state machine below.
  -- values: RUNNING | WAITING_FOREMAN | SUCCEEDED | FAILED | CANCELLED

  run_kind          varchar(32) not null,
  -- lifecycle: set once at create; immutable.
  -- values: IMPL | VERIFY
  -- usage: hard gate for permissions (VERIFY must be read-only).

  context_snapshot_id varchar(64) not null,
  -- lifecycle: set at create; immutable.
  -- usage: binds this run to one READY context snapshot (audit anchor for "what facts/skill this run used").
  -- guard: run creation MUST fail if referenced snapshot is not READY or already STALE.

  lease_until       timestamp not null,
  -- lifecycle: set at create; extended on heartbeats; expires to allow reclaim/retry.

  last_heartbeat_at timestamp not null,
  -- lifecycle: set at create; updated on every heartbeat event.

  started_at        timestamp not null,
  -- lifecycle: set once at create; immutable.

  finished_at       timestamp null,
  -- lifecycle: null while RUNNING/WAITING_FOREMAN; set once when run reaches a terminal status.

  task_skill_ref    varchar(256) null,
  -- lifecycle: set at create; immutable for the lifetime of this run.
  -- usage: convenience copy of compiled task_skill ref (source of truth is task_context_snapshots.task_skill_ref).

  toolpacks_snapshot_json text not null,
  -- lifecycle: set at create; immutable.
  -- usage: JSON snapshot of toolpacks bound to the worker at run start (audit).

  base_commit       varchar(64) not null,
  -- lifecycle: set at create; immutable.
  -- usage: the ONLY source-of-truth code snapshot for this run (facts must not drift).

  branch_name       varchar(128) not null,
  -- lifecycle: set at create; immutable.
  -- usage: run/<run_id> (or equivalent) branch checked out in the worktree.

  worktree_path     varchar(256) not null,
  -- lifecycle: set at create; immutable.
  -- usage: filesystem path where the worker is allowed to operate for this run.

  created_at        timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at        timestamp not null,
  -- lifecycle: updated on lease/heartbeat/status transitions.
  -- NOTE: v0 intentionally does NOT add dedicated columns for work_report/delivery_commit.
  --       Those are persisted in task_run_events (RUN_FINISHED.data_json) and artifact refs via ARTIFACT_LINKED.

  foreign key (task_id) references work_tasks(task_id),
  foreign key (context_snapshot_id) references task_context_snapshots(snapshot_id),
  foreign key (worker_id) references workers(worker_id)
);

-- task_runs.status state machine (v0):
--   (create) -> RUNNING:
--     - trigger: control plane successfully allocated (run + worktree) and dispatched Task Package.
--   RUNNING -> WAITING_FOREMAN:
--     - trigger: worker emits NEED_CLARIFICATION / NEED_DECISION (docs/05), then stops.
--   WAITING_FOREMAN -> RUNNING:
--     - trigger: foreman resumes the same run only when context fingerprint has not changed.
--     - if context facts changed after triage/user response, foreman must create a NEW run with a NEW context_snapshot_id.
--   RUNNING -> SUCCEEDED:
--     - trigger: worker finishes and submits Work Report; no unresolved NEED_*; evidence requirements met.
--   RUNNING/WAITING_FOREMAN -> FAILED:
--     - trigger: command failure; unexpected crash; lease expiry reclaim; or explicit foreman failure decision.
--   RUNNING/WAITING_FOREMAN -> CANCELLED:
--     - trigger: foreman cancels due to risk/incorrect direction; must be explicit.
-- NOTE:
--   - "Retry" always creates a new run_id (history is immutable).


create table task_run_events (
  event_id    varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.

  run_id      varchar(64) not null,
  -- lifecycle: set once; immutable.

  event_type  varchar(64) not null,
  -- lifecycle: set once; immutable.
  -- values (v0 examples):
  --   RUN_STARTED | HEARTBEAT | PROGRESS | NEED_CLARIFICATION | NEED_DECISION | ARTIFACT_LINKED | RUN_FINISHED
  -- RUN_FINISHED payload convention:
  --   data_json includes result_status/work_report/delivery_commit/artifact_refs_json (when present).

  body        text not null,
  -- lifecycle: set once; immutable.
  -- usage: human-readable summary.

  data_json   text null,
  -- lifecycle: set once; immutable.
  -- usage: optional structured payload (must be valid JSON if present).

  created_at  timestamp not null,
  -- lifecycle: set once at insert; immutable.

  foreign key (run_id) references task_runs(run_id)
);

/* =====================================================================================
 * 07. Git Worktree Workspace Tracking (Optional but Auditable)
 * ===================================================================================== */

create table git_workspaces (
  run_id      varchar(64) primary key,
  -- lifecycle: set once at insert; immutable.
  -- rule: one workspace per run in v0 (workspace identity = run_id).

  status      varchar(32) not null,
  -- lifecycle: ALLOCATED -> (RELEASED | BROKEN)
  -- values: ALLOCATED | RELEASED | BROKEN

  created_at  timestamp not null,
  -- lifecycle: set once at insert; immutable.

  updated_at  timestamp not null,
  -- lifecycle: updated when status changes.

  foreign key (run_id) references task_runs(run_id)
);

-- git_workspaces.status triggers:
--   (create) -> ALLOCATED:
--     - trigger: git branch created + worktree directory created + workspace cleanliness check passed.
--   ALLOCATED -> RELEASED:
--     - trigger: run ends and cleanup succeeds (worktree removed; no residue).
--   ALLOCATED -> BROKEN:
--     - trigger: workspace is dirty/unusable OR cleanup fails OR path conflicts/disk issues occur.
-- NOTE: BROKEN requires explicit repair/cleanup workflow; do NOT auto-reuse.
