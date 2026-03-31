insert into capability_packs (
  capability_pack_id, display_name, capability_kind, granularity, purpose, description, enabled
) values
  ('cap-java-backend-coding', 'Java 后端编码能力包', 'coding', 'task', '完成 Java 后端实现与单元测试修改。', 'Bundled for Java implementation tasks that need JDK, Maven, Git and workspace writes.', true),
  ('cap-api-test', '接口测试能力包', 'testing', 'task', '完成接口烟雾测试与黑盒校验。', 'Bundled for HTTP/API checks requiring Python or curl style execution.', true),
  ('cap-verify', '验证能力包', 'verification', 'node', '完成 verify 阶段的检查与合并前验证。', 'Read-only verification and merge-evidence generation.', true)
on duplicate key update
  display_name = values(display_name),
  purpose = values(purpose),
  description = values(description),
  enabled = values(enabled);

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
  ('cap-verify', 'rt-curl', false, 'Optional endpoint verification.')
on duplicate key update
  required_flag = values(required_flag),
  purpose = values(purpose);

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
  ('cap-verify', 'tool-http-client', false, 'OPTIONAL')
on duplicate key update
  required_flag = values(required_flag),
  exposure_mode = values(exposure_mode);

insert into agent_definitions (
  agent_id, display_name, purpose, registration_source, system_prompt_text,
  runtime_type, model, max_parallel_runs,
  architect_suggested, auto_pool_eligible, manual_registration_allowed, enabled
) values
  ('coding-agent-java', 'Java 编码代理', '负责在受控 worktree 中完成 Java 后端实现。', 'SYSTEM', 'Implement the assigned Java task within the provided write scope.', 'docker', 'gpt-5-class', 8, false, true, true, true),
  ('verify-agent-java', '验证代理', '负责执行验证、产生验证证据并支撑合并闸门。', 'SYSTEM', 'Run verification in a read-only manner and report merge evidence.', 'docker', 'gpt-5-class', 8, false, true, true, true)
on duplicate key update
  display_name = values(display_name),
  purpose = values(purpose),
  system_prompt_text = values(system_prompt_text),
  runtime_type = values(runtime_type),
  model = values(model),
  max_parallel_runs = values(max_parallel_runs),
  enabled = values(enabled);

insert into agent_definition_capability_packs (
  agent_id, capability_pack_id, required_flag
) values
  ('coding-agent-java', 'cap-java-backend-coding', true),
  ('coding-agent-java', 'cap-api-test', false),
  ('verify-agent-java', 'cap-verify', true)
on duplicate key update
  required_flag = values(required_flag);
