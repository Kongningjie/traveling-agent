#Requires -Version 7.0
<#
.SYNOPSIS
    停止本项目后端后备份PostgreSQL和必要文件；或在临时数据库/文件卷验证恢复。
.DESCRIPTION
    不恢复覆盖源库。备份期间所有宿主机执行器也必须停止，使用-Quiesced明确此运行前提。
    Directory必须是新目录；备份含私有业务数据，须保存在受限本地位置，不能提交Git。
    默认备份agent-files卷artifacts子目录；FilesDirectory仅供本机非容器运行使用。
    不备份Redis会话、凭据、Harness临时工作区。不保留控制台输出或临时Docker日志。
.EXAMPLE
    .\scripts\backup-local.ps1 -Action Backup -Directory .runtime/backups/manual-001 -Quiesced
.EXAMPLE
    .\scripts\backup-local.ps1 -Action Verify -Directory .runtime/backups/manual-001
#>
param(
    [Parameter(Mandatory)][ValidateSet('Backup', 'Verify')][string]$Action,
    [Parameter(Mandatory)][string]$Directory,
    [string]$FilesDirectory,
    [switch]$Quiesced
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$destination = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($Directory, $root))
$tempId = [Guid]::NewGuid().ToString('N')
$temporaryDatabase = 'restore_' + $tempId
$temporaryVolume = 'travel-restore-' + $tempId
$dumpInContainer = '/tmp/travel-backup-' + $tempId + '.dump'
$oldLocation = Get-Location
$backendWasRunning = $false
$databaseCreated = $false
$volumeCreated = $false

<#
.SYNOPSIS
    静默执行Docker，只返回调用方需要的结果；错误不回显可能含配置的原始输出。
.PARAMETER Arguments
    固定命令与非敏感路径，不含密码、令牌或Key。
#>
function Invoke-Docker {
    param([string[]]$Arguments)
    $result = & docker @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'LOCAL_BACKUP_DOCKER_FAILED' }
    return $result
}
<#
.SYNOPSIS
    执行固定SQL，凭据仅由容器自身环境读取；不输出行正文。
.PARAMETER Sql
    固定表名/随机数据库名生成的SQL，不接受用户内容。
.PARAMETER Database
    空值使用容器配置的源数据库，否则只用内部随机临时数据库。
#>
function Invoke-Sql {
    param([string]$Sql, [string]$Database = '')
    return Invoke-Docker @('compose','exec','-T','postgres','sh','-c',
        'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "${2:-$POSTGRES_DB}" -Atc "$1"', 'backup', $Sql, $Database)
}
<#
.SYNOPSIS
    对每张业务表排序后计算内容指纹和行数；只保存指纹，不导出正文至对话或日志。
.PARAMETER Database
    源库或临时恢复库。
#>
function Get-Fingerprints {
    param([string]$Database = '')
    $tables = Invoke-Sql "SELECT tablename FROM pg_tables WHERE schemaname='traveling_agent' ORDER BY tablename" $Database
    $result = [ordered]@{}
    foreach ($table in $tables) {
        $table = $table.ToString().Trim()
        if ($table -notmatch '^[a-z][a-z0-9_]*$') { throw 'INVALID_BACKUP_TABLE' }
        $query = "SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,E'\n' ORDER BY row_to_json(t)::text),'')) FROM traveling_agent.$table t"
        $result[$table] = ((Invoke-Sql $query $Database) -join '').Trim()
    }
    return $result
}

try {
    Set-Location -LiteralPath $root
    if ($Action -eq 'Backup') {
        if (-not $Quiesced) { throw '先停止所有宿主机执行器，再使用-Quiesced；脚本会另行停止容器后端。' }
        if (Test-Path -LiteralPath $destination) { throw '备份目标必须是新目录，禁止覆盖。' }
        # 凭据/源库路径不打印；备份只能在全部应用写入停止后进行，避免DB与文件时间点不一致。
        $running = Invoke-Docker @('compose','ps','--status','running','-q','backend')
        $backendWasRunning = -not [string]::IsNullOrWhiteSpace(($running -join ''))
        if ($backendWasRunning) { Invoke-Docker @('compose','stop','backend') | Out-Null }
        [IO.Directory]::CreateDirectory($destination) | Out-Null
        Invoke-Docker @('compose','exec','-T','postgres','sh','-c',
            'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-acl -f "$1"', 'backup', $dumpInContainer) | Out-Null
        Invoke-Docker @('compose','cp',"postgres:${dumpInContainer}",(Join-Path $destination 'postgres.dump')) | Out-Null
        if ($FilesDirectory) {
            $source = [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($FilesDirectory, $root))
            if (-not (Test-Path -LiteralPath $source -PathType Container) -or $destination.Equals($source, [StringComparison]::OrdinalIgnoreCase) -or $destination.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'INVALID_BACKUP_FILE_ROOT' }
            # 只归档本存储的users树，排除未完成文件和环境配置，避免误选根目录时带入.env。
            Invoke-Docker @('run','--rm','--network','none','--mount',"type=bind,source=$source,target=/source,readonly",'--mount',"type=bind,source=$destination,target=/backup",'postgres:17.11-bookworm','sh','-c',
                'if [ -d /source/users ]; then exec tar -C /source --exclude="*.pending" --exclude=".env" --exclude=".env.*" -cf /backup/files.tar users; else exec tar -cf /backup/files.tar -T /dev/null; fi') | Out-Null
        } else {
            Invoke-Docker @('run','--rm','--network','none','--mount','type=volume,source=traveling-agent_agent-files,target=/source,readonly','--mount',"type=bind,source=$destination,target=/backup",'postgres:17.11-bookworm','sh','-c',
                'if [ -d /source/artifacts/users ]; then exec tar -C /source --exclude="*.pending" --exclude=".env" --exclude=".env.*" -cf /backup/files.tar artifacts/users; else exec tar -cf /backup/files.tar -T /dev/null; fi') | Out-Null
        }
        $manifest = [ordered]@{ version = 1; fileLayout = $(if ($FilesDirectory) { 'root' } else { 'artifacts' }); tables = (Get-Fingerprints); dumpHash = (Get-FileHash (Join-Path $destination 'postgres.dump') -Algorithm SHA256).Hash; filesHash = (Get-FileHash (Join-Path $destination 'files.tar') -Algorithm SHA256).Hash }
        $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destination 'manifest.json') -Encoding utf8
        Write-Output 'PASS: 本地静止备份完成，数据库与必要文件已记录完整性指纹。'
    } else {
        $manifest = Get-Content -LiteralPath (Join-Path $destination 'manifest.json') -Raw | ConvertFrom-Json
        if ($manifest.version -ne 1 -or $manifest.fileLayout -notin @('root','artifacts') -or (Get-FileHash (Join-Path $destination 'postgres.dump') -Algorithm SHA256).Hash -ne $manifest.dumpHash -or (Get-FileHash (Join-Path $destination 'files.tar') -Algorithm SHA256).Hash -ne $manifest.filesHash) { throw 'BACKUP_HASH_MISMATCH' }
        Invoke-Sql "CREATE DATABASE $temporaryDatabase" | Out-Null; $databaseCreated = $true
        Invoke-Docker @('compose','cp',(Join-Path $destination 'postgres.dump'),"postgres:${dumpInContainer}") | Out-Null
        Invoke-Docker @('compose','exec','-T','postgres','sh','-c',
            'exec pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$1" "$2"', 'restore', $temporaryDatabase, $dumpInContainer) | Out-Null
        $actual = Get-Fingerprints $temporaryDatabase
        if ($actual.Count -ne @($manifest.tables.PSObject.Properties).Count) { throw 'RESTORE_TABLES_MISMATCH' }
        foreach ($table in $actual.Keys) { if ($manifest.tables.$table -ne $actual[$table]) { throw 'RESTORE_CONTENT_MISMATCH' } }
        Invoke-Docker @('volume','create',$temporaryVolume) | Out-Null; $volumeCreated = $true
        Invoke-Docker @('run','--rm','--network','none','--mount',"type=volume,source=$temporaryVolume,target=/restored",'--mount',"type=bind,source=$destination,target=/backup,readonly",'postgres:17.11-bookworm','tar','-C','/restored','-xf','/backup/files.tar') | Out-Null
        # 在恢复库的文件引用上逐个核对字节数和SHA256，不用原始卷来冒充恢复成功。
        $references = Invoke-Sql "SELECT file_key||'|'||hash||'|'||size::text FROM traveling_agent.retained_artifact WHERE kind='FILE' ORDER BY id" $temporaryDatabase
        foreach ($reference in $references) {
            $parts = $reference.ToString().Split('|')
            if ($parts.Count -ne 3 -or $parts[0] -notmatch '^users/([0-9a-f-]{36})/tasks/([0-9a-f-]{36})/runs/([0-9a-f-]{36})/([0-9a-f-]{36})\.bin$' -or $parts[1] -notmatch '^[0-9a-f]{64}$' -or $parts[2] -notmatch '^\d+$') { throw 'RESTORE_REFERENCE_INVALID' }
            $file = '/restored/' + $(if ($manifest.fileLayout -eq 'artifacts') { 'artifacts/' } else { '' }) + $parts[0]
            $hashResult = Invoke-Docker @('run','--rm','--network','none','--mount',"type=volume,source=$temporaryVolume,target=/restored,readonly",'postgres:17.11-bookworm','sh','-c','test "$(wc -c < "$1")" = "$3" && test "$(sha256sum "$1" | cut -d " " -f1)" = "$2"', 'verify', $file, $parts[1], $parts[2])
        }
        Write-Output 'PASS: 临时数据库逐表内容与恢复文件逐引用哈希一致；源库和原文件卷未覆盖。'
    }
} finally {
    # 临时资源始终使用脚本生成的精确随机名称；不使用down -v，也不删除源数据。
    try {
        try { Invoke-Docker @('compose','exec','-T','postgres','rm','-f',$dumpInContainer) | Out-Null }
        finally {
            try { if ($databaseCreated) { Invoke-Sql "DROP DATABASE $temporaryDatabase WITH (FORCE)" | Out-Null } }
            finally {
                try { if ($volumeCreated) { Invoke-Docker @('volume','rm',$temporaryVolume) | Out-Null } }
                finally { if ($backendWasRunning) { Invoke-Docker @('compose','start','backend') | Out-Null } }
            }
        }
    } finally { Set-Location -LiteralPath $oldLocation }
}
