<#
.SYNOPSIS
    验证三个指定 DashScope 型号的 Harness 工具、结构化输出、计量和取消。
.DESCRIPTION
    从根 .env 临时加载允许列表中的简单连接配置，不执行文件内容或打印凭证。
    启动本项目中间件，显式运行Harness 模型测试；结束后恢复调用进程环境。
    会调用真实模型并消耗已确认免费额度；无有效快照即拒绝。不删除真实计量记录。
.EXAMPLE
    .\scripts\verify-models.ps1
#>
param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '请先根据 .env.example 创建 .env 并设置本地密码。'
}

# 仅解析本项目的简单 KEY=value，不执行 .env 中的任何脚本，不打印密码。
$allowedKeys = @('POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER',
    'POSTGRES_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD', 'DASHSCOPE_API_KEY')
$previousValues = @{}
foreach ($key in $allowedKeys + 'TRAVEL_LIVE_MODEL_TESTS') {
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
    $env:TRAVEL_LIVE_MODEL_TESTS = 'true'
    Set-Location -LiteralPath $projectRoot
    docker compose up -d --wait postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL / Redis 启动失败。' }
    & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode --no-transfer-progress `
        '-Dtest=HarnessLiveIntegrationTest' ('-Dspring.config.import=optional:file:' + ((Join-Path $projectRoot 'config/free-quota.local.yml').Replace('\','/'))) '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw '真实模型验证失败，请查看脱敏错误分类；不要自动重试或替换型号。' }
} finally {
    Set-Location -LiteralPath $originalLocation
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
