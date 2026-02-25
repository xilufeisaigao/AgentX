param(
    [int]$BackendPort = 18082,
    [switch]$SkipMavenTests,
    [switch]$SkipApiE2E,
    [switch]$EnableRealLlm
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
Set-Location $root

if (!(Test-Path "target")) {
    New-Item -ItemType Directory -Path "target" | Out-Null
}

$timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$summary = New-Object System.Collections.Generic.List[string]

function Add-Summary([string]$line) {
    $summary.Add($line)
}

function Require-Command([string]$name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        throw "Required command not found: $name"
    }
}

function Test-TcpPort([string]$hostname, [int]$port) {
    try {
        $client = New-Object Net.Sockets.TcpClient
        $async = $client.BeginConnect($hostname, $port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(1500)
        if (-not $ok) {
            $client.Close()
            return $false
        }
        $client.EndConnect($async) | Out-Null
        $client.Close()
        return $true
    }
    catch {
        return $false
    }
}

function Resolve-FreePort([int]$preferredPort) {
    $start = [Math]::Max(1024, $preferredPort)
    for ($port = $start; $port -lt ($start + 50); $port++) {
        if (-not (Test-TcpPort "127.0.0.1" $port)) {
            return $port
        }
    }
    throw "No free port found in range [$start, $($start + 49)]"
}

function Invoke-LoggedCommand([string]$label, [string]$command, [string[]]$arguments, [string]$logPath) {
    Write-Output "[suite] Running: $label"
    & $command @arguments *> $logPath
    if ($LASTEXITCODE -ne 0) {
        Write-Output "[suite] Failed: $label, see log: $logPath"
        Get-Content $logPath
        throw "$label failed with exit code $LASTEXITCODE"
    }
    Add-Summary("[PASS] $label")
}

function Invoke-Git([string]$workingDir, [string[]]$gitArgs) {
    $output = & git @gitArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($gitArgs -join ' ') failed in $workingDir, output=$output"
    }
    return ($output | Out-String).Trim()
}

function Initialize-GitSandbox([string]$sandboxPath) {
    if (Test-Path $sandboxPath) {
        Remove-Item -Recurse -Force $sandboxPath
    }
    New-Item -ItemType Directory -Path $sandboxPath | Out-Null

    Push-Location $sandboxPath
    try {
        Invoke-Git $sandboxPath @("init")
        Invoke-Git $sandboxPath @("config", "user.email", "agentx-suite@test.local")
        Invoke-Git $sandboxPath @("config", "user.name", "AgentX Suite")
        Set-Content -Path (Join-Path $sandboxPath "README.md") -Value "# AgentX Test Sandbox" -Encoding UTF8
        Invoke-Git $sandboxPath @("add", "-A")
        Invoke-Git $sandboxPath @("commit", "-m", "init test sandbox")
        $head = Invoke-Git $sandboxPath @("rev-parse", "HEAD")
        return @{
            root = $sandboxPath
            head = $head
        }
    }
    finally {
        Pop-Location
    }
}

function Stop-AgentxJavaProcesses() {
    $existing = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq "java.exe" -and $_.CommandLine -like "*agentx-backend*"
    }
    foreach ($p in $existing) {
        Stop-Process -Id $p.ProcessId -Force
    }
}

function Resolve-RequirementApiKey([string]$projectRoot) {
    if (-not [string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_API_KEY)) {
        return $env:AGENTX_REQUIREMENT_LLM_API_KEY.Trim()
    }
    return $null
}

Require-Command "mvn"
Require-Command "python"
Require-Command "git"

$resolvedRequirementApiKey = $null
if ($EnableRealLlm) {
    $resolvedRequirementApiKey = Resolve-RequirementApiKey $root
    if ([string]::IsNullOrWhiteSpace($resolvedRequirementApiKey)) {
        throw "AGENTX_REQUIREMENT_LLM_API_KEY is required when -EnableRealLlm is set (no fallback from application.yml)."
    }
    $env:AGENTX_REQUIREMENT_LLM_API_KEY = $resolvedRequirementApiKey
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_BASE_URL)) {
        $env:AGENTX_REQUIREMENT_LLM_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_MODEL)) {
        $env:AGENTX_REQUIREMENT_LLM_MODEL = "qwen3.5-plus-2026-02-15"
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_WORKER_RUNTIME_LLM_API_KEY)) {
        $env:AGENTX_WORKER_RUNTIME_LLM_API_KEY = $resolvedRequirementApiKey
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_WORKER_RUNTIME_LLM_BASE_URL)) {
        $env:AGENTX_WORKER_RUNTIME_LLM_BASE_URL = $env:AGENTX_REQUIREMENT_LLM_BASE_URL
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_WORKER_RUNTIME_LLM_MODEL)) {
        $env:AGENTX_WORKER_RUNTIME_LLM_MODEL = $env:AGENTX_REQUIREMENT_LLM_MODEL
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_AGENT_DECISION_WAIT_SECONDS)) {
        $env:AGENTX_REQUIREMENT_AGENT_DECISION_WAIT_SECONDS = "240"
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_AGENT_DONE_WAIT_SECONDS)) {
        $env:AGENTX_REQUIREMENT_AGENT_DONE_WAIT_SECONDS = "240"
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_TICKET_HTTP_TIMEOUT_SECONDS)) {
        $env:AGENTX_TICKET_HTTP_TIMEOUT_SECONDS = "180"
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_TICKET_EVENT_WAIT_SECONDS)) {
        $env:AGENTX_TICKET_EVENT_WAIT_SECONDS = "240"
    }
    if ([string]::IsNullOrWhiteSpace($env:AGENTX_TICKET_STATUS_WAIT_SECONDS)) {
        $env:AGENTX_TICKET_STATUS_WAIT_SECONDS = "240"
    }
}

if (-not $SkipMavenTests) {
    $mavenLog = "target/full-suite-maven-test-$timestamp.log"
    Invoke-LoggedCommand "maven unit/integration tests" "mvn" @("-q", "test") $mavenLog
}

$backendProc = $null
$springOut = "target/full-suite-spring-boot-$timestamp.log"
$springErr = "target/full-suite-spring-boot-$timestamp.err.log"
$gitSandboxPath = Join-Path $root "target/full-suite-git-$timestamp"
$gitSandbox = Initialize-GitSandbox $gitSandboxPath

try {
    if (-not $SkipApiE2E) {
        $effectiveBackendPort = Resolve-FreePort $BackendPort
        if ($effectiveBackendPort -ne $BackendPort) {
            Write-Output "[suite] Preferred port $BackendPort is busy, switched to $effectiveBackendPort"
        }

        $dbHost = if ([string]::IsNullOrWhiteSpace($env:AGENTX_DB_HOST)) { "127.0.0.1" } else { $env:AGENTX_DB_HOST }
        $dbPort = if ([string]::IsNullOrWhiteSpace($env:AGENTX_DB_PORT)) { 3306 } else { [int]$env:AGENTX_DB_PORT }
        $redisHost = if ([string]::IsNullOrWhiteSpace($env:AGENTX_REDIS_HOST)) { "127.0.0.1" } else { $env:AGENTX_REDIS_HOST }
        $redisPort = if ([string]::IsNullOrWhiteSpace($env:AGENTX_REDIS_PORT)) { 6379 } else { [int]$env:AGENTX_REDIS_PORT }

        if (-not (Test-TcpPort $dbHost $dbPort)) {
            throw "MySQL is not reachable at ${dbHost}:$dbPort"
        }
        if (-not (Test-TcpPort $redisHost $redisPort)) {
            throw "Redis is not reachable at ${redisHost}:$redisPort"
        }

        Stop-AgentxJavaProcesses

        Write-Output "[suite] Starting backend on port $effectiveBackendPort ..."
        $defaultBaseCommit = $gitSandbox.head
        $provider = if ($EnableRealLlm) { "bailian" } else { "mock" }
        $procEnv = @{
            SERVER_PORT = "$effectiveBackendPort"
            AGENTX_REQUIREMENT_LLM_PROVIDER = $provider
            AGENTX_ARCHITECT_LLM_PROVIDER = $provider
            AGENTX_WORKER_RUNTIME_LLM_PROVIDER = $provider
            AGENTX_EXECUTION_DEFAULT_BASE_COMMIT = $defaultBaseCommit
            AGENTX_WORKSPACE_GIT_REPO_ROOT = $gitSandbox.root
            AGENTX_MERGEGATE_GIT_REPO_ROOT = $gitSandbox.root
            AGENTX_WORKER_RUNTIME_REPO_ROOT = $gitSandbox.root
        }
        if ($EnableRealLlm) {
            $procEnv["AGENTX_REQUIREMENT_LLM_API_KEY"] = $resolvedRequirementApiKey
            if (-not [string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_MODEL)) {
                $procEnv["AGENTX_REQUIREMENT_LLM_MODEL"] = $env:AGENTX_REQUIREMENT_LLM_MODEL
            }
            if (-not [string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_BASE_URL)) {
                $procEnv["AGENTX_REQUIREMENT_LLM_BASE_URL"] = $env:AGENTX_REQUIREMENT_LLM_BASE_URL
            }
        }

        $backendProc = Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" -Environment $procEnv -PassThru -RedirectStandardOutput $springOut -RedirectStandardError $springErr

        $started = $false
        for ($i = 0; $i -lt 120; $i++) {
            Start-Sleep -Seconds 1
            if ($backendProc.HasExited) {
                throw "spring-boot:run exited early with code $($backendProc.ExitCode), stderr: $springErr"
            }
            if (Test-TcpPort "127.0.0.1" $effectiveBackendPort) {
                $started = $true
                break
            }
        }
        if (-not $started) {
            throw "Backend did not start within timeout."
        }
        Add-Summary("[PASS] backend startup on port $effectiveBackendPort")

        $env:AGENTX_BASE_URL = "http://127.0.0.1:$effectiveBackendPort"

        $apiTestMatrix = @(
            @{ Label = "python requirement api"; Script = "tests/python/requirement_api/test_requirement_api.py"; Log = "target/full-suite-requirement-api-$timestamp.log" },
            @{ Label = "python requirement-agent api"; Script = "tests/python/requirement_agent_api/test_requirement_agent_api.py"; Log = "target/full-suite-requirement-agent-api-$timestamp.log" },
            @{ Label = "python ticket api"; Script = "tests/python/ticket_api/test_ticket_api.py"; Log = "target/full-suite-ticket-api-$timestamp.log" },
            @{ Label = "python architect auto scheduler flow"; Script = "tests/python/ticket_api/test_architect_auto_scheduler_flow.py"; Log = "target/full-suite-architect-scheduler-$timestamp.log" },
            @{ Label = "python execution worker claim api"; Script = "tests/python/execution_api/test_execution_worker_claim_api.py"; Log = "target/full-suite-execution-api-$timestamp.log" },
            @{ Label = "python workforce auto-provision and lease-recovery"; Script = "tests/python/workforce_runtime/test_auto_provision_and_lease_recovery.py"; Log = "target/full-suite-workforce-runtime-$timestamp.log" }
        )

        foreach ($testCase in $apiTestMatrix) {
            Invoke-LoggedCommand $testCase.Label "python" @($testCase.Script) $testCase.Log
        }
    }

    if ($EnableRealLlm) {
        $realLlmLog = "target/full-suite-real-llm-maven-$timestamp.log"
        $env:AGENTX_REAL_LLM_TEST = "true"
        Invoke-LoggedCommand "real llm smoke tests" "mvn" @("-q", "-Dtest=RealLlmSmokeTest,WorkerRuntimeJavaBackendSmokeTest", "test") $realLlmLog
    }
}
finally {
    if ($backendProc -and -not $backendProc.HasExited) {
        Stop-Process -Id $backendProc.Id -Force
    }
    Stop-AgentxJavaProcesses
}

Write-Output ""
Write-Output "[suite] Completed."
foreach ($line in $summary) {
    Write-Output "  $line"
}
Write-Output "[suite] spring stdout: $springOut"
Write-Output "[suite] spring stderr: $springErr"

