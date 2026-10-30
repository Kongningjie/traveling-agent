#Requires -Version 7.0
<#
.SYNOPSIS
    会话或需求回归的实际HTTP、Worker及独立PG裁判；默认本地模型替身，不是完整二期验收。
.DESCRIPTION
    每批新建独立PG库与Redis会话命名空间，不停止用户后端或修改.env。
    默认Conversation选择P01/P02各一次，Requirements选择独立R01四轮，不改封存24例。
    DocumentReferences选择独立R02/R03，自然语言正文与documentIds分离；不回写原封存用例。
    Documents选择P07/P19各一次；需本地已有精确解析镜像，使用本批原件根和临时认证，不修改.env或接管用户runner。
    真实模式须RealModel及本次Authorized，使用已配置协调型号，全批最多16模型预占。
    TripDemo使用Flash理解/调查/规划和Max审核，最多30模型预占、两家资料工具合计20次。
    UnderstandOnly只诊断首条输入，最多4次协调模型请求且关闭高德/Tavily；其他非TripDemo批次也不调用这些工具。失败停止后续场景。保留结构化报告，不保存控制台日志。
    ToolProbe仅定位一次指定官方页的Tavily原文安全检查，不调用模型/高德；PASS仅表示诊断完成，不代表工具内容通过或旅行闭环通过。
    本地库执行后删除，真实批次库保留供人工核查；不删除任何开发库/用户卷。
#>
param([switch]$RealModel, [switch]$Authorized, [switch]$UnderstandOnly, [switch]$ToolProbe, [ValidateSet('Conversation','Requirements','Documents','DocumentReferences','TripDemo')][string]$Batch = 'Conversation')
$ErrorActionPreference = 'Stop'
if ($RealModel -and -not $Authorized) { throw '本批真实HTTP验收必须先明确授权，再使用Authorized。' }
if ($UnderstandOnly -and $Batch -ne 'TripDemo') { throw 'UNDERSTAND_ONLY_REQUIRES_TRIP_DEMO' }
if ($ToolProbe -and ($Batch -ne 'TripDemo' -or $UnderstandOnly)) { throw 'TOOL_PROBE_REQUIRES_EXCLUSIVE_TRIP_DEMO' }
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$keys = @('POSTGRES_HOST','POSTGRES_PORT','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')
if ($RealModel -and -not $ToolProbe) { $keys += 'DASHSCOPE_API_KEY' }
if ($RealModel -and $ToolProbe) { $keys += 'TAVILY_API_KEY' }
if ($RealModel -and $Batch -eq 'TripDemo' -and -not $UnderstandOnly -and -not $ToolProbe) { $keys += @('AMAP_API_KEY','TAVILY_API_KEY') }
if ($Batch -in @('Documents','DocumentReferences')) { $keys += 'DOCUMENT_PARSER_IMAGE' }
$runtimeKeys = @('TRAVEL_DEV35_HTTP_ACCEPTANCE','TRAVEL_DEV35_TRIP_DEMO','TRAVEL_DEV35_TRIP_UNDERSTAND_ONLY','TRAVEL_DEV35_TRIP_TOOL_PROBE','TRAVEL_DEV35_HTTP_REAL','TRAVEL_DEV35_HTTP_STORAGE','TRAVEL_DEV35_HTTP_REPORT','TRAVEL_DEV35_HTTP_BATCH')
$previous = @{}
foreach ($key in $keys + $runtimeKeys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key,'Process') }
$mutexName = 'travel-verify-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($root))).Substring(0,16)
$mutex = [Threading.Mutex]::new($false,$mutexName)
$locked = $false; $created = $false
$batchId = [Guid]::NewGuid().ToString('N').Substring(0,16)
$database = 'travel_dev35_http_' + $batchId
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (-not $locked) { throw '另一个本地验证正在运行。' }
    Set-Location -LiteralPath $root
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $keys -contains $Matches[1]) { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process') }
    }
    if ($env:POSTGRES_HOST -and $env:POSTGRES_HOST -notin @('127.0.0.1','localhost')) { throw 'HTTP_ACCEPTANCE_REQUIRES_LOCAL_POSTGRES' }
    if ($RealModel -and -not $ToolProbe -and -not $env:DASHSCOPE_API_KEY) { throw 'DASHSCOPE_API_KEY_MISSING' }
    if ($RealModel -and $ToolProbe -and -not $env:TAVILY_API_KEY) { throw 'TAVILY_API_KEY_MISSING' }
    if ($RealModel -and $Batch -eq 'TripDemo' -and -not $UnderstandOnly -and -not $ToolProbe -and (-not $env:AMAP_API_KEY -or -not $env:TAVILY_API_KEY)) { throw 'TRIP_DEMO_TOOL_KEYS_MISSING' }
    if ($Batch -in @('Documents','DocumentReferences')) {
        if ($env:DOCUMENT_PARSER_IMAGE -notmatch '^sha256:[a-f0-9]{64}$') { throw 'PARSER_IMAGE_NOT_CONFIGURED' }
        & docker image inspect $env:DOCUMENT_PARSER_IMAGE --format '{{.Id}}' 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'PARSER_IMAGE_UNAVAILABLE' }
    }
    $sourceFiles = @(& git ls-files --cached --others --exclude-standard -- application domain agent infrastructure bootstrap scripts)
    if ($LASTEXITCODE -ne 0) { throw 'ACCEPTANCE_SOURCE_UNAVAILABLE' }
    $parts = foreach ($file in ($sourceFiles | Sort-Object)) { $file + ':' + (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash }
    $configuration = @{ codeCommit = (& git rev-parse HEAD); runtime = 'SPRING_BOOT_TEST_CLASSES'; database = $database;
        sourceFingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($parts -join "`n")))) }
    if ($Batch -in @('Documents','DocumentReferences')) {
        $configuration.parserImage = $env:DOCUMENT_PARSER_IMAGE
        $configuration.documentFixtureHash = (Get-FileHash -LiteralPath (Join-Path $root 'bootstrap/src/test/resources/evaluation/documents/trip1.txt') -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $report = Join-Path $root ('.runtime/evaluations/dev35-http-' + $batchId + '.json')
    $env:TRAVEL_DEV35_HTTP_STORAGE = Join-Path $root ('.runtime/evaluations/dev35-http-' + $batchId + '-storage')
    $env:TRAVEL_DEV35_HTTP_REPORT = $report
    $env:TRAVEL_DEV35_HTTP_ACCEPTANCE = ($Batch -ne 'TripDemo').ToString().ToLowerInvariant()
    $env:TRAVEL_DEV35_TRIP_DEMO = ($Batch -eq 'TripDemo').ToString().ToLowerInvariant()
    $env:TRAVEL_DEV35_TRIP_UNDERSTAND_ONLY = $UnderstandOnly.ToString().ToLowerInvariant()
    $env:TRAVEL_DEV35_TRIP_TOOL_PROBE = $ToolProbe.ToString().ToLowerInvariant()
    $env:TRAVEL_DEV35_HTTP_REAL = $RealModel.ToString().ToLowerInvariant()
    $env:TRAVEL_DEV35_HTTP_BATCH = $Batch
    & docker compose exec -T postgres sh -c 'createdb -U "$POSTGRES_USER" -T template0 "$1"' sh $database 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'ISOLATED_ACCEPTANCE_DATABASE_CREATE_FAILED' }
    $created = $true; $env:POSTGRES_DB = $database
    $testClass = if ($Batch -eq 'TripDemo') { 'Dev35TripDemoTest' } else { 'Dev35HttpAcceptanceTest' }
    $arguments = @('--batch-mode','--no-transfer-progress','-pl','bootstrap','-am',('-Dtest=' + $testClass),'-Dsurefire.failIfNoSpecifiedTests=false','test')
    & ./mvnw.cmd @arguments 2>&1 | Select-String '^DEV35_HTTP|^\[INFO\] (Tests run:|BUILD SUCCESS|BUILD FAILURE)|^\[ERROR\] Tests run:'
    $testExit = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'HTTP_ACCEPTANCE_REPORT_MISSING' }
    $result = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    $result | Add-Member -NotePropertyName configuration -NotePropertyValue $configuration
    $result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $report -Encoding utf8
    'REPORT: ' + $report
    $result.rows | Select-Object caseId,mode,status,failedChecks,metrics | ConvertTo-Json -Depth 4 -Compress
    $expectedCases = if ($ToolProbe) { @('DEMO-TOOL-PROBE') } elseif ($UnderstandOnly) { @('DEMO-UNDERSTAND-01') } elseif ($Batch -eq 'TripDemo') { @('DEMO-TRIP-01') } elseif ($Batch -eq 'Requirements') { @('R01') } elseif ($Batch -eq 'DocumentReferences') { @('R02','R03') } elseif ($Batch -eq 'Documents') { @('P07','P19') } else { @('P01','P02') }
    if ($testExit -ne 0 -or @($result.rows).Count -ne $expectedCases.Count -or
        (($result.rows.caseId -join ',') -ne ($expectedCases -join ',')) -or
        @($result.rows | Where-Object status -ne 'PASS').Count) { throw 'HTTP_ACCEPTANCE_FAILED_WITH_EVIDENCE' }
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
