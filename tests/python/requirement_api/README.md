# Requirement API Python Tests

## Coverage

`test_requirement_api.py` validates the Requirement HTTP workflow against a real running backend:

1. Create requirement doc -> create version -> confirm (`happy path`)
2. Verify `confirm` auto-creates one `ARCH_REVIEW` ticket (it may be immediately consumed by architect auto-processor)
3. Verify `confirm` path appends one `ticket_events` audit record (`COMMENT`, `actor_role=requirement_agent`)
4. Confirm without any version (`409 CONFLICT`)
5. Create version with invalid `created_by_role` (`400 BAD_REQUEST`)
6. Create version on missing doc (`404 NOT_FOUND`)
7. Version content must satisfy `REQ-DOC-v1` markdown format (validated server-side)
8. Test data is isolated by unique `session_id`; script does not delete existing records.

## Prerequisites

1. MySQL service is running.
2. Backend service is running at `http://127.0.0.1:8080` (or set `AGENTX_BASE_URL`).
3. Database account has rights to create database/tables.

## Run

```powershell
python tests/python/requirement_api/test_requirement_api.py
```

## Environment Variables

- `AGENTX_BASE_URL` default: `http://127.0.0.1:8080`
- `AGENTX_DB_HOST` default: `127.0.0.1`
- `AGENTX_DB_PORT` default: `3306`
- `AGENTX_DB_NAME` default: `agentx_backend`
- `AGENTX_DB_USERNAME` default: `root`
- `AGENTX_DB_PASSWORD` default: (empty)

