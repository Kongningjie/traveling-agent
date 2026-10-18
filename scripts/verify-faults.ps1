<#
.SYNOPSIS
    运行本地故障矩阵；可选执行后端SIGKILL及PostgreSQL/Redis重启实验。
.DESCRIPTION
    默认仅执行Fault测试范围，供应商均用可控本地替身，不请求任何真实外部API。
    IncludeContainerFaults会启动app服务，短暂中断本项目容器且保留所有命名卷。
    容器实验以随机探针验证存储与健康恢复，业务事务和阶段硬退出由JVM集成实验断言。
    不保存控制台日志，不删除源数据或数据卷，结束只清理本轮精确随机探针。
.EXAMPLE
    .\scripts\verify-faults.ps1 -IncludeContainerFaults
.EXAMPLE
    .\scripts\verify-faults.ps1 -ContainersOnly
#>
param([switch]$IncludeContainerFaults, [switch]$ContainersOnly)
$ErrorActionPreference = 'Stop'
if (-not $ContainersOnly) {
    & (Join-Path $PSScriptRoot 'verify-local.ps1') -Scope Fault
}
if (-not ($IncludeContainerFaults -or $ContainersOnly)) { return }

$oldLocation = Get-Location
$probe = [Guid]::NewGuid().ToString('N')
$table = 'm3_fault_' + $probe
$redisKey = 'traveling-agent:fault:' + $probe
$tableCreated = $false
$redisCreated = $false

<#
.SYNOPSIS
    静默执行固定Compose命令；原始响应不进入日志或异常链。
.PARAMETER Arguments
    固定操作及随机探针，不允许真实凭据参数。
#>
function Invoke-FaultCompose {
    param([string[]]$Arguments)
    $result = & docker compose @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'LOCAL_CONTAINER_FAULT_FAILED' }
    return $result
}

try {
    Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
    Invoke-FaultCompose @('--profile','app','up','-d','--wait') | Out-Null
    Invoke-FaultCompose @('exec','-T','postgres','sh','-c',
        'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"','fault',
        "CREATE TABLE traveling_agent.$table(marker text NOT NULL); INSERT INTO traveling_agent.$table VALUES('$probe');") | Out-Null
    $tableCreated = $true
    Invoke-FaultCompose @('exec','-T','redis','sh','-c',
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli SET "$1" "$2" EX 600','fault',$redisKey,$probe) | Out-Null
    $redisCreated = $true

    Invoke-FaultCompose @('kill','--signal','SIGKILL','backend') | Out-Null
    Invoke-FaultCompose @('up','-d','--wait','backend') | Out-Null
    Write-Output 'PASS: 后端SIGKILL后已重新启动且健康检查通过。'
    Invoke-FaultCompose @('restart','postgres','redis') | Out-Null
    Invoke-FaultCompose @('--profile','app','up','-d','--wait') | Out-Null
    $postgres = Invoke-FaultCompose @('exec','-T','postgres','sh','-c',
        'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"','fault',"SELECT marker FROM traveling_agent.$table")
    $redis = Invoke-FaultCompose @('exec','-T','redis','sh','-c',
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw GET "$1"','fault',$redisKey)
    if (($postgres -join '').Trim() -ne $probe -or ($redis -join '').Trim() -ne $probe) { throw 'RESTART_PROBE_MISSING' }
    Write-Output 'PASS: PostgreSQL/Redis重启后探针数据保留，应用重新健康。'
} finally {
    try {
        # 无论在哪个故障点失败，都尝试恢复服务；绝不down -v或清空其他业务数据。
        try { Invoke-FaultCompose @('--profile','app','up','-d','--wait') | Out-Null }
        finally {
            try {
                if ($tableCreated) { Invoke-FaultCompose @('exec','-T','postgres','sh','-c',
                    'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"','fault',"DROP TABLE IF EXISTS traveling_agent.$table") | Out-Null }
            } finally {
                if ($redisCreated) { Invoke-FaultCompose @('exec','-T','redis','sh','-c',
                    'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli DEL "$1"','fault',$redisKey) | Out-Null }
            }
        }
    } finally { Set-Location -LiteralPath $oldLocation }
}
