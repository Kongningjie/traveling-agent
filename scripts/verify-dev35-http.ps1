#Requires -Version 7.0
<#
.SYNOPSIS
    P01/P02实际HTTP、Worker及独立PG裁判；默认本地模型替身，不是完整二期验收。
.DESCRIPTION
    每批新建独立PG库与Redis会话命名空间，不停止用户后端或修改.env。
    真实模式须RealModel及本次Authorized，限27b协调角色、两例各一次、全批最多16模型预占。
    不调用高德/Tavily；失败停止后续场景。保留结构化报告，不保存控制台日志。
    本地库执行后删除，真实批次库保留供人工核查；不删除任何开发库/用户卷。
#>
param([switch]$RealModel, [switch]$Authorized)
$ErrorActionPreference = 'Stop'
if ($RealModel -and -not $Authorized) { throw '本批真实HTTP验收必须先明确授权，再使用Authorized。' }
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$keys = @('POSTGRES_HOST','POSTGRES_PORT','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')
if ($RealModel) { $keys += 'DASHSCOPE_API_KEY' }
$runtimeKeys = @('TRAVEL_DEV35_HTTP_ACCEPTANCE','TRAVEL_DEV35_HTTP_REAL','TRAVEL_DEV35_HTTP_STORAGE','TRAVEL_DEV35_HTTP_REPORT')
$previous = @{}
foreach ($key in $keys + $runtimeKeys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key,'Process') }
$mutexName = 'travel-verify-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($root))).Substring(0,16)
$mutex = [Threading.Mutex]::new($false,$mutexName)
$locked = $false; $created = $false
$batch = [Guid]::NewGuid().ToString('N').Substring(0,16)
$database = 'travel_dev35_http_' + $batch
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw '另一个本地验证正在运行。' }
    Set-Location -LiteralPath $root
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $keys -contains $Matches[1]) { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process') }
    }
    if ($env:POSTGRES_HOST -and $env:POSTGRES_HOST -notin @('127.0.0.1','localhost')) { throw 'HTTP_ACCEPTANCE_REQUIRES_LOCAL_POSTGRES' }
    if ($RealModel -and -not $env:DASHSCOPE_API_KEY) { throw 'DASHSCOPE_API_KEY_MISSING' }
    $sourceFiles = @(& git ls-files --cached --others --exclude-standard -- application domain agent infrastructure bootstrap scripts)
    if ($LASTEXITCODE -ne 0) { throw 'ACCEPTANCE_SOURCE_UNAVAILABLE' }
    $parts = foreach ($file in ($sourceFiles | Sort-Object)) { $file + ':' + (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash }
    $configuration = @{ codeCommit = (& git rev-parse HEAD); runtime = 'SPRING_BOOT_TEST_CLASSES'; database = $database;
        sourceFingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($parts -join "`n")))) }
    $report = Join-Path $root ('.runtime/evaluations/dev35-http-' + $batch + '.json')
    $env:TRAVEL_DEV35_HTTP_STORAGE = Join-Path $root ('.runtime/evaluations/dev35-http-' + $batch + '-storage')
    $env:TRAVEL_DEV35_HTTP_REPORT = $report
    $env:TRAVEL_DEV35_HTTP_ACCEPTANCE = 'true'; $env:TRAVEL_DEV35_HTTP_REAL = $RealModel.ToString().ToLowerInvariant()
    & docker compose exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" -T template0 "$1"' sh $database 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'ISOLATED_ACCEPTANCE_DATABASE_CREATE_FAILED' }
    $created = $true; $env:POSTGRES_DB = $database
    $arguments = @('--batch-mode','--no-transfer-progress','-pl','bootstrap','-am','-Dtest=Dev35HttpAcceptanceTest','-Dsurefire.failIfNoSpecifiedTests=false','test')
    if ($RealModel) {
        $quota = Join-Path $root 'config/free-quota.local.yml'
        if (-not (Test-Path -LiteralPath $quota -PathType Leaf)) { throw 'FREE_QUOTA_SNAPSHOT_MISSING' }
        $configuration.quotaSnapshotHash = (Get-FileHash -LiteralPath $quota -Algorithm SHA256).Hash
        $arguments += '-Dspring.config.import=file:' + $quota.Replace('\','/')
    }
    & ./mvnw.cmd @arguments 2>&1 | Select-String '^DEV35_HTTP|^\[INFO\] (Tests run:|BUILD SUCCESS|BUILD FAILURE)|^\[ERROR\] Tests run:'
    $testExit = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'HTTP_ACCEPTANCE_REPORT_MISSING' }
    $result = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    $result | Add-Member -NotePropertyName configuration -NotePropertyValue $configuration
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $report -Encoding utf8
    'REPORT: ' + $report
    $result.rows | Select-Object caseId,mode,status,failedChecks,metrics | ConvertTo-Json -Depth 4 -Compress
    if ($testExit -ne 0 -or @($result.rows).Count -ne 2 -or @($result.rows | Where-Object status -ne 'PASS').Count) { throw 'HTTP_ACCEPTANCE_FAILED_WITH_EVIDENCE' }
} finally {
    # 数据库名固定前缀+本批随机16位，且只有本脚本实际创建的本地替身库才可删除。
    if ($created -and -not $RealModel) {
        if ($database -notmatch '^travel_dev35_http_[a-f0-9]{16}$') { throw 'ACCEPTANCE_DATABASE_CLEANUP_TARGET_INVALID' }
        & docker compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --force "$1"' sh $database 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning 'ISOLATED_ACCEPTANCE_DATABASE_CLEANUP_FAILED' }
    }
    foreach ($key in $previous.Keys) {
        if ($null -eq $previous[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key,$previous[$key],'Process') }
    }
    Set-Location -LiteralPath $original
    if ($locked) { $mutex.ReleaseMutex() }; $mutex.Dispose()
}
