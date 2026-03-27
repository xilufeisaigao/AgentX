create database if not exists agentx_platform
  character set utf8mb4
  collate utf8mb4_0900_ai_ci;

use agentx_platform;

set foreign_key_checks = 0;

drop table if exists git_workspaces;
drop table if exists task_run_events;
drop table if exists task_runs;
drop table if exists agent_pool_instances;
drop table if exists task_context_snapshots;
drop table if exists work_task_dependencies;
drop table if exists work_task_capability_requirements;
drop table if exists work_tasks;
drop table if exists work_modules;
drop table if exists ticket_events;
drop table if exists tickets;
drop table if exists requirement_doc_versions;
drop table if exists requirement_docs;
drop table if exists workflow_node_run_events;
drop table if exists workflow_node_runs;
drop table if exists workflow_run_events;
drop table if exists workflow_run_node_bindings;
drop table if exists workflow_runs;
drop table if exists workflow_template_nodes;
drop table if exists workflow_templates;
drop table if exists agent_definition_capability_packs;
drop table if exists agent_definitions;
drop table if exists capability_pack_skills;
drop table if exists capability_pack_tools;
drop table if exists capability_pack_runtime_packs;
drop table if exists capability_packs;
drop table if exists skill_tool_bindings;
drop table if exists skill_definitions;
drop table if exists tool_definitions;
drop table if exists runtime_packs;

set foreign_key_checks = 1;

create table runtime_packs (
  runtime_pack_id varchar(64) primary key,
  display_name varchar(128) not null,
  pack_type varchar(32) not null,
  version varchar(64) not null,
  locator varchar(256) null,
  description varchar(512) null,
  enabled boolean not null default true,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3)
) engine=InnoDB comment='Execution environment packs such as JDK, Maven, Python, curl and Git.';

create table tool_definitions (
  tool_id varchar(64) primary key,
  display_name varchar(128) not null,
  tool_kind varchar(32) not null,
  adapter_key varchar(128) not null,
  description varchar(512) null,
  config_schema_json json null,
  enabled boolean not null default true,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3)
) engine=InnoDB comment='Invocable tools exposed to agents through the platform runtime.';

create table skill_definitions (
  skill_id varchar(64) primary key,
  display_name varchar(128) not null,
  skill_kind varchar(32) not null,
  purpose varchar(512) not null,
  instruction_text text null,
  input_schema_json json null,
  output_schema_json json null,
  enabled boolean not null default true,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3)
) engine=InnoDB comment='Skill assets that encode how an agent should reason or execute within a domain.';

create table skill_tool_bindings (
  skill_id varchar(64) not null,
  tool_id varchar(64) not null,
  required_flag boolean not null default true,
  invocation_mode varchar(32) not null,
  sort_order int not null default 100,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (skill_id, tool_id),
  foreign key (skill_id) references skill_definitions(skill_id),
  foreign key (tool_id) references tool_definitions(tool_id)
) engine=InnoDB comment='Declares which tools a skill expects or knows how to invoke.';

create table capability_packs (
  capability_pack_id varchar(64) primary key,
  display_name varchar(128) not null,
  capability_kind varchar(64) not null,
  granularity varchar(32) not null,
  purpose varchar(512) not null,
  description text null,
  enabled boolean not null default true,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3)
) engine=InnoDB comment='Higher-level capability bundles. Agents usually bind packs, not raw skills or tools.';

create table capability_pack_runtime_packs (
  capability_pack_id varchar(64) not null,
  runtime_pack_id varchar(64) not null,
  required_flag boolean not null default true,
  purpose varchar(256) null,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (capability_pack_id, runtime_pack_id),
  foreign key (capability_pack_id) references capability_packs(capability_pack_id),
  foreign key (runtime_pack_id) references runtime_packs(runtime_pack_id)
) engine=InnoDB comment='Execution environment requirements implied by a capability pack.';

create table capability_pack_tools (
  capability_pack_id varchar(64) not null,
  tool_id varchar(64) not null,
  required_flag boolean not null default true,
  exposure_mode varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (capability_pack_id, tool_id),
  foreign key (capability_pack_id) references capability_packs(capability_pack_id),
  foreign key (tool_id) references tool_definitions(tool_id)
) engine=InnoDB comment='Tool exposure granted by a capability pack.';

create table capability_pack_skills (
  capability_pack_id varchar(64) not null,
  skill_id varchar(64) not null,
  required_flag boolean not null default true,
  role_in_pack varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (capability_pack_id, skill_id),
  foreign key (capability_pack_id) references capability_packs(capability_pack_id),
  foreign key (skill_id) references skill_definitions(skill_id)
) engine=InnoDB comment='Skill composition of a capability pack.';

create table agent_definitions (
  agent_id varchar(64) primary key,
  display_name varchar(128) not null,
  purpose varchar(512) not null,
  registration_source varchar(32) not null,
  system_prompt_text text null,
  runtime_type varchar(32) not null,
  model varchar(128) not null,
  max_parallel_runs int not null,
  architect_suggested boolean not null default false,
  auto_pool_eligible boolean not null default false,
  manual_registration_allowed boolean not null default true,
  enabled boolean not null default true,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3)
) engine=InnoDB comment='Agent catalog definitions. This is the primary platform asset users manage.';

create table agent_definition_capability_packs (
  agent_id varchar(64) not null,
  capability_pack_id varchar(64) not null,
  required_flag boolean not null default true,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (agent_id, capability_pack_id),
  foreign key (agent_id) references agent_definitions(agent_id),
  foreign key (capability_pack_id) references capability_packs(capability_pack_id)
) engine=InnoDB comment='Capability packs bound to an agent definition.';

create table workflow_templates (
  workflow_template_id varchar(64) primary key,
  display_name varchar(128) not null,
  description text not null,
  mutability varchar(64) not null,
  registration_policy varchar(32) not null,
  is_system_builtin boolean not null default true,
  enabled boolean not null default true,
  version varchar(64) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3)
) engine=InnoDB comment='Installed workflow templates. v1 keeps them system-owned rather than user-authored.';

create table workflow_template_nodes (
  workflow_template_id varchar(64) not null,
  node_id varchar(64) not null,
  display_name varchar(128) not null,
  node_kind varchar(32) not null,
  sequence_no int not null,
  default_agent_id varchar(64) null,
  agent_binding_configurable boolean not null default false,
  parameter_schema_json json null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  primary key (workflow_template_id, node_id),
  foreign key (workflow_template_id) references workflow_templates(workflow_template_id),
  foreign key (default_agent_id) references agent_definitions(agent_id)
) engine=InnoDB comment='Node catalog for installed workflow templates.';

create table workflow_runs (
  workflow_run_id varchar(64) primary key,
  workflow_template_id varchar(64) not null,
  title varchar(256) not null,
  status varchar(32) not null,
  entry_mode varchar(32) not null,
  auto_agent_mode boolean not null default false,
  created_by_actor_type varchar(32) not null,
  created_by_actor_id varchar(64) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  foreign key (workflow_template_id) references workflow_templates(workflow_template_id)
) engine=InnoDB comment='Top-level run instance for a fixed workflow template.';

create table workflow_run_node_bindings (
  binding_id varchar(64) primary key,
  workflow_run_id varchar(64) not null,
  node_id varchar(64) not null,
  binding_mode varchar(32) not null,
  selected_agent_id varchar(64) not null,
  rationale text null,
  locked_by_user boolean not null default false,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_workflow_run_node_binding (workflow_run_id, node_id),
  foreign key (workflow_run_id) references workflow_runs(workflow_run_id),
  foreign key (selected_agent_id) references agent_definitions(agent_id)
) engine=InnoDB comment='Resolved agent binding for each configurable workflow node in a run.';

create table workflow_run_events (
  event_id varchar(64) primary key,
  workflow_run_id varchar(64) not null,
  event_type varchar(64) not null,
  actor_type varchar(32) not null,
  actor_id varchar(64) not null,
  body text not null,
  data_json json null,
  created_at datetime(3) not null default current_timestamp(3),
  foreign key (workflow_run_id) references workflow_runs(workflow_run_id)
) engine=InnoDB comment='Top-level timeline events for a workflow run.';

create table requirement_docs (
  doc_id varchar(64) primary key,
  workflow_run_id varchar(64) not null,
  current_version int not null,
  confirmed_version int null,
  status varchar(32) not null,
  title varchar(256) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  foreign key (workflow_run_id) references workflow_runs(workflow_run_id)
) engine=InnoDB comment='Requirement docs belonging to a workflow run.';

create table requirement_doc_versions (
  doc_id varchar(64) not null,
  version int not null,
  content longtext not null,
  created_by_actor_type varchar(32) not null,
  created_by_actor_id varchar(64) not null,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (doc_id, version),
  foreign key (doc_id) references requirement_docs(doc_id)
) engine=InnoDB comment='Immutable requirement document versions.';

create table tickets (
  ticket_id varchar(64) primary key,
  workflow_run_id varchar(64) not null,
  type varchar(32) not null,
  blocking_scope varchar(32) not null default 'GLOBAL_BLOCKING',
  status varchar(32) not null,
  title varchar(256) not null,
  created_by_actor_type varchar(32) not null,
  created_by_actor_id varchar(64) not null,
  assignee_actor_type varchar(32) not null,
  assignee_actor_id varchar(64) not null,
  origin_node_id varchar(64) null,
  requirement_doc_id varchar(64) null,
  requirement_doc_ver int null,
  payload_json json not null,
  claimed_by_actor_id varchar(64) null,
  claimed_by_instance_id varchar(64) null,
  lease_until datetime(3) null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_tickets_workflow_status (workflow_run_id, status),
  foreign key (workflow_run_id) references workflow_runs(workflow_run_id),
  foreign key (requirement_doc_id) references requirement_docs(doc_id),
  foreign key (requirement_doc_id, requirement_doc_ver) references requirement_doc_versions(doc_id, version)
) engine=InnoDB comment='HITL and cross-node ticket inbox. blocking_scope distinguishes workflow-blocking, task-blocking and informational tickets.';

create table ticket_events (
  event_id varchar(64) primary key,
  ticket_id varchar(64) not null,
  event_type varchar(64) not null,
  actor_type varchar(32) not null,
  actor_id varchar(64) not null,
  body text not null,
  data_json json null,
  created_at datetime(3) not null default current_timestamp(3),
  foreign key (ticket_id) references tickets(ticket_id)
) engine=InnoDB comment='Immutable audit events for tickets.';

create table work_modules (
  module_id varchar(64) primary key,
  workflow_run_id varchar(64) not null,
  name varchar(128) not null,
  description varchar(512) null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  foreign key (workflow_run_id) references workflow_runs(workflow_run_id)
) engine=InnoDB comment='Task grouping within the coding workflow.';

create table work_tasks (
  task_id varchar(64) primary key,
  module_id varchar(64) not null,
  title varchar(256) not null,
  objective text not null,
  task_template_id varchar(64) not null,
  status varchar(32) not null,
  write_scope_json json not null,
  delivery_contract_json json null,
  origin_ticket_id varchar(64) null,
  created_by_actor_type varchar(32) not null,
  created_by_actor_id varchar(64) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_work_tasks_module_status (module_id, status),
  foreign key (module_id) references work_modules(module_id),
  foreign key (origin_ticket_id) references tickets(ticket_id)
) engine=InnoDB comment='Executable tasks created from architect planning.';

create table work_task_capability_requirements (
  task_id varchar(64) not null,
  capability_pack_id varchar(64) not null,
  required_flag boolean not null default true,
  role_in_task varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (task_id, capability_pack_id),
  foreign key (task_id) references work_tasks(task_id),
  foreign key (capability_pack_id) references capability_packs(capability_pack_id)
) engine=InnoDB comment='Normalized capability requirements used for agent-task matching.';

create table work_task_dependencies (
  task_id varchar(64) not null,
  depends_on_task_id varchar(64) not null,
  required_upstream_status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  primary key (task_id, depends_on_task_id),
  foreign key (task_id) references work_tasks(task_id),
  foreign key (depends_on_task_id) references work_tasks(task_id)
) engine=InnoDB comment='DAG edges between tasks.';

create table task_context_snapshots (
  snapshot_id varchar(64) primary key,
  task_id varchar(64) not null,
  run_kind varchar(32) not null,
  status varchar(32) not null,
  trigger_type varchar(64) not null,
  source_fingerprint varchar(128) not null,
  task_context_ref varchar(256) null,
  task_skill_ref varchar(256) null,
  error_code varchar(64) null,
  error_message text null,
  compiled_at datetime(3) null,
  retained_until datetime(3) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_task_context_snapshot_task_kind_status (task_id, run_kind, status),
  foreign key (task_id) references work_tasks(task_id)
) engine=InnoDB comment='Compiled task context and skill snapshots.';

create table agent_pool_instances (
  agent_instance_id varchar(64) primary key,
  agent_id varchar(64) not null,
  runtime_type varchar(32) not null,
  status varchar(32) not null,
  launch_mode varchar(32) not null,
  current_workflow_run_id varchar(64) null,
  lease_until datetime(3) null,
  last_heartbeat_at datetime(3) null,
  endpoint_ref varchar(256) null,
  runtime_metadata_json json null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_agent_pool_status (agent_id, status),
  foreign key (agent_id) references agent_definitions(agent_id),
  foreign key (current_workflow_run_id) references workflow_runs(workflow_run_id)
) engine=InnoDB comment='Runtime instances pooled from an agent definition.';

create table workflow_node_runs (
  node_run_id varchar(64) primary key,
  workflow_run_id varchar(64) not null,
  node_id varchar(64) not null,
  selected_agent_id varchar(64) null,
  agent_instance_id varchar(64) null,
  status varchar(32) not null,
  input_payload_json json null,
  output_payload_json json null,
  started_at datetime(3) not null,
  finished_at datetime(3) null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_workflow_node_runs_workflow_node_status (workflow_run_id, node_id, status),
  foreign key (workflow_run_id) references workflow_runs(workflow_run_id),
  foreign key (selected_agent_id) references agent_definitions(agent_id),
  foreign key (agent_instance_id) references agent_pool_instances(agent_instance_id)
) engine=InnoDB comment='Execution records for top-level workflow nodes such as requirement, architect and verify.';

create table workflow_node_run_events (
  event_id varchar(64) primary key,
  node_run_id varchar(64) not null,
  event_type varchar(64) not null,
  body text not null,
  data_json json null,
  created_at datetime(3) not null default current_timestamp(3),
  foreign key (node_run_id) references workflow_node_runs(node_run_id)
) engine=InnoDB comment='Event stream for top-level workflow node executions.';

create table task_runs (
  run_id varchar(64) primary key,
  task_id varchar(64) not null,
  agent_instance_id varchar(64) not null,
  status varchar(32) not null,
  run_kind varchar(32) not null,
  context_snapshot_id varchar(64) not null,
  lease_until datetime(3) not null,
  last_heartbeat_at datetime(3) not null,
  started_at datetime(3) not null,
  finished_at datetime(3) null,
  execution_contract_json json not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  key idx_task_runs_task_status (task_id, status),
  foreign key (task_id) references work_tasks(task_id),
  foreign key (agent_instance_id) references agent_pool_instances(agent_instance_id),
  foreign key (context_snapshot_id) references task_context_snapshots(snapshot_id)
) engine=InnoDB comment='Immutable run attempts executed by pooled agent instances.';

create table task_run_events (
  event_id varchar(64) primary key,
  run_id varchar(64) not null,
  event_type varchar(64) not null,
  body text not null,
  data_json json null,
  created_at datetime(3) not null default current_timestamp(3),
  foreign key (run_id) references task_runs(run_id)
) engine=InnoDB comment='Run-level audit log.';

create table git_workspaces (
  workspace_id varchar(64) primary key,
  run_id varchar(64) not null,
  task_id varchar(64) not null,
  status varchar(32) not null,
  repo_root varchar(512) not null,
  worktree_path varchar(512) not null,
  branch_name varchar(128) not null,
  base_commit varchar(64) not null,
  head_commit varchar(64) null,
  merge_commit varchar(64) null,
  cleanup_status varchar(32) not null,
  created_at datetime(3) not null default current_timestamp(3),
  updated_at datetime(3) not null default current_timestamp(3) on update current_timestamp(3),
  unique key uk_git_workspaces_run (run_id),
  foreign key (run_id) references task_runs(run_id),
  foreign key (task_id) references work_tasks(task_id)
) engine=InnoDB comment='Git worktree artifacts used by task runs and merge handling.';

insert into runtime_packs (
  runtime_pack_id, display_name, pack_type, version, locator, description, enabled
) values
  ('rt-java-21', 'JDK 21', 'language', '21', null, 'Java 21 runtime and compiler.', true),
  ('rt-maven-3', 'Maven 3', 'build', '3.x', null, 'Maven build lifecycle.', true),
  ('rt-python-3_11', 'Python 3.11', 'language', '3.11', null, 'Python runtime for API checks and scripts.', true),
  ('rt-curl', 'curl', 'cli', '8.x', null, 'HTTP client for smoke checks and black-box API tests.', true),
  ('rt-git', 'Git CLI', 'cli', '2.x', null, 'Git for branching, diff and merge operations.', true);

insert into tool_definitions (
  tool_id, display_name, tool_kind, adapter_key, description, config_schema_json, enabled
) values
  ('tool-filesystem', 'Filesystem', 'builtin', 'filesystem', 'Read and write files inside the allowed workspace.', json_object(), true),
  ('tool-git', 'Git', 'builtin', 'git', 'Inspect diffs, branches and repository state.', json_object(), true),
  ('tool-shell', 'Shell', 'builtin', 'shell', 'Run allowlisted shell commands.', json_object(), true),
  ('tool-http-client', 'HTTP Client', 'builtin', 'http-client', 'Call local or remote HTTP endpoints for verification.', json_object(), true);

insert into skill_definitions (
  skill_id, display_name, skill_kind, purpose, instruction_text, input_schema_json, output_schema_json, enabled
) values
  ('skill-requirement-drafting', '需求整理', 'analysis', '整理需求对话并生成结构化需求文档。', 'Maintain requirement context and produce a confirmed requirement doc.', json_object(), json_object(), true),
  ('skill-architecture-planning', '架构规划', 'planning', '识别澄清点、输出任务图并建议 Agent。', 'Review the requirement, ask for missing facts, design tasks and suggest agents.', json_object(), json_object(), true),
  ('skill-java-coding', 'Java 编码', 'coding', '在受限写域内完成 Java 后端实现。', 'Implement Java backend changes in the assigned worktree and keep evidence concise.', json_object(), json_object(), true),
  ('skill-api-testing', '接口测试', 'testing', '执行黑盒接口测试与返回值校验。', 'Run API smoke checks or scripted HTTP verification.', json_object(), json_object(), true),
  ('skill-verify-and-merge', '验证与合并', 'verification', '执行验证并产出交付候选证据。', 'Run verify commands, check workspace cleanliness and prepare merge evidence.', json_object(), json_object(), true);

insert into skill_tool_bindings (
  skill_id, tool_id, required_flag, invocation_mode, sort_order
) values
  ('skill-requirement-drafting', 'tool-filesystem', false, 'OPTIONAL', 100),
  ('skill-architecture-planning', 'tool-filesystem', false, 'OPTIONAL', 100),
  ('skill-java-coding', 'tool-filesystem', true, 'PRIMARY', 10),
  ('skill-java-coding', 'tool-git', true, 'PRIMARY', 20),
  ('skill-java-coding', 'tool-shell', true, 'PRIMARY', 30),
  ('skill-api-testing', 'tool-http-client', true, 'PRIMARY', 10),
  ('skill-api-testing', 'tool-shell', true, 'PRIMARY', 20),
  ('skill-verify-and-merge', 'tool-shell', true, 'PRIMARY', 10),
  ('skill-verify-and-merge', 'tool-git', true, 'PRIMARY', 20),
  ('skill-verify-and-merge', 'tool-http-client', false, 'OPTIONAL', 30);

insert into capability_packs (
  capability_pack_id, display_name, capability_kind, granularity, purpose, description, enabled
) values
  ('cap-requirement-discovery', '需求澄清能力包', 'requirement', 'node', '支撑需求代理进行对话和需求文档产出。', 'Bind requirement drafting behaviour without exposing arbitrary execution tools.', true),
  ('cap-architecture-planning', '架构规划能力包', 'planning', 'node', '支撑架构代理做任务拆解和 Agent 建议。', 'Bind planning-centric skills and limited read-oriented tools.', true),
  ('cap-java-backend-coding', 'Java 后端编码能力包', 'coding', 'task', '完成 Java 后端实现与单元测试修改。', 'Bundled for Java implementation tasks that need JDK, Maven, Git and workspace writes.', true),
  ('cap-api-test', '接口测试能力包', 'testing', 'task', '完成接口烟雾测试与黑盒校验。', 'Bundled for HTTP/API checks requiring Python or curl style execution.', true),
  ('cap-verify', '验证能力包', 'verification', 'node', '完成 verify 阶段的检查与合并前验证。', 'Read-only verification and merge-evidence generation.', true);

insert into capability_pack_runtime_packs (
  capability_pack_id, runtime_pack_id, required_flag, purpose
) values
  ('cap-java-backend-coding', 'rt-java-21', true, 'Compile and run Java source.'),
  ('cap-java-backend-coding', 'rt-maven-3', true, 'Resolve dependencies and run Maven test lifecycle.'),
  ('cap-java-backend-coding', 'rt-git', true, 'Inspect diffs and branch state.'),
  ('cap-api-test', 'rt-python-3_11', false, 'Optional scripted API checks.'),
  ('cap-api-test', 'rt-curl', true, 'Direct HTTP smoke checks.'),
  ('cap-verify', 'rt-java-21', false, 'Some verify tasks need Java runtime.'),
  ('cap-verify', 'rt-maven-3', false, 'Some verify tasks run Maven test lifecycle.'),
  ('cap-verify', 'rt-git', true, 'Inspect merge candidate and cleanliness.'),
  ('cap-verify', 'rt-curl', false, 'Optional endpoint verification.'),
  ('cap-requirement-discovery', 'rt-git', false, 'Optional repo reference inspection.'),
  ('cap-architecture-planning', 'rt-git', false, 'Optional repo topology inspection.');

insert into capability_pack_tools (
  capability_pack_id, tool_id, required_flag, exposure_mode
) values
  ('cap-java-backend-coding', 'tool-filesystem', true, 'DIRECT'),
  ('cap-java-backend-coding', 'tool-git', true, 'DIRECT'),
  ('cap-java-backend-coding', 'tool-shell', true, 'DIRECT'),
  ('cap-api-test', 'tool-http-client', true, 'DIRECT'),
  ('cap-api-test', 'tool-shell', true, 'DIRECT'),
  ('cap-verify', 'tool-shell', true, 'DIRECT'),
  ('cap-verify', 'tool-git', true, 'DIRECT'),
  ('cap-verify', 'tool-http-client', false, 'OPTIONAL'),
  ('cap-requirement-discovery', 'tool-filesystem', false, 'OPTIONAL'),
  ('cap-architecture-planning', 'tool-filesystem', false, 'OPTIONAL');

insert into capability_pack_skills (
  capability_pack_id, skill_id, required_flag, role_in_pack
) values
  ('cap-requirement-discovery', 'skill-requirement-drafting', true, 'PRIMARY'),
  ('cap-architecture-planning', 'skill-architecture-planning', true, 'PRIMARY'),
  ('cap-java-backend-coding', 'skill-java-coding', true, 'PRIMARY'),
  ('cap-api-test', 'skill-api-testing', true, 'PRIMARY'),
  ('cap-verify', 'skill-verify-and-merge', true, 'PRIMARY');

insert into agent_definitions (
  agent_id, display_name, purpose, registration_source, system_prompt_text,
  runtime_type, model, max_parallel_runs,
  architect_suggested, auto_pool_eligible, manual_registration_allowed, enabled
) values
  ('requirement-agent', '需求代理', '负责需求澄清、需求文档草拟与修订。', 'SYSTEM', 'Keep the user conversation focused on requirement intent and requirement document quality.', 'in-process', 'gpt-5-class', 4, false, false, true, true),
  ('architect-agent', '架构代理', '负责架构审查、任务拆分、Agent 建议与人工提请。', 'SYSTEM', 'Review the requirement, ask for missing facts and produce task plans and agent suggestions.', 'in-process', 'gpt-5-class', 2, true, false, true, true),
  ('coding-agent-java', 'Java 编码代理', '负责在受控 worktree 中完成 Java 后端实现。', 'SYSTEM', 'Implement the assigned Java task within the provided write scope.', 'docker', 'gpt-5-class', 8, false, true, true, true),
  ('verify-agent-java', '验证代理', '负责执行验证、产生验证证据并支撑合并闸门。', 'SYSTEM', 'Run verification in a read-only manner and report merge evidence.', 'docker', 'gpt-5-class', 8, false, true, true, true);

insert into agent_definition_capability_packs (
  agent_id, capability_pack_id, required_flag
) values
  ('requirement-agent', 'cap-requirement-discovery', true),
  ('architect-agent', 'cap-architecture-planning', true),
  ('coding-agent-java', 'cap-java-backend-coding', true),
  ('coding-agent-java', 'cap-api-test', false),
  ('verify-agent-java', 'cap-verify', true);

insert into workflow_templates (
  workflow_template_id, display_name, description, mutability, registration_policy, is_system_builtin, enabled, version
) values
  ('builtin-coding-flow', '内置代码交付流程', '固定结构的代码交付流程，只允许替换部分 Agent 节点和调整少量参数。', 'FIXED_STRUCTURE_AGENT_TUNABLE', 'SYSTEM_ONLY', true, true, 'v1');

insert into workflow_template_nodes (
  workflow_template_id, node_id, display_name, node_kind, sequence_no, default_agent_id, agent_binding_configurable, parameter_schema_json
) values
  ('builtin-coding-flow', 'requirement', '需求代理', 'AGENT', 10, 'requirement-agent', true, json_object()),
  ('builtin-coding-flow', 'architect', '架构代理', 'AGENT', 20, 'architect-agent', true, json_object()),
  ('builtin-coding-flow', 'ticket-gate', '工单收件箱', 'HUMAN_GATE', 30, null, false, json_object()),
  ('builtin-coding-flow', 'task-graph', '任务图', 'SYSTEM', 40, null, false, json_object()),
  ('builtin-coding-flow', 'worker-manager', '工作代理管理器', 'SYSTEM', 50, null, false, json_object()),
  ('builtin-coding-flow', 'coding', '编码代理', 'AGENT', 60, 'coding-agent-java', true, json_object('maxParallelCodingAgents', 'integer')),
  ('builtin-coding-flow', 'merge-gate', '合并闸门', 'SYSTEM', 70, null, false, json_object()),
  ('builtin-coding-flow', 'verify', '验证代理', 'AGENT', 80, 'verify-agent-java', true, json_object('verificationStrictness', 'string'));
