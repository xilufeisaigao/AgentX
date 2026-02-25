param(
    [string]$BackendBaseUrl = "http://127.0.0.1:18082",
    [int]$FrontendPort = 5173
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "../..")
Set-Location $root

$env:AGENTX_UI_API_BASE = $BackendBaseUrl
$env:AGENTX_UI_PORT = "$FrontendPort"
$env:AGENTX_UI_HOST = "127.0.0.1"

Write-Output "[frontend-demo] backend: $BackendBaseUrl"
Write-Output "[frontend-demo] open: http://127.0.0.1:$FrontendPort"
Write-Output "[frontend-demo] Press Ctrl+C to stop."

if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm command not found. Please install Node.js (with npm) first."
}

if (-not (Test-Path "frontend-demo/node_modules")) {
    Write-Output "[frontend-demo] Installing npm dependencies..."
    npm --prefix frontend-demo install
    if ($LASTEXITCODE -ne 0) {
        throw "npm install failed with exit code $LASTEXITCODE"
    }
}

npm --prefix frontend-demo run dev
