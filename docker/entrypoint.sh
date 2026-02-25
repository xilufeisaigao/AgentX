#!/usr/bin/env sh
set -eu

REPO_ROOT="${AGENTX_WORKSPACE_GIT_REPO_ROOT:-/agentx/repo}"
MAIN_BRANCH="${AGENTX_MERGEGATE_GIT_MAIN_BRANCH:-main}"

mkdir -p "${REPO_ROOT}"

if [ ! -d "${REPO_ROOT}/.git" ]; then
  if ! git -C "${REPO_ROOT}" init -b "${MAIN_BRANCH}" >/dev/null 2>&1; then
    git -C "${REPO_ROOT}" init >/dev/null
    git -C "${REPO_ROOT}" checkout -B "${MAIN_BRANCH}" >/dev/null 2>&1 || true
  fi
  git -C "${REPO_ROOT}" config user.email "${AGENTX_GIT_USER_EMAIL:-agentx-runtime@local}"
  git -C "${REPO_ROOT}" config user.name "${AGENTX_GIT_USER_NAME:-AgentX Runtime}"
  if [ ! -f "${REPO_ROOT}/README.md" ]; then
    cat > "${REPO_ROOT}/README.md" <<'EOF'
# AgentX Workspace

This repository was initialized automatically by the AgentX backend container.
EOF
  fi
  if [ -n "$(git -C "${REPO_ROOT}" status --porcelain)" ]; then
    git -C "${REPO_ROOT}" add -A
    git -C "${REPO_ROOT}" commit -m "init workspace baseline" >/dev/null
  fi
fi

if [ -z "${AGENTX_EXECUTION_DEFAULT_BASE_COMMIT:-}" ] || [ "${AGENTX_EXECUTION_DEFAULT_BASE_COMMIT}" = "BASELINE_UNAVAILABLE" ]; then
  BASE_COMMIT="$(git -C "${REPO_ROOT}" rev-parse "${MAIN_BRANCH}" 2>/dev/null || true)"
  if [ -z "${BASE_COMMIT}" ]; then
    BASE_COMMIT="$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || true)"
  fi
  if [ -n "${BASE_COMMIT}" ]; then
    export AGENTX_EXECUTION_DEFAULT_BASE_COMMIT="${BASE_COMMIT}"
  fi
fi

echo "[entrypoint] workspace git root: ${REPO_ROOT}"
echo "[entrypoint] execution base commit: ${AGENTX_EXECUTION_DEFAULT_BASE_COMMIT:-unset}"

exec java ${JAVA_OPTS:-} -jar /app/agentx-backend.jar
