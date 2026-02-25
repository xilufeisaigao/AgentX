create database if not exists agentx_backend;

use agentx_backend;

create table if not exists sessions (
  session_id  varchar(64) primary key,
  title       varchar(256) not null,
  status      varchar(32) not null,
  created_at  timestamp not null,
  updated_at  timestamp not null
);

create table if not exists requirement_docs (
  doc_id            varchar(64) primary key,
  session_id        varchar(64) not null,
  current_version   int not null,
  confirmed_version int null,
  status            varchar(32) not null,
  title             varchar(256) not null,
  created_at        timestamp not null,
  updated_at        timestamp not null,
  foreign key (session_id) references sessions(session_id)
);

create table if not exists requirement_doc_versions (
  doc_id          varchar(64) not null,
  version         int not null,
  content         text not null,
  created_by_role varchar(32) not null,
  created_at      timestamp not null,
  primary key (doc_id, version),
  foreign key (doc_id) references requirement_docs(doc_id)
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
