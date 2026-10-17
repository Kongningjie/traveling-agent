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
    [ValidateSet('All', 'Identity', 'Travel', 'Engine', 'Candidate', 'Event', 'M1', 'Negotiation', 'Workflow')]
    [string]$Scope = 'All',
    # 在单一作用域中仅复测受影响方法，多个方法以 + 连接；不执行任意命令。
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\+[A-Za-z][A-Za-z0-9_]*)*$')]
    [string]$TestMethod
)

$ErrorActionPreference = 'Stop'
if ($TestMethod -and $Scope -in @('All', 'M1', 'Workflow')) { throw '方法选择只能用于单一测试类作用域。' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '请先根据 .env.example 创建 .env 并设置本地密码。'
}

# 仅解析本项目的简单 KEY=value，不执行 .env 中的任何脚本，不打印密码。
$allowedKeys = @('POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER',
    'POSTGRES_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD')
$previousValues = @{}
foreach ($key in $allowedKeys + @('TRAVEL_INTEGRATION_TESTS', 'TRAVEL_ENGINE_ENABLED')) {
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
    # 集成测试手动推进发现和恢复，避免后台周期与故障注入争抢随机测试事件。
    $env:TRAVEL_ENGINE_ENABLED = 'false'
    Set-Location -LiteralPath $projectRoot
    docker compose up -d --wait postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL / Redis 启动失败。' }
    $selectedTest = if ($Scope -eq 'Identity') { '-Dtest=IdentityIntegrationTest' }
        elseif ($Scope -eq 'Travel') { '-Dtest=TravelStorageIntegrationTest' }
        elseif ($Scope -eq 'Engine') { '-Dtest=ExecutionEngineIntegrationTest' }
        elseif ($Scope -eq 'Candidate') { '-Dtest=CandidateAcceptanceIntegrationTest' }
        elseif ($Scope -eq 'Event') { '-Dtest=RunEventIntegrationTest' }
        elseif ($Scope -eq 'Negotiation') { '-Dtest=NegotiationIntegrationTest' }
        elseif ($Scope -eq 'M1') { '-Dtest=IdentityIntegrationTest,TravelStorageIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,RunEventIntegrationTest' }
        elseif ($Scope -eq 'Workflow') { '-Dtest=IdentityIntegrationTest,TravelStorageIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,RunEventIntegrationTest,NegotiationIntegrationTest' }
        else { '-Dtest=LocalInfrastructureTest,BudgetLedgerIntegrationTest,TravelToolIntegrationTest,IdentityIntegrationTest,TravelStorageIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,RunEventIntegrationTest,NegotiationIntegrationTest' }
    if ($TestMethod) { $selectedTest += "#$TestMethod" }
    & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode --no-transfer-progress `
        $selectedTest '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw '本地基础设施集成测试失败。' }
} finally {
    Set-Location -LiteralPath $originalLocation
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
