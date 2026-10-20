#Requires -Version 7.0
<#
.SYNOPSIS
    仅向兼容最新删除清单的固定镜像回滚后端；不回退数据库或删除清单。
.DESCRIPTION
    会短暂停止并重建本项目后端，保留PG/Redis/文件卷。
    先停止IDE等宿主机执行器；仅支持带生命周期协议1标签的镜像，拒绝DEV-0032及更旧版本。
    镜像必须预先存在本机，指定不可变sha256 ID。失败保持维护状态，不自动启动旧服务。
.PARAMETER Image
    本机已测试的不可变镜像ID，不允许latest、远程拉取或任意Compose配置。
.EXAMPLE
    .\scripts\rollback-local.ps1 -Image sha256:<64位镜像摘要>
#>
param([Parameter(Mandatory)][ValidatePattern('^sha256:[a-f0-9]{64}$')][string]$Image)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$original=Get-Location
$override=Join-Path $root ('.runtime/rollback-'+[guid]::NewGuid().ToString('N')+'.yaml')
$deletionsRoot=Join-Path $root '.runtime/lifecycle'
. (Join-Path $PSScriptRoot 'backup-deletions.ps1')
try {
    Set-Location -LiteralPath $root
    $protocol=(& docker image inspect --format '{{index .Config.Labels "com.traveling-agent.lifecycle"}}' $Image 2>&1)-join ''
    if($LASTEXITCODE -ne 0 -or $protocol.Trim() -ne '1'){throw 'ROLLBACK_INCOMPATIBLE_LIFECYCLE'}
    $head=Read-DeletionHead
    $db=(& docker compose exec -T postgres sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT instance::text||chr(124)||sequence::text||chr(124)||hash FROM traveling_agent.lifecycle_state WHERE id=1 AND initialized"' 2>&1)-join ''
    if($LASTEXITCODE -ne 0 -or $db.Trim() -ne "$($head.instance)|$($head.sequence)|$($head.hash)"){throw 'ROLLBACK_RESTORE_REQUIRED'}
    [IO.Directory]::CreateDirectory((Split-Path -Parent $override)) | Out-Null
    [IO.File]::WriteAllText($override,"services:`n  backend:`n    image: $Image`n    pull_policy: never`n",[Text.UTF8Encoding]::new($false))
    & docker compose stop backend 2>&1 | Out-Null
    if($LASTEXITCODE -ne 0){throw 'ROLLBACK_STOP_FAILED'}
    & docker compose -f compose.yaml -f $override up -d --no-build --wait backend 2>&1 | Out-Null
    if($LASTEXITCODE -ne 0){& docker compose stop backend 2>&1 | Out-Null;throw 'ROLLBACK_START_FAILED_MAINTENANCE'}
    Write-Output 'PASS: 兼容镜像启动并健康，数据库及独立最新删除清单未回退。'
}finally{
    if(Test-Path -LiteralPath $override){Remove-Item -LiteralPath $override}
    Set-Location -LiteralPath $original
}
