# Requirement E2E Script

## Run Full Requirement E2E

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/run_requirement_e2e.ps1
```

This script will:

1. Start backend via `mvn spring-boot:run`
2. Execute `tests/python/requirement_api/test_requirement_api.py`
3. Execute `tests/python/ticket_api/test_ticket_api.py`
4. Stop backend process automatically
5. Print log file paths under `target/`

## Run Full Backend Regression Suite

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/run_full_backend_suite.ps1
```

This suite runs:

1. `mvn -q test`
2. Start backend (`spring-boot:run`) on dedicated port
3. Python API/E2E scripts:
   - `tests/python/requirement_api/test_requirement_api.py`
   - `tests/python/requirement_agent_api/test_requirement_agent_api.py`
   - `tests/python/ticket_api/test_ticket_api.py`
   - `tests/python/ticket_api/test_architect_auto_scheduler_flow.py`
   - `tests/python/execution_api/test_execution_worker_claim_api.py`
   - `tests/python/workforce_runtime/test_auto_provision_and_lease_recovery.py`
4. Stop backend automatically and print logs under `target/`

Runtime details:

1. Script auto-picks a free backend port (starting from `18082`) if preferred port is occupied.
2. Script creates an isolated git sandbox under `target/full-suite-git-*` and injects it as workspace/mergegate repo root, so claim/worktree tests do not depend on the current repo git state.

Useful flags:

```powershell
# skip maven unit/integration tests, only run API/E2E
pwsh -NoLogo -NoProfile -File tests/e2e/run_full_backend_suite.ps1 -SkipMavenTests

# skip API/E2E and only run maven tests
pwsh -NoLogo -NoProfile -File tests/e2e/run_full_backend_suite.ps1 -SkipApiE2E

# enable real LLM smoke tests at the end
# AGENTX_REQUIREMENT_LLM_API_KEY must be set explicitly.
# there is no fallback from src/main/resources/application.yml.
pwsh -NoLogo -NoProfile -File tests/e2e/run_full_backend_suite.ps1 -EnableRealLlm
```

## Run Frontend Demo (ChatGPT-like layout)

```powershell
# backend should already be running at http://127.0.0.1:18082
pwsh -NoLogo -NoProfile -File tests/e2e/run_frontend_demo.ps1
```

Open:

```text
http://127.0.0.1:5173
```

Direct npm commands (optional):

```powershell
npm --prefix frontend-demo install
npm --prefix frontend-demo run dev
```

When runtime has produced code for a session, you can publish a temporary clone URL:

```powershell
$sessionId = "<SES-...>"
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:18082/api/v0/sessions/$sessionId/delivery/clone-repo"
```

## Start Docker Runtime With Isolated Artifact Directory

Use this script when you want every runtime startup to use a fresh project artifact directory.

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/start_isolated_runtime.ps1 -EnvFile .env.docker -DownFirst
```

What it does:
1. Creates a new folder under `runtime-projects/<timestamp-random>/`.
2. Temporarily overrides `AGENTX_HOST_REPO_ROOT` for this startup only.
3. Starts docker compose with that isolated host mount path.

Useful flags:

```powershell
# inspect generated compose command/path without starting containers
pwsh -NoLogo -NoProfile -File tests/e2e/start_isolated_runtime.ps1 -EnvFile .env.docker -DryRun

# skip image rebuild and just bring stack up
pwsh -NoLogo -NoProfile -File tests/e2e/start_isolated_runtime.ps1 -EnvFile .env.docker -NoBuild
```

## Reset Docker Test Data (AgentX only)

When docker stack has accumulated test data/workers/tickets/runtime cache, run:

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/reset_agentx_test_data.ps1 -EnvFile .env.docker.example
```

What it resets:
1. Truncates AgentX tables in MySQL.
2. Drops generated virtual DB users with configured prefix (default `ax_*`).
3. Flushes Redis.
4. Cleans runtime cache/worktree artifacts under container paths:
   - `/agentx/runtime-data/runtime-env/projects/*`
   - `/agentx/runtime-data/context/*`
   - `/agentx/repo/worktrees/*`

Optional workspace purge (keeps `.git`):

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/reset_agentx_test_data.ps1 -EnvFile .env.docker.example -PurgeWorkspace
```
