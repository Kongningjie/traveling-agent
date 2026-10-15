param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '请先根据 .env.example 创建 .env 并设置本地密码。'
}

# 仅解析本项目的简单 KEY=value，不执行 .env 中的任何脚本，不打印密码。
$allowedKeys = @('POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER',
    'POSTGRES_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD')
$previousValues = @{}
foreach ($key in $allowedKeys + 'TRAVEL_INTEGRATION_TESTS') {
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$originalLocation = Get-Location
try {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $allowedKeys -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    $env:TRAVEL_INTEGRATION_TESTS = 'true'
    Set-Location -LiteralPath $projectRoot
    docker compose up -d --wait postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL / Redis 启动失败。' }
    & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode --no-transfer-progress `
        '-Dtest=LocalInfrastructureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw '本地基础设施集成测试失败。' }
} finally {
    Set-Location -LiteralPath $originalLocation
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
