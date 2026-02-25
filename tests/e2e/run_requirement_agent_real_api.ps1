$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
Set-Location $root

if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_API_KEY)) {
    throw "AGENTX_REQUIREMENT_LLM_API_KEY is required for real Bailian API test."
}

if (!(Test-Path "target")) {
    New-Item -ItemType Directory -Path "target" | Out-Null
}

$existing = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq "java.exe" -and $_.CommandLine -like "*agentx-backend*"
}
foreach ($p in $existing) {
    Stop-Process -Id $p.ProcessId -Force
}

$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$port = 18080
$stdout = "target/spring-boot-real-llm-$ts.log"
$stderr = "target/spring-boot-real-llm-$ts.err.log"
$pythonLog = "target/python-requirement-agent-real-$ts.log"

$env:AGENTX_REQUIREMENT_LLM_PROVIDER = "bailian"
if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_MODEL)) {
    $env:AGENTX_REQUIREMENT_LLM_MODEL = "qwen3.5-plus-2026-02-15"
}
if ([string]::IsNullOrWhiteSpace($env:AGENTX_REQUIREMENT_LLM_TIMEOUT_MS)) {
    $env:AGENTX_REQUIREMENT_LLM_TIMEOUT_MS = "120000"
}

$procEnv = @{
    AGENTX_REQUIREMENT_LLM_PROVIDER = "bailian"
    AGENTX_REQUIREMENT_LLM_MODEL = $env:AGENTX_REQUIREMENT_LLM_MODEL
    AGENTX_REQUIREMENT_LLM_API_KEY = $env:AGENTX_REQUIREMENT_LLM_API_KEY
    AGENTX_REQUIREMENT_LLM_TIMEOUT_MS = $env:AGENTX_REQUIREMENT_LLM_TIMEOUT_MS
    SERVER_PORT = "$port"
}

Write-Output "[real-e2e] Starting backend with Bailian provider on port $port..."
$proc = Start-Process -FilePath "mvn" -ArgumentList "spring-boot:run" -Environment $procEnv -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr

try {
    $started = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 1
        try {
            $sock = New-Object Net.Sockets.TcpClient
            $sock.Connect("127.0.0.1", $port)
            $sock.Close()
            $started = $true
            break
        } catch {
        }

        if ($proc.HasExited) {
            throw "spring-boot:run exited early with code $($proc.ExitCode)"
        }
    }

    if (-not $started) {
        throw "Backend did not start within timeout."
    }

    Write-Output "[real-e2e] Running real requirement-agent API script..."
    $env:AGENTX_BASE_URL = "http://127.0.0.1:$port"
    $env:AGENTX_HTTP_TIMEOUT_SECONDS = "180"
    python tests/python/requirement_agent_api/run_requirement_agent_real_api.py *> $pythonLog
    if ($LASTEXITCODE -ne 0) {
        Write-Output "[real-e2e] Real API script failed. See log: $pythonLog"
        Get-Content $pythonLog
        throw "Real API script failed with exit code $LASTEXITCODE"
    }

    Write-Output "[real-e2e] Success."
    Write-Output "[real-e2e] python log: $pythonLog"
    Write-Output "[real-e2e] spring stdout: $stdout"
    Write-Output "[real-e2e] spring stderr: $stderr"
    Get-Content $pythonLog
}
finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force
    }

    $agentxJava = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq "java.exe" -and $_.CommandLine -like "*agentx-backend*"
    }
    foreach ($p in $agentxJava) {
        Stop-Process -Id $p.ProcessId -Force
    }
}

