# AgentX Docker Runtime Guide

This deployment keeps backend, MySQL, and Redis inside Docker containers.
The host only needs to provide one mounted workspace directory for generated project code.

## 1. Prepare Environment File

1. Copy `.env.docker.example` to `.env.docker`.
2. Set `AGENTX_HOST_REPO_ROOT` to the host path where generated code should be stored.
3. Fill required secrets (no plaintext fallback in source config):
   - `MYSQL_ROOT_PASSWORD`
   - `AGENTX_DB_PASSWORD`
   - `AGENTX_REDIS_PASSWORD`
4. LLM defaults are `mock` for both requirement and worker runtime.
   - Recommended flow: start in `mock`, then configure real LLM from frontend initialization panel.
   - The backend now supports runtime LLM apply without restart via:
     - `GET /api/v0/runtime/llm-config`
     - `POST /api/v0/runtime/llm-config:test`
     - `POST /api/v0/runtime/llm-config:apply`
   - If you still prefer env-based startup:
     - `AGENTX_REQUIREMENT_LLM_PROVIDER=bailian`
     - `AGENTX_WORKER_RUNTIME_LLM_PROVIDER=bailian`
     - `AGENTX_REQUIREMENT_LLM_API_KEY=<your key>`
     - `AGENTX_WORKER_RUNTIME_LLM_API_KEY=<your key>`
5. Optional worker pool cleanup tuning:
   - `AGENTX_WORKFORCE_WORKER_POOL_CLEANUP_MAX_WORKERS_TOTAL`
   - `AGENTX_WORKFORCE_WORKER_POOL_CLEANUP_MIN_IDLE_SECONDS`
   - `AGENTX_WORKFORCE_WORKER_POOL_CLEANUP_MAX_DISABLE_PER_POLL`
   - `AGENTX_WORKFORCE_WORKER_POOL_CLEANUP_STRATEGY=oldest_idle|least_used`
6. Optional database virtual account provisioning:
   - Enabled by default: `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_ENABLED=true`
   - Built-in MySQL account provisioning uses:
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MYSQL_HOST`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MYSQL_PORT`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MYSQL_DATABASE`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MYSQL_ADMIN_USERNAME`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MYSQL_ADMIN_PASSWORD`
   - Built-in PostgreSQL account provisioning uses:
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_POSTGRESQL_HOST`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_POSTGRESQL_PORT`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_POSTGRESQL_DATABASE`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_POSTGRESQL_ADMIN_DATABASE`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_POSTGRESQL_ADMIN_USERNAME`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_POSTGRESQL_ADMIN_PASSWORD`
   - Built-in Redis ACL user provisioning uses:
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_REDIS_HOST`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_REDIS_PORT`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_REDIS_DATABASE`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_REDIS_ADMIN_USERNAME`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_REDIS_ADMIN_PASSWORD`
   - Built-in MongoDB user provisioning uses:
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MONGODB_HOST`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MONGODB_PORT`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MONGODB_DATABASE`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MONGODB_ADMIN_DATABASE`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MONGODB_ADMIN_USERNAME`
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_MONGODB_ADMIN_PASSWORD`
   - For SQLServer/Oracle or custom control path, configure command templates JSON:
     - `AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_COMMAND_TEMPLATES_JSON`
     - Template placeholders: `${provider}`, `${username}`, `${password}`, `${database}`, `${host}`, `${port}`, `${admin_username}`, `${admin_password}`, `${session_id}`, `${worker_id}`
7. Optional clone-repo publish settings:
   - `AGENTX_DELIVERY_CLONE_PUBLISH_PUBLIC_BASE=git://127.0.0.1:19418`
   - `AGENTX_DELIVERY_CLONE_PUBLISH_RETENTION_HOURS=72`
   - `AGENTX_GIT_EXPORT_PORT=19418`
8. Optional default worker profile bootstrap:
   - `AGENTX_WORKFORCE_BOOTSTRAP_DEFAULT_WORKERS=true`
   - On backend startup this creates common READY worker profiles (idempotent):
     - `WRK-BOOT-JAVA-CORE` -> `TP-GIT-2`,`TP-JAVA-17`,`TP-JAVA-21`,`TP-MAVEN-3`
     - `WRK-BOOT-JAVA-DB` -> `TP-GIT-2`,`TP-JAVA-17`,`TP-JAVA-21`,`TP-MAVEN-3`,`TP-MYSQL-8`
     - `WRK-BOOT-PYTHON-AUX` -> `TP-GIT-2`,`TP-PYTHON-3_11`
   - Compatibility note:
     - runtime now treats `TP-JAVA-21` as satisfying `TP-JAVA-17` task requirements
     - planner output may still contain either id, both are accepted by the default Java worker profiles

## 2. Start Stack

```bash
docker compose --env-file .env.docker up -d --build
```

Recommended for daily development/testing (isolated artifacts per startup):

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/start_isolated_runtime.ps1 -EnvFile .env.docker -DownFirst
```

This command creates a fresh host artifact directory under `runtime-projects/<timestamp-random>/`
and injects it as `AGENTX_HOST_REPO_ROOT` only for that startup.

Services:
1. `backend` at `http://127.0.0.1:${AGENTX_BACKEND_PORT}` (default `18082`)
2. `mysql` at `127.0.0.1:${AGENTX_MYSQL_PORT}` (default `13306`)
3. `redis` at `127.0.0.1:${AGENTX_REDIS_PORT}` (default `16379`)
4. `git-export` at `git://127.0.0.1:${AGENTX_GIT_EXPORT_PORT}` (default `19418`)

Validated on 2026-03-09:
1. `SES-365c1b01de6a44bfadc5bc0ae499bed2` reached `complete` and published a clone repo.
2. `SES-8c9b63dae1454b43ad435e3c2cdbe155` reached `complete` and published a clone repo.
3. Both published repos were cloned on the host, passed `mvn test`, and returned the expected HTTP response after startup.

## 3. Check Health

```bash
docker compose ps
docker compose logs -f backend
```

The backend entrypoint automatically:
1. Initializes the mounted workspace template repository if missing.
2. Creates an initial baseline commit.
3. Exports `AGENTX_EXECUTION_DEFAULT_BASE_COMMIT` from current `HEAD` when unset.
4. Session creation automatically bootstraps one `tmpl.init.v0` task (INIT gate).

## 4. Where Generated Code Is Stored

All runtime code output and git worktrees are under the host path configured by:

`AGENTX_HOST_REPO_ROOT`

Default path:

`./runtime-projects/default-repo`

Session repository layout:

1. Session git repository root: `sessions/<session-id>/repo/`
2. Run worktree path (inside that session repo): `worktrees/<session-id>/<run-id>/`

Runtime environment manifests are written under Docker volume `runtime_data`, for example:

`/agentx/runtime-data/runtime-env/projects/<session-id>/<fingerprint>/environment.json`

If task toolpacks include database providers (for example `TP-MYSQL-8`), `environment.json` includes `database_accounts`.

## 5. Publish Clone URL For Users

After a session has produced code, call:

```bash
curl -X POST "http://127.0.0.1:${AGENTX_BACKEND_PORT}/api/v0/sessions/<session_id>/delivery/clone-repo"
```

The response includes:
1. `clone_url`
2. `clone_command` (directly executable)
3. `expires_at` (temporary repo retention deadline)

If `AGENTX_DELIVERY_CLONE_PUBLISH_PUBLIC_BASE` is configured to `git://127.0.0.1:${AGENTX_GIT_EXPORT_PORT}`, users can clone directly via that URL.
Expired temporary repositories are cleaned automatically by backend scheduler.

## 6. Stop and Clean

Stop services:

```bash
docker compose --env-file .env.docker down
```

Stop and remove MySQL/Redis/runtime volumes:

```bash
docker compose --env-file .env.docker down -v
```

## 7. One-shot Test Data Reset (Without Recreating Containers)

```powershell
pwsh -NoLogo -NoProfile -File tests/e2e/reset_agentx_test_data.ps1 -EnvFile .env.docker
```

This command only resets AgentX runtime data and test artifacts in the docker stack.
