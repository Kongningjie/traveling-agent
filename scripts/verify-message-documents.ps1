#Requires -Version 7.0
<#
.SYNOPSIS
    在随机独立PG库验证消息附件绑定、权限、幂等和本机HTTP；不调用供应商。
.DESCRIPTION
    只加载数据库及Redis连接白名单，不打印凭据，不停止用户后端，不保存控制台日志。
    测试结束删除本脚本创建的随机数据库；仅本机PG与Redis可用时运行。
#>
param([ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\+[A-Za-z][A-Za-z0-9_]*)*$')][string]$DocumentTest)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$keys = @('POSTGRES_HOST','POSTGRES_PORT','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')
$runtime = @('TRAVEL_INTEGRATION_TESTS','TRAVEL_ENGINE_ENABLED','TRAVEL_EXTERNAL_CALLS_ENABLED','TRAVEL_DOCUMENTS_CLEANUP_ENABLED','TRAVEL_LIFECYCLE_MAINTENANCE_ENABLED','TRAVEL_DELETIONS_ROOT','SPRING_SESSION_REDIS_NAMESPACE')
$previous = @{}
foreach ($key in $keys + $runtime) { $previous[$key] = [Environment]::GetEnvironmentVariable($key,'Process') }
$database = 'travel_document_message_' + [Guid]::NewGuid().ToString('N').Substring(0,16)
$created = $false
try {
    Set-Location -LiteralPath $root
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $keys -contains $Matches[1]) { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process') }
    }
    if ($env:POSTGRES_HOST -and $env:POSTGRES_HOST -notin @('127.0.0.1','localhost')) { throw 'LOCAL_POSTGRES_REQUIRED' }
    if ($env:REDIS_HOST -and $env:REDIS_HOST -notin @('127.0.0.1','localhost')) { throw 'LOCAL_REDIS_REQUIRED' }
    & docker compose exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" -T template0 "$1"' sh $database 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'ISOLATED_DATABASE_CREATE_FAILED' }
    $created = $true
    $env:POSTGRES_DB = $database
    $env:TRAVEL_INTEGRATION_TESTS = 'true'
    $env:TRAVEL_ENGINE_ENABLED = 'false'
    $env:TRAVEL_EXTERNAL_CALLS_ENABLED = 'false'
    $env:TRAVEL_DOCUMENTS_CLEANUP_ENABLED = 'false'
    $env:TRAVEL_LIFECYCLE_MAINTENANCE_ENABLED = 'false'
    $env:TRAVEL_DELETIONS_ROOT = Join-Path $root ('.runtime/' + $database + '/deletions')
    $env:SPRING_SESSION_REDIS_NAMESPACE = 'travel:test:' + $database
    $tests = 'DocumentIntegrationTest#messageAttachmentsPersistAndReplayCannotChangeSelection+messageAttachmentOwnershipReadinessAndLimitsAreAtomic+staleMessageAttachmentPausesBeforeModelAndDeletionHidesName+changedAttachmentBeforeCommitRejectsLateReply+concurrentMessageAttachmentConflictHasOneWinner+httpMessageCarriesDocumentReferencesOutsideUserText+uploadParseAndIdempotencyPersistTogether+httpMultipartAndDownloadRequireOwnerAndCsrf,DialogueIntegrationTest'
    if ($DocumentTest) { $tests = 'DocumentIntegrationTest#' + $DocumentTest }
    & ./mvnw.cmd --batch-mode --no-transfer-progress -pl bootstrap -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlogging.level.root=OFF' test 2>&1 | Select-String '^\[INFO\] (Tests run:|BUILD SUCCESS|BUILD FAILURE)|^\[ERROR\] Tests run:'
    if ($LASTEXITCODE -ne 0) { throw 'MESSAGE_DOCUMENT_TEST_FAILED' }
} finally {
    if ($created) {
        if ($database -notmatch '^travel_document_message_[a-f0-9]{16}$') { throw 'CLEANUP_TARGET_INVALID' }
        & docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$1"' sh $database 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'ISOLATED_DATABASE_CLEANUP_FAILED' }
    }
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key,$previous[$key],'Process') }
    Set-Location -LiteralPath $original
}
