# Requirement Agent API Python Tests

## Coverage

`test_requirement_agent_api.py` validates the requirement-agent backend API in `mock` LLM mode:

1. Generate and persist a new REQ-DOC-v1 draft
2. Revise an existing requirement doc and create next version
3. Dry-run generation without DB persistence
4. Architecture-layer request auto-creates `HANDOFF` ticket and one `ticket_events` audit record

`run_requirement_agent_real_api.py` is a real integration script that calls backend API only
and relies on backend-side Bailian integration.

`run_requirement_full_flow.py` is now an **interactive console** script:
1. you type requirement feedback in real time
2. backend requirement-agent stays in discovery chat first
3. when backend judges information is sufficient, send `确认需求` to trigger first draft generation
4. continue revision turns until satisfied
5. trigger `/confirm` to finalize the current version
6. backend auto-creates `ARCH_REVIEW` handoff ticket

## Prerequisites

1. MySQL is running.
2. Backend is running at `http://127.0.0.1:8080`.
3. Redis is running (`127.0.0.1:6379` by default, password `upt-123456`) for discovery history persistence.
4. Default backend provider is real Bailian in `application.yml`.
5. For mock-only verification:
   - set `AGENTX_REQUIREMENT_LLM_PROVIDER=mock` before starting backend
6. For real Bailian script:
   - backend started with:
     - `AGENTX_REQUIREMENT_LLM_PROVIDER=bailian`
     - `AGENTX_REQUIREMENT_LLM_API_KEY=<your-key>`
     - optional `AGENTX_REQUIREMENT_LLM_MODEL=qwen3.5-plus-2026-02-15`
7. Test data is isolated by unique `session_id`; scripts do not delete existing records.

## Run (Mock)

```powershell
python tests/python/requirement_agent_api/test_requirement_agent_api.py
```

## Run (Real Bailian Through Backend API)

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/run_requirement_agent_real_api.ps1
```

Or if backend is already running in Bailian mode:

```powershell
python tests/python/requirement_agent_api/run_requirement_agent_real_api.py
```

## Run Interactive Console (Python only)

Start backend automatically on a dedicated port (real Bailian):

```powershell
python tests/python/requirement_agent_api/run_requirement_full_flow.py --start-backend --provider bailian --port 18090
```

Use existing backend:

```powershell
python tests/python/requirement_agent_api/run_requirement_full_flow.py
```

Useful commands inside console:

- `/new <TITLE>`: create and enter a new session
- `/sessions [KEYWORD]`: list all sessions from backend (`GET /api/v0/sessions`), optional keyword filters by session id/title
- `/enter <SESSION_ID>`: enter existing session snapshot and continue (alias: `/load`)
- `/load <SESSION_ID>`: load an existing session snapshot and continue in the same console
- `/architect-auto [SESSION_ID] [MAX]`: trigger backend architect auto-processor once (`POST /api/v0/architect/auto-process`)
- `/show`: print latest requirement markdown
- `/confirm`: freeze current version as baseline (`confirmed_version=current_version`) and trigger `ARCH_REVIEW`
- `/tickets OPEN`: inspect open tickets in current session
- `/events [TICKET_ID]`: inspect ticket event chain and generated request payloads
- `/respond [TICKET_ID] <TEXT>`: reply to architect request; backend may either issue another request or auto-plan modules/tasks then close ticket to `DONE`
- `/monitor`: print SQL/log hints to manually verify DB state and handoff
- `/status`: print local session/doc state
- `/help`: list commands

Notes:

- If startup prompt leaves session title empty, console will not auto-create a session; use `/sessions` + `/enter` or `/new`.
- In discovery stage, response `phase` will be `DISCOVERY_CHAT`, `READY_TO_DRAFT`, `NEED_MORE_INFO`, or `HANDOFF_CREATED`.
- `HANDOFF_CREATED` means backend detected architecture-layer change and auto-created a `HANDOFF` ticket for `architect_agent`.
- `/monitor` prints SQL for both `HANDOFF` and `ARCH_REVIEW` plus their `ticket_events` audit chain.
- Draft persistence only happens when `phase` becomes `DRAFT_CREATED` / `DRAFT_REVISED`.
- Redis history is only for discovery turns when `doc_id` is empty; revision turns rely on latest `requirement_doc_versions` content.
- `/confirm` is baseline freeze, not global lock; later value-layer changes can still create a new version and require re-confirm.
- Starting with `--session-id` now auto-loads session snapshot (`session + current requirement doc`) from backend.
- Architect auto-processor should be driven by backend scheduler; the console no longer force-triggers it on `/confirm` or `HANDOFF_CREATED`.

