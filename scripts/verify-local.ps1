<#
.SYNOPSIS
    验证本地 Compose 中 PostgreSQL、Flyway 和 Redis Session 的真实接线。
.DESCRIPTION
    从根 .env 临时加载允许列表中的简单连接配置，不执行文件内容或打印凭证。
    启动本项目中间件，按 Scope 选择基础设施、账号或旅行存储测试；结束后恢复调用进程环境。
    不调用模型、地图或检索服务，不清理业务表和命名卷。
.EXAMPLE
    .\scripts\verify-local.ps1
#>
param(
    # 可以仅复测账号或旅行存储；默认保留本地基础设施统一验收入口。
    [ValidateSet('All', 'Identity', 'Travel')]
    [string]$Scope = 'All'
)

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
    # 同时保存原值和未配置状态，finally 中可精确还原，而不是把测试配置留给后续任务。
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$originalLocation = Get-Location
try {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $allowedKeys -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    # 普通 Maven verify 默认不连接容器；仅本次进程显式打开集成测试条件。
    $env:TRAVEL_INTEGRATION_TESTS = 'true'
    Set-Location -LiteralPath $projectRoot
    docker compose up -d --wait postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL / Redis 启动失败。' }
    $selectedTest = if ($Scope -eq 'Identity') { '-Dtest=IdentityIntegrationTest' }
        elseif ($Scope -eq 'Travel') { '-Dtest=TravelStorageIntegrationTest' }
        else { '-Dtest=LocalInfrastructureTest,BudgetLedgerIntegrationTest,TravelToolIntegrationTest,IdentityIntegrationTest,TravelStorageIntegrationTest' }
    & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode --no-transfer-progress `
        $selectedTest '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw '本地基础设施集成测试失败。' }
} finally {
    Set-Location -LiteralPath $originalLocation
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
