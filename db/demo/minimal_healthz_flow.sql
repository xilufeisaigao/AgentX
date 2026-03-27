use agentx_platform;

delete from workflow_node_run_events where event_id like 'demo-%';
delete from workflow_node_runs where node_run_id like 'demo-%';
delete from git_workspaces where workspace_id like 'demo-%';
delete from task_run_events where event_id like 'demo-%';
delete from task_runs where run_id like 'demo-%';
delete from agent_pool_instances where agent_instance_id like 'demo-%';
delete from task_context_snapshots where snapshot_id like 'demo-%';
delete from work_task_dependencies where task_id like 'demo-%' or depends_on_task_id like 'demo-%';
delete from work_task_capability_requirements where task_id like 'demo-%';
delete from work_tasks where task_id like 'demo-%';
delete from work_modules where module_id like 'demo-%';
delete from ticket_events where event_id like 'demo-%';
delete from tickets where ticket_id like 'demo-%';
delete from requirement_doc_versions where doc_id like 'demo-%';
delete from requirement_docs where doc_id like 'demo-%';
delete from workflow_run_events where event_id like 'demo-%';
delete from workflow_run_node_bindings where binding_id like 'demo-%';
delete from workflow_runs where workflow_run_id like 'demo-%';

insert into workflow_runs (
  workflow_run_id, workflow_template_id, title, status, entry_mode, auto_agent_mode,
  created_by_actor_type, created_by_actor_id, created_at, updated_at
) values (
  'demo-run-healthz',
  'builtin-coding-flow',
  '新增 healthz 接口',
  'COMPLETED',
  'MANUAL',
  true,
  'HUMAN',
  'user-001',
  '2026-03-27 14:00:00.000',
  '2026-03-27 14:40:00.000'
);

insert into workflow_run_node_bindings (
  binding_id, workflow_run_id, node_id, binding_mode, selected_agent_id, rationale, locked_by_user,
  created_at, updated_at
) values
  ('demo-bind-requirement', 'demo-run-healthz', 'requirement', 'DEFAULT', 'requirement-agent', '使用系统默认需求代理。', false, '2026-03-27 14:00:00.000', '2026-03-27 14:00:00.000'),
  ('demo-bind-architect', 'demo-run-healthz', 'architect', 'DEFAULT', 'architect-agent', '使用系统默认架构代理。', false, '2026-03-27 14:00:00.000', '2026-03-27 14:00:00.000'),
  ('demo-bind-coding', 'demo-run-healthz', 'coding', 'AUTO_SELECTED', 'coding-agent-java', '根据任务能力要求自动选择 Java 编码代理。', false, '2026-03-27 14:10:00.000', '2026-03-27 14:10:00.000'),
  ('demo-bind-verify', 'demo-run-healthz', 'verify', 'DEFAULT', 'verify-agent-java', '验证阶段沿用系统默认验证代理。', false, '2026-03-27 14:10:00.000', '2026-03-27 14:10:00.000');

insert into workflow_run_events (
  event_id, workflow_run_id, event_type, actor_type, actor_id, body, data_json, created_at
) values
  ('demo-run-event-001', 'demo-run-healthz', 'WORKFLOW_STARTED', 'HUMAN', 'user-001', '用户发起新增 healthz 接口需求。', json_object('entryMode', 'MANUAL'), '2026-03-27 14:00:00.000'),
  ('demo-run-event-002', 'demo-run-healthz', 'TASK_GRAPH_CONFIRMED', 'AGENT', 'architect-agent', '架构代理确认拆出两个任务，并生成 DAG。', json_object('taskCount', 2), '2026-03-27 14:18:00.000'),
  ('demo-run-event-003', 'demo-run-healthz', 'MERGE_APPROVED', 'SYSTEM', 'merge-gate', '验证通过，主流程进入完成态。', json_object('verified', true), '2026-03-27 14:40:00.000');

insert into workflow_node_runs (
  node_run_id, workflow_run_id, node_id, selected_agent_id, agent_instance_id, status,
  input_payload_json, output_payload_json, started_at, finished_at, created_at, updated_at
) values
  (
    'demo-node-requirement-v1',
    'demo-run-healthz',
    'requirement',
    'requirement-agent',
    null,
    'SUCCEEDED',
    json_object('conversationTurns', 3),
    json_object('docId', 'demo-req-healthz', 'version', 1, 'status', 'DRAFT'),
    '2026-03-27 14:00:00.000',
    '2026-03-27 14:03:00.000',
    '2026-03-27 14:00:00.000',
    '2026-03-27 14:03:00.000'
  ),
  (
    'demo-node-architect-review',
    'demo-run-healthz',
    'architect',
    'architect-agent',
    null,
    'SUCCEEDED',
    json_object('requirementDocId', 'demo-req-healthz', 'version', 1),
    json_object('createdTicketId', 'demo-ticket-healthz-db-check'),
    '2026-03-27 14:03:00.000',
    '2026-03-27 14:05:00.000',
    '2026-03-27 14:03:00.000',
    '2026-03-27 14:05:00.000'
  ),
  (
    'demo-node-requirement-v2',
    'demo-run-healthz',
    'requirement',
    'requirement-agent',
    null,
    'SUCCEEDED',
    json_object('ticketId', 'demo-ticket-healthz-db-check', 'answerApplied', true),
    json_object('docId', 'demo-req-healthz', 'version', 2, 'status', 'CONFIRMED'),
    '2026-03-27 14:06:00.000',
    '2026-03-27 14:08:00.000',
    '2026-03-27 14:06:00.000',
    '2026-03-27 14:08:00.000'
  ),
  (
    'demo-node-architect-plan',
    'demo-run-healthz',
    'architect',
    'architect-agent',
    null,
    'SUCCEEDED',
    json_object('requirementDocId', 'demo-req-healthz', 'version', 2),
    json_object('moduleId', 'demo-module-api', 'taskIds', json_array('demo-task-healthz-endpoint', 'demo-task-healthz-smoke')),
    '2026-03-27 14:08:00.000',
    '2026-03-27 14:10:00.000',
    '2026-03-27 14:08:00.000',
    '2026-03-27 14:10:00.000'
  );

insert into workflow_node_run_events (
  event_id, node_run_id, event_type, body, data_json, created_at
) values
  ('demo-node-event-001', 'demo-node-requirement-v1', 'DOC_DRAFTED', '需求代理产出第一版需求文档。', json_object('version', 1), '2026-03-27 14:03:00.000'),
  ('demo-node-event-002', 'demo-node-architect-review', 'CLARIFICATION_REQUESTED', '架构代理发现数据库探测边界需要澄清。', json_object('ticketId', 'demo-ticket-healthz-db-check'), '2026-03-27 14:05:00.000'),
  ('demo-node-event-003', 'demo-node-requirement-v2', 'DOC_CONFIRMED', '需求代理根据人工回复修订并确认需求文档。', json_object('version', 2), '2026-03-27 14:08:00.000'),
  ('demo-node-event-004', 'demo-node-architect-plan', 'TASKS_PLANNED', '架构代理输出模块、任务及依赖。', json_object('taskCount', 2), '2026-03-27 14:10:00.000');

insert into requirement_docs (
  doc_id, workflow_run_id, current_version, confirmed_version, status, title, created_at, updated_at
) values (
  'demo-req-healthz',
  'demo-run-healthz',
  2,
  2,
  'CONFIRMED',
  'healthz 接口需求',
  '2026-03-27 14:03:00.000',
  '2026-03-27 14:08:00.000'
);

insert into requirement_doc_versions (
  doc_id, version, content, created_by_actor_type, created_by_actor_id, created_at
) values
  (
    'demo-req-healthz',
    1,
    '新增 GET /healthz 接口，返回服务状态与版本信息，是否探测数据库待确认。',
    'AGENT',
    'requirement-agent',
    '2026-03-27 14:03:00.000'
  ),
  (
    'demo-req-healthz',
    2,
    '新增 GET /healthz 接口，仅检查进程存活和应用版本，不探测数据库；响应体包含 status=UP 与 version 字段。',
    'AGENT',
    'requirement-agent',
    '2026-03-27 14:08:00.000'
  );

insert into tickets (
  ticket_id, workflow_run_id, type, status, title,
  created_by_actor_type, created_by_actor_id,
  assignee_actor_type, assignee_actor_id,
  origin_node_id, requirement_doc_id, requirement_doc_ver, payload_json,
  claimed_by_actor_id, claimed_by_instance_id, lease_until, created_at, updated_at
) values (
  'demo-ticket-healthz-db-check',
  'demo-run-healthz',
  'CLARIFICATION',
  'ANSWERED',
  'healthz 是否需要探测数据库',
  'AGENT',
  'architect-agent',
  'HUMAN',
  'user-001',
  'architect',
  'demo-req-healthz',
  1,
  json_object(
    'question', 'GET /healthz 是否需要包含数据库连通性检查',
    'options', json_array('仅进程健康', '包含数据库探测')
  ),
  'user-001',
  null,
  null,
  '2026-03-27 14:05:00.000',
  '2026-03-27 14:06:00.000'
);

insert into ticket_events (
  event_id, ticket_id, event_type, actor_type, actor_id, body, data_json, created_at
) values
  ('demo-ticket-event-001', 'demo-ticket-healthz-db-check', 'CREATED', 'AGENT', 'architect-agent', '架构代理发起澄清提请。', json_object('originNodeId', 'architect'), '2026-03-27 14:05:00.000'),
  ('demo-ticket-event-002', 'demo-ticket-healthz-db-check', 'ANSWERED', 'HUMAN', 'user-001', '只需要进程健康，不需要数据库探测。', json_object('selectedOption', '仅进程健康'), '2026-03-27 14:06:00.000');

insert into work_modules (
  module_id, workflow_run_id, name, description, created_at, updated_at
) values (
  'demo-module-api',
  'demo-run-healthz',
  'api-health',
  'healthz 接口相关实现与验证。',
  '2026-03-27 14:10:00.000',
  '2026-03-27 14:10:00.000'
);

insert into work_tasks (
  task_id, module_id, title, objective, task_template_id, status,
  write_scope_json, delivery_contract_json, origin_ticket_id,
  created_by_actor_type, created_by_actor_id, created_at, updated_at
) values
  (
    'demo-task-healthz-endpoint',
    'demo-module-api',
    '实现 healthz 接口',
    '新增 GET /healthz 接口并返回 status/version 字段。',
    'java-backend-task',
    'DONE',
    json_array('src/main/java/**', 'src/test/java/**'),
    json_object('deliverables', json_array('controller', 'service', 'unit-test')),
    'demo-ticket-healthz-db-check',
    'AGENT',
    'architect-agent',
    '2026-03-27 14:10:00.000',
    '2026-03-27 14:28:00.000'
  ),
  (
    'demo-task-healthz-smoke',
    'demo-module-api',
    '补充 healthz 冒烟验证',
    '为 /healthz 增加接口级验证脚本或校验步骤。',
    'api-smoke-task',
    'DONE',
    json_array('scripts/**', 'src/test/**'),
    json_object('deliverables', json_array('smoke-check', 'verification-note')),
    null,
    'AGENT',
    'architect-agent',
    '2026-03-27 14:10:00.000',
    '2026-03-27 14:34:00.000'
  );

insert into work_task_capability_requirements (
  task_id, capability_pack_id, required_flag, role_in_task, created_at
) values
  ('demo-task-healthz-endpoint', 'cap-java-backend-coding', true, 'PRIMARY', '2026-03-27 14:10:00.000'),
  ('demo-task-healthz-smoke', 'cap-api-test', true, 'PRIMARY', '2026-03-27 14:10:00.000');

insert into work_task_dependencies (
  task_id, depends_on_task_id, required_upstream_status, created_at
) values (
  'demo-task-healthz-smoke',
  'demo-task-healthz-endpoint',
  'DONE',
  '2026-03-27 14:10:00.000'
);

insert into task_context_snapshots (
  snapshot_id, task_id, run_kind, status, trigger_type, source_fingerprint,
  task_context_ref, task_skill_ref, error_code, error_message, compiled_at, retained_until,
  created_at, updated_at
) values
  (
    'demo-snapshot-healthz-endpoint',
    'demo-task-healthz-endpoint',
    'PRIMARY',
    'READY',
    'PLAN_CONFIRMED',
    'fp-demo-healthz-endpoint-v1',
    'object://context/demo-task-healthz-endpoint/v1.json',
    'object://skills/cap-java-backend-coding/v1.json',
    null,
    null,
    '2026-03-27 14:11:00.000',
    '2026-04-03 14:11:00.000',
    '2026-03-27 14:11:00.000',
    '2026-03-27 14:11:00.000'
  ),
  (
    'demo-snapshot-healthz-smoke',
    'demo-task-healthz-smoke',
    'PRIMARY',
    'READY',
    'UPSTREAM_DELIVERED',
    'fp-demo-healthz-smoke-v1',
    'object://context/demo-task-healthz-smoke/v1.json',
    'object://skills/cap-api-test/v1.json',
    null,
    null,
    '2026-03-27 14:29:00.000',
    '2026-04-03 14:29:00.000',
    '2026-03-27 14:29:00.000',
    '2026-03-27 14:29:00.000'
  );

insert into agent_pool_instances (
  agent_instance_id, agent_id, runtime_type, status, launch_mode, current_workflow_run_id,
  lease_until, last_heartbeat_at, endpoint_ref, runtime_metadata_json, created_at, updated_at
) values
  (
    'demo-agent-instance-code-01',
    'coding-agent-java',
    'docker',
    'READY',
    'AUTO_POOL',
    'demo-run-healthz',
    '2026-03-27 15:00:00.000',
    '2026-03-27 14:34:00.000',
    'docker://agentx/coding-agent-java/01',
    json_object('containerId', 'cnt-code-01'),
    '2026-03-27 14:12:00.000',
    '2026-03-27 14:34:00.000'
  ),
  (
    'demo-agent-instance-code-02',
    'coding-agent-java',
    'docker',
    'READY',
    'AUTO_POOL',
    'demo-run-healthz',
    '2026-03-27 15:00:00.000',
    '2026-03-27 14:34:00.000',
    'docker://agentx/coding-agent-java/02',
    json_object('containerId', 'cnt-code-02'),
    '2026-03-27 14:28:00.000',
    '2026-03-27 14:34:00.000'
  ),
  (
    'demo-agent-instance-verify-01',
    'verify-agent-java',
    'docker',
    'READY',
    'AUTO_POOL',
    'demo-run-healthz',
    '2026-03-27 15:00:00.000',
    '2026-03-27 14:40:00.000',
    'docker://agentx/verify-agent-java/01',
    json_object('containerId', 'cnt-verify-01'),
    '2026-03-27 14:34:00.000',
    '2026-03-27 14:40:00.000'
  );

insert into workflow_node_runs (
  node_run_id, workflow_run_id, node_id, selected_agent_id, agent_instance_id, status,
  input_payload_json, output_payload_json, started_at, finished_at, created_at, updated_at
) values (
  'demo-node-verify',
  'demo-run-healthz',
  'verify',
  'verify-agent-java',
  'demo-agent-instance-verify-01',
  'SUCCEEDED',
  json_object('deliveredTasks', 2),
  json_object('result', 'PASSED', 'mergeReady', true),
  '2026-03-27 14:34:00.000',
  '2026-03-27 14:40:00.000',
  '2026-03-27 14:34:00.000',
  '2026-03-27 14:40:00.000'
);

insert into workflow_node_run_events (
  event_id, node_run_id, event_type, body, data_json, created_at
) values (
  'demo-node-event-005',
  'demo-node-verify',
  'VERIFY_PASSED',
  '验证代理确认候选交付满足要求。',
  json_object('mergeReady', true),
  '2026-03-27 14:40:00.000'
);

insert into task_runs (
  run_id, task_id, agent_instance_id, status, run_kind, context_snapshot_id,
  lease_until, last_heartbeat_at, started_at, finished_at, execution_contract_json,
  created_at, updated_at
) values
  (
    'demo-task-run-healthz-endpoint-01',
    'demo-task-healthz-endpoint',
    'demo-agent-instance-code-01',
    'SUCCEEDED',
    'PRIMARY',
    'demo-snapshot-healthz-endpoint',
    '2026-03-27 14:28:00.000',
    '2026-03-27 14:28:00.000',
    '2026-03-27 14:12:00.000',
    '2026-03-27 14:28:00.000',
    json_object(
      'verifyCommands', json_array('mvn -q -Dtest=HealthzControllerTest test'),
      'writeScope', json_array('src/main/java/**', 'src/test/java/**')
    ),
    '2026-03-27 14:12:00.000',
    '2026-03-27 14:28:00.000'
  ),
  (
    'demo-task-run-healthz-smoke-01',
    'demo-task-healthz-smoke',
    'demo-agent-instance-code-02',
    'SUCCEEDED',
    'PRIMARY',
    'demo-snapshot-healthz-smoke',
    '2026-03-27 14:34:00.000',
    '2026-03-27 14:34:00.000',
    '2026-03-27 14:29:00.000',
    '2026-03-27 14:34:00.000',
    json_object(
      'verifyCommands', json_array('curl -f http://localhost:8080/healthz'),
      'writeScope', json_array('scripts/**', 'src/test/**')
    ),
    '2026-03-27 14:29:00.000',
    '2026-03-27 14:34:00.000'
  );

insert into task_run_events (
  event_id, run_id, event_type, body, data_json, created_at
) values
  (
    'demo-task-run-event-001',
    'demo-task-run-healthz-endpoint-01',
    'RUN_FINISHED',
    '编码任务完成并提交交付候选。',
    json_object('taskFinalStatus', 'DONE', 'producedFiles', json_array('HealthzController.java', 'HealthzControllerTest.java')),
    '2026-03-27 14:28:00.000'
  ),
  (
    'demo-task-run-event-002',
    'demo-task-run-healthz-smoke-01',
    'RUN_FINISHED',
    '冒烟验证任务完成。',
    json_object('taskFinalStatus', 'DONE', 'producedFiles', json_array('healthz-smoke.sh')),
    '2026-03-27 14:34:00.000'
  );

insert into git_workspaces (
  workspace_id, run_id, task_id, status, repo_root, worktree_path, branch_name,
  base_commit, head_commit, merge_commit, cleanup_status, created_at, updated_at
) values
  (
    'demo-workspace-healthz-endpoint',
    'demo-task-run-healthz-endpoint-01',
    'demo-task-healthz-endpoint',
    'MERGED',
    'D:/DeskTop/agentx-platform',
    'D:/DeskTop/.worktrees/demo-healthz-endpoint',
    'task/demo-healthz-endpoint',
    'abc123base',
    'def456head',
    'merge789commit',
    'PENDING',
    '2026-03-27 14:12:00.000',
    '2026-03-27 14:40:00.000'
  ),
  (
    'demo-workspace-healthz-smoke',
    'demo-task-run-healthz-smoke-01',
    'demo-task-healthz-smoke',
    'MERGED',
    'D:/DeskTop/agentx-platform',
    'D:/DeskTop/.worktrees/demo-healthz-smoke',
    'task/demo-healthz-smoke',
    'def456head',
    'fed321head',
    'merge999commit',
    'PENDING',
    '2026-03-27 14:29:00.000',
    '2026-03-27 14:40:00.000'
  );

select
  wr.workflow_run_id,
  wr.title,
  wr.status,
  rd.confirmed_version,
  count(distinct wt.task_id) as task_count,
  count(distinct tr.run_id) as task_run_count,
  count(distinct t.ticket_id) as ticket_count
from workflow_runs wr
left join requirement_docs rd on rd.workflow_run_id = wr.workflow_run_id
left join work_modules wm on wm.workflow_run_id = wr.workflow_run_id
left join work_tasks wt on wt.module_id = wm.module_id
left join task_runs tr on tr.task_id = wt.task_id
left join tickets t on t.workflow_run_id = wr.workflow_run_id
where wr.workflow_run_id = 'demo-run-healthz'
group by wr.workflow_run_id, wr.title, wr.status, rd.confirmed_version;
