$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
Set-Location $root

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
$port = 18081
$stdout = "target/spring-boot-run-$ts.log"
$stderr = "target/spring-boot-run-$ts.err.log"
$requirementPyLog = "target/python-requirement-api-tests-$ts.log"
$requirementAgentPyLog = "target/python-requirement-agent-api-tests-$ts.log"
$ticketPyLog = "target/python-ticket-api-tests-$ts.log"

Write-Output "[e2e] Starting backend on port $port..."
$procEnv = @{
    SERVER_PORT = "$port"
}
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

    Write-Output "[e2e] Running requirement API tests..."
    $env:AGENTX_BASE_URL = "http://127.0.0.1:$port"
    python tests/python/requirement_api/test_requirement_api.py *> $requirementPyLog
    if ($LASTEXITCODE -ne 0) {
        Write-Output "[e2e] Requirement API tests failed. See log: $requirementPyLog"
        Get-Content $requirementPyLog
        throw "Python API tests failed with exit code $LASTEXITCODE"
    }

    Write-Output "[e2e] Running requirement-agent API tests..."
    python tests/python/requirement_agent_api/test_requirement_agent_api.py *> $requirementAgentPyLog
    if ($LASTEXITCODE -ne 0) {
        Write-Output "[e2e] Requirement-agent API tests failed. See log: $requirementAgentPyLog"
        Get-Content $requirementAgentPyLog
        throw "Requirement-agent Python API tests failed with exit code $LASTEXITCODE"
    }

    Write-Output "[e2e] Running ticket API tests..."
    python tests/python/ticket_api/test_ticket_api.py *> $ticketPyLog
    if ($LASTEXITCODE -ne 0) {
        Write-Output "[e2e] Ticket API tests failed. See log: $ticketPyLog"
        Get-Content $ticketPyLog
        throw "Ticket Python API tests failed with exit code $LASTEXITCODE"
    }

    Write-Output "[e2e] Success."
    Write-Output "[e2e] requirement python log: $requirementPyLog"
    Write-Output "[e2e] requirement-agent python log: $requirementAgentPyLog"
    Write-Output "[e2e] ticket python log: $ticketPyLog"
    Write-Output "[e2e] spring stdout: $stdout"
    Write-Output "[e2e] spring stderr: $stderr"
    Get-Content $requirementPyLog
    Get-Content $requirementAgentPyLog
    Get-Content $ticketPyLog
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
