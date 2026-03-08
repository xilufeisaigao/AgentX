param(
    [string]$EnvFile = ".env.docker",
    [string]$ArtifactsRoot = "./runtime-projects",
    [string]$ProjectId = "",
    [switch]$DownFirst,
    [switch]$NoBuild,
    [switch]$DryRun
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

function Resolve-AbsolutePathFromRoot([string]$pathValue) {
    if ([string]::IsNullOrWhiteSpace($pathValue)) {
        throw "Path value must not be blank."
    }
    if ([System.IO.Path]::IsPathRooted($pathValue)) {
        return [System.IO.Path]::GetFullPath($pathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $root $pathValue))
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

if ([string]::IsNullOrWhiteSpace($ProjectId)) {
    $ProjectId = (Get-Date).ToString("yyyyMMdd-HHmmss") + "-" + (Get-Random -Minimum 1000 -Maximum 9999)
}

$artifactsRootPath = Resolve-AbsolutePathFromRoot $ArtifactsRoot
$repoRootPath = Join-Path $artifactsRootPath $ProjectId

# Compose on Windows accepts host mount paths more reliably with forward slashes.
$repoRootForCompose = ($repoRootPath -replace "\\", "/")

$composeArgs = @("--env-file", $resolvedEnvFile, "up", "-d")
if (-not $NoBuild) {
    $composeArgs += "--build"
}

Write-Output "[isolated-runtime] env-file: $resolvedEnvFile"
Write-Output "[isolated-runtime] project-id: $ProjectId"
Write-Output "[isolated-runtime] AGENTX_HOST_REPO_ROOT: $repoRootForCompose"

if ($DownFirst) {
    Write-Output "[isolated-runtime] stopping existing stack before startup ..."
    & docker compose --env-file $resolvedEnvFile down --remove-orphans
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose down failed with exit code $LASTEXITCODE"
    }
}

if ($DryRun) {
    Write-Output "[isolated-runtime] dry-run command: docker compose $($composeArgs -join ' ')"
    return
}

New-Item -ItemType Directory -Path $repoRootPath -Force | Out-Null

$hadPreviousOverride = Test-Path Env:AGENTX_HOST_REPO_ROOT
$previousOverride = $env:AGENTX_HOST_REPO_ROOT
$env:AGENTX_HOST_REPO_ROOT = $repoRootForCompose

try {
    & docker compose @composeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose up failed with exit code $LASTEXITCODE"
    }
} finally {
    if ($hadPreviousOverride) {
        $env:AGENTX_HOST_REPO_ROOT = $previousOverride
    } else {
        Remove-Item Env:AGENTX_HOST_REPO_ROOT -ErrorAction SilentlyContinue
    }
}

Write-Output "[isolated-runtime] stack is up."
Write-Output "[isolated-runtime] use this folder for generated project artifacts:"
Write-Output $repoRootPath
