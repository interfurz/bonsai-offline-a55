param(
    [string]$Command = 'status',
    [string]$DataRoot = $env:BONSAI_DATA_ROOT,
    [string]$Python = 'python',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$PipelineArgs
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $DataRoot) { $DataRoot = Join-Path (Split-Path $repoRoot -Parent) 'data' }
$env:PYTHONUTF8 = '1'
Push-Location -LiteralPath $repoRoot
try {
    & $Python -m database_pipeline --data-root $DataRoot $Command @PipelineArgs
    if ($LASTEXITCODE -ne 0) { throw "Database pipeline failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
