param(
    [string]$EnvFile = ".env.docker.example",
    [switch]$PurgeWorkspace
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
Set-Location $root

function Require-Command([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        throw "Required command not found: $name"
    }
}

function Read-EnvValue([string]$filePath, [string]$key, [string]$defaultValue) {
    if (-not (Test-Path $filePath)) {
        return $defaultValue
    }
    $pattern = "^\s*$([Regex]::Escape($key))\s*=\s*(.*)\s*$"
    foreach ($line in Get-Content $filePath) {
        if ($line.TrimStart().StartsWith("#")) {
            continue
        }
        if ($line -match $pattern) {
            $raw = $matches[1].Trim()
            if ($raw.StartsWith('"') -and $raw.EndsWith('"') -and $raw.Length -ge 2) {
                return $raw.Substring(1, $raw.Length - 2)
            }
            return $raw
        }
    }
    return $defaultValue
}

function Invoke-Compose([string[]]$arguments) {
    & docker compose --env-file $resolvedEnvFile @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
}

Require-Command "docker"

$resolvedEnvFile = $EnvFile
if (-not [System.IO.Path]::IsPathRooted($resolvedEnvFile)) {
    $resolvedEnvFile = Join-Path $root $resolvedEnvFile
}
if (-not (Test-Path $resolvedEnvFile)) {
    throw "Env file not found: $resolvedEnvFile"
}
$resolvedEnvFile = (Resolve-Path $resolvedEnvFile).Path

$dbName = Read-EnvValue $resolvedEnvFile "AGENTX_DB_NAME" "agentx_backend"
$mysqlRootPassword = Read-EnvValue $resolvedEnvFile "MYSQL_ROOT_PASSWORD" "agentx-root"
$redisPassword = Read-EnvValue $resolvedEnvFile "AGENTX_REDIS_PASSWORD" "agentx-redis"
$dbAccountPrefix = Read-EnvValue $resolvedEnvFile "AGENTX_WORKFORCE_RUNTIME_ENVIRONMENT_DB_ACCOUNT_USERNAME_PREFIX" "ax"
$hostRepoRootRaw = Read-EnvValue $resolvedEnvFile "AGENTX_HOST_REPO_ROOT" "./runtime-projects/default-repo"

Write-Output "[reset] checking docker compose services ..."
Invoke-Compose @("ps")

$truncateTables = @(
    "task_run_events",
    "task_runs",
    "task_context_snapshots",
    "git_workspaces",
    "work_task_dependencies",
    "work_tasks",
    "work_modules",
    "ticket_events",
    "tickets",
    "requirement_doc_versions",
    "requirement_docs",
    "worker_toolpacks",
    "workers",
    "toolpacks",
    "sessions"
)
$sqlCommands = @("SET FOREIGN_KEY_CHECKS=0")
foreach ($table in $truncateTables) {
    $sqlCommands += "TRUNCATE TABLE ``$table``"
}
$sqlCommands += "SET FOREIGN_KEY_CHECKS=1"
$truncateSql = ($sqlCommands -join "; ")

Write-Output "[reset] truncating AgentX tables in MySQL ..."
Invoke-Compose @(
    "exec", "-T", "mysql",
    "mysql",
    "-uroot",
    "-p$mysqlRootPassword",
    "-D", $dbName,
    "-e", $truncateSql
)

$escapedPrefix = ($dbAccountPrefix -replace "'", "''")
$listUserSql = "SELECT user FROM mysql.user WHERE user LIKE CONCAT('${escapedPrefix}', '\\_%');"
$dropUserSqlTemplate = "DROP USER IF EXISTS '{0}'@'%';"

Write-Output "[reset] dropping generated virtual database users ..."
$usersRaw = & docker compose --env-file $resolvedEnvFile exec -T mysql mysql -N -uroot "-p$mysqlRootPassword" -D mysql -e $listUserSql
if ($LASTEXITCODE -ne 0) {
    throw "Failed to query virtual users from mysql.user"
}
$virtualUsers = $usersRaw | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
foreach ($user in $virtualUsers) {
    $escapedUser = $user.Replace("'", "''")
    $dropSql = [string]::Format($dropUserSqlTemplate, $escapedUser)
    Invoke-Compose @(
        "exec", "-T", "mysql",
        "mysql",
        "-uroot",
        "-p$mysqlRootPassword",
        "-D", "mysql",
        "-e", $dropSql
    )
}
Write-Output "[reset] dropped virtual db users: $($virtualUsers.Count)"

Write-Output "[reset] flushing Redis data ..."
Invoke-Compose @(
    "exec", "-T", "redis",
    "sh", "-lc",
    "redis-cli -a '$redisPassword' FLUSHALL"
)

Write-Output "[reset] removing runtime cache/worktree artifacts in backend container ..."
Invoke-Compose @(
    "exec", "-T", "backend",
    "sh", "-lc",
    "rm -rf /agentx/runtime-data/runtime-env/projects/* /agentx/runtime-data/context/* /agentx/repo/worktrees/*"
)

if ($PurgeWorkspace) {
    $hostRepoRoot = $hostRepoRootRaw
    if (-not [System.IO.Path]::IsPathRooted($hostRepoRoot)) {
        $hostRepoRoot = Join-Path $root $hostRepoRoot
    }
    $hostRepoRoot = [System.IO.Path]::GetFullPath($hostRepoRoot)
    if (Test-Path $hostRepoRoot) {
        Write-Output "[reset] purging generated host workspace files (keep .git): $hostRepoRoot"
        Get-ChildItem -Path $hostRepoRoot -Force | Where-Object { $_.Name -ne ".git" } | Remove-Item -Recurse -Force
    } else {
        Write-Output "[reset] workspace path not found, skip purge: $hostRepoRoot"
    }
}

Write-Output "[reset] done. Only AgentX docker data/workspace artifacts were touched."
