#Requires -Version 7.0
<#
.SYNOPSIS
    DEV-0035分批真实验证；显式选择文本、Harness协议或P01/P02应用场景，均不是整体验收通过。
.DESCRIPTION
    只加载数据库／Redis／DashScope允许项，不打印配置或凭据；不启动Worker，不调用真实旅行工具。
    文本模式三型号各一次；Harness模式每型号最多六次共十八次；应用场景两轮每轮最多四次共八次。
    只保存最小结构化报告；失败即停止剩余批次。应用场景短暂停止并恢复本机backend，不清空卷。
    不修改.env、业务容器开关或原有UNKNOWN；结束还原当前进程环境。
    DiagnosticsOnly仅检查本机角色配置，不调用供应商，也不需要Authorized或模型Key。
#>
param([switch]$Authorized, [switch]$DiagnosticsOnly, [switch]$Harness, [switch]$Conversation,
    [ValidateSet('P01','P02')][string]$CaseId = 'P01', [string]$InspectReport)
$ErrorActionPreference = 'Stop'
if (-not $DiagnosticsOnly -and -not $Authorized) { throw '必须先获得本轮真实调用范围授权，再显式传入Authorized；开关本身不代表用户授权。' }
if (@($DiagnosticsOnly,$Harness,$Conversation).Where({ $_ }).Count -gt 1) { throw 'PROBE_MODES_ARE_EXCLUSIVE' }
if ($InspectReport -and -not $DiagnosticsOnly) { throw 'INSPECT_REQUIRES_LOCAL_DIAGNOSTICS' }
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$backendWasRunning = $false
$keys = @('POSTGRES_HOST','POSTGRES_PORT','POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','DASHSCOPE_API_KEY')
$previous = @{}
foreach ($key in $keys + @('TRAVEL_DEV35_MODEL_PROBE','TRAVEL_DEV35_REPORT','TRAVEL_DELETION_ROOT','TRAVEL_DEV35_CASE','TRAVEL_DEV35_INSPECT_REPORT')) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$mutexName = 'travel-verify-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($root))).Substring(0,16)
$verificationLock = [Threading.Mutex]::new($false,$mutexName)
$lockHeld = $false
try {
    try { $lockHeld = $verificationLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $lockHeld = $true }
    if (-not $lockHeld) { throw '另一个共享数据库验证正在运行，请等待结束。' }
    Set-Location -LiteralPath $root
    foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $keys -contains $Matches[1]) { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process') }
    }
    if (-not $DiagnosticsOnly -and -not $env:DASHSCOPE_API_KEY) { throw 'DASHSCOPE_API_KEY_MISSING' }
    $report = Join-Path $root ('.runtime/evaluations/dev35-probe-' + [Guid]::NewGuid().ToString('N') + '.json')
    $env:TRAVEL_DEV35_MODEL_PROBE = 'true'; $env:TRAVEL_DEV35_REPORT = $report
    $env:TRAVEL_DEV35_CASE = $CaseId
    if ($InspectReport) {
        $resolvedReport = (Resolve-Path -LiteralPath $InspectReport).Path
        if (-not $resolvedReport.StartsWith((Join-Path $root '.runtime/evaluations/'), [StringComparison]::OrdinalIgnoreCase)) { throw 'INSPECT_REPORT_OUTSIDE_EVALUATIONS' }
        $env:TRAVEL_DEV35_INSPECT_REPORT = $resolvedReport
    }
    # Surefire以模块为工作目录；必须使用现有权威删除清单，不能新建空清单绕过恢复保护。
    $env:TRAVEL_DELETION_ROOT = Join-Path $root '.runtime/lifecycle'
    if ($Conversation) {
        $backendWasRunning = -not [string]::IsNullOrWhiteSpace((& docker compose ps --status running -q backend) -join '')
        if ($LASTEXITCODE -ne 0) { throw 'BACKEND_STATE_CHECK_FAILED' }
        if ($backendWasRunning) { & docker compose stop backend 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'BACKEND_ISOLATION_FAILED' } }
    }
    $method = if ($DiagnosticsOnly) { 'recordsLocalBlockersWithoutVendorRequests' } elseif ($Harness) { 'verifiesHarnessToolsAndStructuredOutput' } elseif ($Conversation) { 'verifiesConversationCaseWithOwnedState' } else { 'probesConfiguredRolesWithNoAutomaticRetry' }
    if ($InspectReport) { $method = 'inspectsPersistedRequirementsWithoutVendorRequests' }
    & ./mvnw.cmd --batch-mode --no-transfer-progress -pl bootstrap -am ( '-Dtest=Dev35ModelProbeTest#' + $method ) '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 | Select-String '^DEV35_|^\[INFO\] (Tests run:|BUILD SUCCESS|BUILD FAILURE)'
    $testExit = $LASTEXITCODE
    # 测试中止后也处理AfterEach写出的失败证据；不能先throw丢掉计量及版本指纹。
    if ($testExit -ne 0 -and -not (Test-Path -LiteralPath $report)) { throw 'DEV35_PROBE_HARNESS_FAILED_WITHOUT_REPORT' }
    if (-not (Test-Path -LiteralPath $report)) { throw 'DEV35_PROBE_REPORT_MISSING' }
    Write-Output ('REPORT: ' + $report)
    $result = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
    $sourceFiles = @('bootstrap/src/test/java/com/lawrence/travel/bootstrap/Dev35ModelProbeTest.java','agent/src/main/java/com/lawrence/travel/agent/runtime/DialogueIntentInterpreter.java','agent/src/main/java/com/lawrence/travel/agent/runtime/RestrictedHarnessFactory.java','scripts/verify-dev35-probe.ps1','agent/src/main/java/com/lawrence/travel/agent/runtime/HarnessAgentRuntimeAdapter.java','agent/src/main/java/com/lawrence/travel/agent/runtime/ControlledHarnessCall.java','agent/src/main/java/com/lawrence/travel/agent/runtime/SanitizedAgentFailure.java')
    $sourceFiles += @('application/src/main/java/com/lawrence/travel/application/interaction/DialogueService.java','application/src/main/java/com/lawrence/travel/application/interaction/DialogueQuestionContext.java','application/src/main/java/com/lawrence/travel/application/memory/SessionContextService.java')
    $sourceFiles += @('agent/src/main/java/com/lawrence/travel/agent/runtime/SourceToolkit.java','agent/src/main/java/com/lawrence/travel/agent/runtime/SourceResultBudgetMiddleware.java','agent/src/main/java/com/lawrence/travel/agent/runtime/GuardedDashScopeTransport.java','domain/src/main/java/com/lawrence/travel/domain/execution/control/ContextBudgetDiagnostic.java')
    $sourceFiles += @('agent/src/main/java/com/lawrence/travel/agent/runtime/SourceTools.java')
    $sourceParts = foreach ($file in $sourceFiles) { $file + ':' + (Get-FileHash -LiteralPath (Join-Path $root $file) -Algorithm SHA256).Hash }
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($sourceParts -join "`n"))))
    $configuration = @{ codeCommit = (& git rev-parse HEAD); workingSourceFingerprint = $fingerprint; promptSourceHash = (Get-FileHash -LiteralPath (Join-Path $root $sourceFiles[1]) -Algorithm SHA256).Hash }
    $result | Add-Member -NotePropertyName configuration -NotePropertyValue $configuration
    $result | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $report -Encoding utf8
    if ($Conversation) { $result | Select-Object caseId,programStatus,humanReview,checks,metrics,messageStates,diagnostics,wallMillis | ConvertTo-Json -Compress -Depth 5 }
    else { $result.rows | Select-Object role,model,status,reason,requests,unknownRequests,inputTokens,outputTokens,wallMillis | ConvertTo-Json -Compress }
    if ($testExit -ne 0) { throw 'DEV35_PROBE_HARNESS_FAILED_WITH_EVIDENCE' }
    # JUnit这里只验证执行/记录协议；供应商或应用失败必须让脚本失败，不能用BUILD SUCCESS掩盖。
    if ($Conversation -and $result.programStatus -ne 'PASS') { throw 'DEV35_CONVERSATION_PROGRAM_CHECK_FAILED' }
    if (-not $Conversation -and -not $DiagnosticsOnly -and @($result.rows | Where-Object status -ne 'PASS').Count -gt 0) { throw 'DEV35_REAL_CHECK_FAILED' }
} finally {
    if ($backendWasRunning) { & docker compose start backend 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { Write-Warning 'BACKEND_RESTORE_FAILED' } }
    foreach ($key in $previous.Keys) {
        if ($null -eq $previous[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
    Set-Location -LiteralPath $original
    if ($lockHeld) { $verificationLock.ReleaseMutex() }
    $verificationLock.Dispose()
}
