insert into runtime_packs (
  runtime_pack_id, display_name, pack_type, version, locator, description, enabled
) values
  ('rt-node-22', 'Node 22', 'language', '22', null, 'Node.js 22 runtime for TypeScript-flavored monorepo fixtures.', true)
on duplicate key update
  display_name = values(display_name),
  version = values(version),
  description = values(description),
  enabled = values(enabled);

insert into capability_packs (
  capability_pack_id, display_name, capability_kind, granularity, purpose, description, enabled
) values
  ('cap-ts-fullstack-coding', 'TS 全栈编码能力包', 'coding', 'task', '完成 pnpm monorepo 下 shared/api/web 的联动实现。', 'Bundled for shared contract, API feature, web feature, and cross-layer regression updates.', true),
  ('cap-ts-fullstack-verify', 'TS 全栈验证能力包', 'verification', 'node', '完成 pnpm monorepo 的 build、API 与集成验证。', 'Read-only verification for the TypeScript fullstack profile.', true)
on duplicate key update
  display_name = values(display_name),
  purpose = values(purpose),
  description = values(description),
  enabled = values(enabled);

insert into capability_pack_runtime_packs (
  capability_pack_id, runtime_pack_id, required_flag, purpose
) values
  ('cap-ts-fullstack-coding', 'rt-node-22', true, 'Run shared/api/web workspace scripts.'),
  ('cap-ts-fullstack-coding', 'rt-git', true, 'Inspect diffs and create delivery commits.'),
  ('cap-ts-fullstack-verify', 'rt-node-22', true, 'Run profile verify commands.'),
  ('cap-ts-fullstack-verify', 'rt-git', true, 'Capture merge and verify evidence.')
on duplicate key update
  required_flag = values(required_flag),
  purpose = values(purpose);

insert into capability_pack_tools (
  capability_pack_id, tool_id, required_flag, exposure_mode
) values
  ('cap-ts-fullstack-coding', 'tool-filesystem', true, 'DIRECT'),
  ('cap-ts-fullstack-coding', 'tool-git', true, 'DIRECT'),
  ('cap-ts-fullstack-coding', 'tool-shell', true, 'DIRECT'),
  ('cap-ts-fullstack-verify', 'tool-git', true, 'DIRECT'),
  ('cap-ts-fullstack-verify', 'tool-shell', true, 'DIRECT')
on duplicate key update
  required_flag = values(required_flag),
  exposure_mode = values(exposure_mode);

insert into agent_definitions (
  agent_id, display_name, purpose, registration_source, system_prompt_text,
  runtime_type, model, max_parallel_runs,
  architect_suggested, auto_pool_eligible, manual_registration_allowed, enabled
) values
  ('coding-agent-ts-fullstack', 'TS 全栈编码代理', '负责在 pnpm monorepo 中完成 shared/api/web 的实现。', 'SYSTEM', 'Implement the assigned TypeScript fullstack task within the provided write scope and keep shared/api/web contracts aligned.', 'docker', 'gpt-5-class', 8, false, true, true, true),
  ('verify-agent-ts-fullstack', 'TS 全栈验证代理', '负责执行 pnpm monorepo 的 build、API 与集成验证。', 'SYSTEM', 'Run profile-declared verification commands and produce merge-ready evidence without rewriting business truth.', 'docker', 'gpt-5-class', 8, false, true, true, true)
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
  ('coding-agent-ts-fullstack', 'cap-ts-fullstack-coding', true),
  ('verify-agent-ts-fullstack', 'cap-ts-fullstack-verify', true)
on duplicate key update
  required_flag = values(required_flag);
