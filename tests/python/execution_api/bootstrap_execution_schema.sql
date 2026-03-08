create database if not exists agentx_backend;

use agentx_backend;

create table if not exists sessions (
  session_id  varchar(64) primary key,
  title       varchar(256) not null,
  status      varchar(32) not null,
  created_at  timestamp not null,
  updated_at  timestamp not null
);

create table if not exists toolpacks (
  toolpack_id  varchar(64) primary key,
  name         varchar(128) not null,
  version      varchar(64) not null,
  kind         varchar(64) not null,
  description  varchar(512) null,
  created_at   timestamp not null
);

create table if not exists workers (
  worker_id   varchar(64) primary key,
  status      varchar(32) not null,
  created_at  timestamp not null,
  updated_at  timestamp not null
);

create table if not exists worker_toolpacks (
  worker_id   varchar(64) not null,
  toolpack_id varchar(64) not null,
  primary key (worker_id, toolpack_id),
  foreign key (worker_id) references workers(worker_id),
  foreign key (toolpack_id) references toolpacks(toolpack_id)
);

create table if not exists work_modules (
  module_id    varchar(64) primary key,
  session_id   varchar(64) not null,
  name         varchar(128) not null,
  description  varchar(512) null,
  created_at   timestamp not null,
  updated_at   timestamp not null,
  foreign key (session_id) references sessions(session_id)
);

create table if not exists work_tasks (
  task_id                 varchar(64) primary key,
  module_id               varchar(64) not null,
  title                   varchar(256) not null,
  task_template_id        varchar(64) not null,
  status                  varchar(32) not null,
  required_toolpacks_json text not null,
  active_run_id           varchar(64) null,
  created_by_role         varchar(32) not null,
  created_at              timestamp not null,
  updated_at              timestamp not null,
  foreign key (module_id) references work_modules(module_id)
);

create table if not exists work_task_dependencies (
  task_id                  varchar(64) not null,
  depends_on_task_id       varchar(64) not null,
  required_upstream_status varchar(32) not null,
  created_at               timestamp not null,
  primary key (task_id, depends_on_task_id),
  foreign key (task_id) references work_tasks(task_id),
  foreign key (depends_on_task_id) references work_tasks(task_id)
);

create table if not exists tickets (
  ticket_id           varchar(64) primary key,
  session_id          varchar(64) not null,
  type                varchar(32) not null,
  status              varchar(32) not null,
  title               varchar(256) not null,
  created_by_role     varchar(32) not null,
  assignee_role       varchar(32) not null,
  requirement_doc_id  varchar(64) null,
  requirement_doc_ver int null,
  payload_json        text not null,
  claimed_by          varchar(128) null,
  lease_until         timestamp null,
  created_at          timestamp not null,
  updated_at          timestamp not null,
  foreign key (session_id) references sessions(session_id)
);

create table if not exists ticket_events (
  event_id    varchar(64) primary key,
  ticket_id   varchar(64) not null,
  event_type  varchar(64) not null,
  actor_role  varchar(32) not null,
  body        text not null,
  data_json   text null,
  created_at  timestamp not null,
  foreign key (ticket_id) references tickets(ticket_id)
);

create table if not exists task_context_snapshots (
  snapshot_id         varchar(64) primary key,
  task_id             varchar(64) not null,
  run_kind            varchar(32) not null,
  status              varchar(32) not null,
  trigger_type        varchar(64) not null,
  source_fingerprint  varchar(128) not null,
  task_context_ref    varchar(256) null,
  task_skill_ref      varchar(256) null,
  error_code          varchar(64) null,
  error_message       text null,
  compiled_at         timestamp null,
  retained_until      timestamp not null,
  created_at          timestamp not null,
  updated_at          timestamp not null,
  foreign key (task_id) references work_tasks(task_id)
);

create table if not exists task_runs (
  run_id                  varchar(64) primary key,
  task_id                 varchar(64) not null,
  worker_id               varchar(64) not null,
  status                  varchar(32) not null,
  run_kind                varchar(32) not null,
  context_snapshot_id     varchar(64) not null,
  lease_until             timestamp not null,
  last_heartbeat_at       timestamp not null,
  started_at              timestamp not null,
  finished_at             timestamp null,
  task_skill_ref          varchar(256) null,
  toolpacks_snapshot_json text not null,
  base_commit             varchar(64) not null,
  branch_name             varchar(128) not null,
  worktree_path           varchar(256) not null,
  created_at              timestamp not null,
  updated_at              timestamp not null,
  foreign key (task_id) references work_tasks(task_id),
  foreign key (context_snapshot_id) references task_context_snapshots(snapshot_id),
  foreign key (worker_id) references workers(worker_id)
);

create table if not exists task_run_events (
  event_id    varchar(64) primary key,
  run_id      varchar(64) not null,
  event_type  varchar(64) not null,
  body        text not null,
  data_json   text null,
  created_at  timestamp not null,
  foreign key (run_id) references task_runs(run_id)
);

create table if not exists git_workspaces (
  run_id      varchar(64) primary key,
  status      varchar(32) not null,
  created_at  timestamp not null,
  updated_at  timestamp not null,
  foreign key (run_id) references task_runs(run_id)
);
