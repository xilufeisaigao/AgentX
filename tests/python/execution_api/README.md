# Execution API Python Tests

## Coverage

`test_execution_worker_claim_api.py` validates M3 worker-claim runtime flow against real backend + MySQL:

1. Create planning task via API (`/sessions/{sessionId}/modules`, `/modules/{moduleId}/tasks`)
2. Insert READY context snapshot for the task
3. Worker claim API (`POST /api/v0/workers/{workerId}/claim`) returns task package and run id
4. Heartbeat API (`POST /api/v0/runs/{runId}/heartbeat`) extends runtime lease
5. Verify DB state:
   - `work_tasks` -> `ASSIGNED`, `active_run_id=run_id`
   - `task_runs` -> `RUNNING`
   - `task_run_events` includes `RUN_STARTED` and `HEARTBEAT`
   - `git_workspaces` includes `ALLOCATED`
6. Missing READY snapshot path returns `412 PRECONDITION_FAILED` and task claim is released back to `READY_FOR_ASSIGN`
7. Test data is isolated by unique ids; script does not delete existing rows.

## Prerequisites

1. MySQL service is running.
2. Backend service is running at `http://127.0.0.1:8080` (or set `AGENTX_BASE_URL`).
3. Database account has rights to create database/tables.

## Run

```powershell
python tests/python/execution_api/test_execution_worker_claim_api.py
```

## Environment Variables

- `AGENTX_BASE_URL` default: `http://127.0.0.1:8080`
- `AGENTX_DB_HOST` default: `127.0.0.1`
- `AGENTX_DB_PORT` default: `3306`
- `AGENTX_DB_NAME` default: `agentx_backend`
- `AGENTX_DB_USERNAME` default: `root`
- `AGENTX_DB_PASSWORD` default: (empty)

