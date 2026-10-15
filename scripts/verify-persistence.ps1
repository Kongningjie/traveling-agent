<#
.SYNOPSIS
    重新创建本项目容器，校验 PostgreSQL 与 Redis 的命名卷数据保留。
.DESCRIPTION
    会短暂中断本项目四个容器。写入随机数据库表和 Redis key 后重建容器，
    再校验内容并清理本轮探针；不删除卷，不修改已有业务表，不打印凭证。
    此测试不证明应用阶段恢复或完整备份恢复能力已实现。
.EXAMPLE
    .\scripts\verify-persistence.ps1
#>
param()

$ErrorActionPreference = 'Stop'
$originalLocation = Get-Location
$projectRoot = Split-Path -Parent $PSScriptRoot
$probeId = [Guid]::NewGuid().ToString('N')
$tableName = 'm0_probe_' + $probeId
$redisKey = 'traveling-agent:verification:' + $probeId

<#
.SYNOPSIS
    执行当前项目的 Compose 子命令，并将非零退出码转换为终止错误。
.PARAMETER DockerArguments
    作为独立参数传给 Docker 的固定命令及本轮随机探针值，不接受真实密钥。
.OUTPUTS
    Docker 的标准输出；调用方负责提取并比较探针结果。
#>
function Invoke-Compose {
    param([string[]]$DockerArguments)
    $result = & docker compose @DockerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Compose 验证命令执行失败。' }
    return $result
}

try {
    Set-Location -LiteralPath $projectRoot
    Invoke-Compose -DockerArguments @('--profile', 'app', 'up', '-d', '--wait')
    # 随机名称仅供本轮探针使用；不修改业务表或既有 Redis key。
    $sql = "CREATE TABLE traveling_agent.$tableName (marker text NOT NULL); INSERT INTO traveling_agent.$tableName VALUES ('$probeId');"
    Invoke-Compose -DockerArguments @('exec', '-T', 'postgres', 'sh', '-c',
        'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"', 'probe', $sql)
    Invoke-Compose -DockerArguments @('exec', '-T', 'redis', 'sh', '-c',
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli SET "$1" "$2" EX 600', 'probe', $redisKey, $probeId)

    # 重新创建本项目四个容器，保留命名卷，比仅重启进程更严格。
    Invoke-Compose -DockerArguments @('--profile', 'app', 'up', '-d', '--force-recreate', '--wait')
    $pgValue = Invoke-Compose -DockerArguments @('exec', '-T', 'postgres', 'sh', '-c',
        'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"',
        'probe', "SELECT marker FROM traveling_agent.$tableName;")
    $redisValue = Invoke-Compose -DockerArguments @('exec', '-T', 'redis', 'sh', '-c',
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw GET "$1"', 'probe', $redisKey)
    if (($pgValue -join '').Trim() -ne $probeId) { throw 'PostgreSQL 探针未恢复。' }
    if (($redisValue -join '').Trim() -ne $probeId) { throw 'Redis 探针未恢复。' }
    Write-Output 'PASS: PostgreSQL and Redis data survive container recreation with named volumes.'
} finally {
    try {
        # 仅清理本轮生成的随机探针，保留所有业务数据与命名卷。
        Invoke-Compose -DockerArguments @('exec', '-T', 'postgres', 'sh', '-c',
            'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
            'probe', "DROP TABLE IF EXISTS traveling_agent.$tableName;") | Out-Null
        Invoke-Compose -DockerArguments @('exec', '-T', 'redis', 'sh', '-c',
            'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli DEL "$1"', 'probe', $redisKey) | Out-Null
    } finally {
        Set-Location -LiteralPath $originalLocation
    }
}
