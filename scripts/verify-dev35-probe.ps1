#Requires -Version 7.0
<#
.SYNOPSIS
    DEV-0035已明确授权的首批模型接入验证，最多三次请求，不是完整场景验收。
.DESCRIPTION
    只加载数据库／Redis／DashScope允许项，不打印配置或凭据；不启动Worker，不调用真实旅行工具。
    三个冻结型号各最多一次，未知用量或已发生的供应商失败停止剩余批次。保留PG计量和结构化结果。
    不修改.env、免费快照、业务容器开关或原有UNKNOWN；结束还原当前进程环境。
    DiagnosticsOnly仅查询本机快照与账本，不调用供应商，也不需要Authorized或模型Key。
#>
param([switch]$Authorized, [switch]$DiagnosticsOnly)
$ErrorActionPreference = 'Stop'
if (-not $DiagnosticsOnly -and -not $Authorized) { throw '必须先获得本轮真实调用范围授权，再显式传入Authorized；开关本身不代表用户授权。' }
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$keys = @('POSTGRES_HOST','POSTGRES_PORT','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','DASHSCOPE_API_KEY')
$previous = @{}
foreach ($key in $keys + @('TRAVEL_DEV35_MODEL_PROBE','TRAVEL_DEV35_REPORT','TRAVEL_DELETION_ROOT')) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    Set-Location -LiteralPath $root
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $keys -contains $Matches[1]) { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process') }
    }
    if (-not $DiagnosticsOnly -and -not $env:DASHSCOPE_API_KEY) { throw 'DASHSCOPE_API_KEY_MISSING' }
    $report = Join-Path $root ('.runtime/evaluations/dev35-probe-' + [Guid]::NewGuid().ToString('N') + '.json')
    $env:TRAVEL_DEV35_MODEL_PROBE = 'true'; $env:TRAVEL_DEV35_REPORT = $report
    # Surefire以模块为工作目录；必须使用现有权威删除清单，不能新建空清单绕过恢复保护。
    $env:TRAVEL_DELETION_ROOT = Join-Path $root '.runtime/lifecycle'
    $quotaFile = Join-Path $root 'config/free-quota.local.yml'
    if (-not (Test-Path -LiteralPath $quotaFile -PathType Leaf)) { throw 'DEV35_QUOTA_FILE_MISSING' }
    # 与删除清单相同，读取原快照的绝对路径；不改余额、核实时间或UNKNOWN历史。
    $method = if ($DiagnosticsOnly) { 'recordsLocalBlockersWithoutVendorRequests' } else { 'probesThreeExactModelsWithNoAutomaticRetry' }
    & ./mvnw.cmd --batch-mode --no-transfer-progress -pl bootstrap -am ( '-Dtest=Dev35ModelProbeTest#' + $method ) ('-Dspring.config.import=file:' + $quotaFile.Replace('\','/')) '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | Select-String '^DEV35_|^\[INFO\] (Tests run:|BUILD SUCCESS|BUILD FAILURE)'
    if ($LASTEXITCODE -ne 0) { throw 'DEV35_PROBE_HARNESS_FAILED' }
    if (-not (Test-Path -LiteralPath $report)) { throw 'DEV35_PROBE_REPORT_MISSING' }
    Write-Output ('REPORT: ' + $report)
    $result = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    $result.rows | Select-Object role,model,status,reason,requests,unknownRequests,inputTokens,outputTokens,wallMillis | ConvertTo-Json -Compress
} finally {
    foreach ($key in $previous.Keys) {
        if ($null -eq $previous[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
    Set-Location -LiteralPath $original
}
