# Workforce Runtime Python Test

## Coverage

`test_auto_provision_and_lease_recovery.py` validates M4 flow using real backend + MySQL:

1. Create a `WAITING_WORKER` task that requires a custom toolpack and has no eligible worker.
2. Trigger one-shot auto-provision endpoint: `POST /api/v0/workforce/auto-provision`.
3. Verify worker is created and bound to required toolpack.
4. Create a new task with same toolpack; it should become `READY_FOR_ASSIGN`.
5. Insert READY context snapshot and claim task via `POST /api/v0/workers/{workerId}/claim`.
6. Force lease timeout and trigger one-shot recovery endpoint: `POST /api/v0/execution/lease-recovery`.
7. Verify DB transitions:
   - `task_runs.status -> FAILED`
   - `work_tasks.status -> READY_FOR_ASSIGN` and `active_run_id -> null`
   - `task_run_events` contains `RUN_FINISHED`
   - `git_workspaces.status -> RELEASED`

## Prerequisites

1. MySQL is running.
2. Backend is running at `http://127.0.0.1:8080` (or set `AGENTX_BASE_URL`).
3. DB user has create-table privileges.

## Run

```powershell
python tests/python/workforce_runtime/test_auto_provision_and_lease_recovery.py
```

## Environment Variables

- `AGENTX_BASE_URL` default: `http://127.0.0.1:8080`
- `AGENTX_DB_HOST` default: `127.0.0.1`
- `AGENTX_DB_PORT` default: `3306`
- `AGENTX_DB_NAME` default: `agentx_backend`
- `AGENTX_DB_USERNAME` default: `root`
- `AGENTX_DB_PASSWORD` default: (empty)

