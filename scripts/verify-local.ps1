<#
.SYNOPSIS
    验证本地 Compose 中 PostgreSQL、Flyway 和 Redis Session 的真实接线。
.DESCRIPTION
    从根 .env 临时加载允许列表中的简单连接配置，不执行文件内容或打印凭证。
    启动本项目中间件，按 Scope 选择基础设施、账号或旅行存储测试；结束后恢复调用进程环境。
    不调用模型、地图或检索服务，不清理业务表和命名卷。
.EXAMPLE
    .\scripts\verify-local.ps1
#>
param(
    # 可以仅复测账号或旅行存储；默认保留本地基础设施统一验收入口。
    [ValidateSet('All', 'Identity', 'Travel', 'Engine', 'Candidate', 'Event', 'M1', 'Negotiation', 'Research', 'Planning', 'Workflow', 'Preference', 'Context', 'Artifact', 'Fault', 'Evaluation', 'Dialogue', 'Job', 'Session', 'Document', 'Source', 'Strategy', 'Trace', 'Advice', 'AdviceCommit', 'Support', 'SupportCommit', 'Lifecycle', 'LifecycleCommit')]
    [string]$Scope = 'All',
    # 在单一作用域中仅复测受影响方法，多个方法以 + 连接；不执行任意命令。
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\+[A-Za-z][A-Za-z0-9_]*)*$')]
    [string]$TestMethod
)

$ErrorActionPreference = 'Stop'
if ($TestMethod -and $Scope -in @('All', 'M1', 'Workflow', 'Fault', 'Evaluation','LifecycleCommit')) { throw '方法选择只能用于单一测试类作用域。' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '请先根据 .env.example 创建 .env 并设置本地密码。'
}

# 仅解析本项目的简单 KEY=value，不执行 .env 中的任何脚本，不打印密码。
$allowedKeys = @('POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER',
    'POSTGRES_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD')
$previousValues = @{}
foreach ($key in $allowedKeys + @('TRAVEL_INTEGRATION_TESTS', 'TRAVEL_ENGINE_ENABLED', 'TRAVEL_EXTERNAL_CALLS_ENABLED','TRAVEL_DOCUMENTS_CLEANUP_ENABLED','TRAVEL_LIFECYCLE_MAINTENANCE_ENABLED','TRAVEL_DELETION_ROOT')) {
    # 同时保存原值和未配置状态，finally 中可精确还原，而不是把测试配置留给后续任务。
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$originalLocation = Get-Location
$backendWasRunning = $false
$mutexName = 'travel-verify-' + [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($projectRoot))).Substring(0,16)
$verificationLock = [Threading.Mutex]::new($false,$mutexName)
$lockHeld = $false
try {
    try { $lockHeld = $verificationLock.WaitOne(0) } catch [Threading.AbandonedMutexException] { $lockHeld = $true }
    if (-not $lockHeld) { throw '另一个本地数据库验证正在运行；请等待结束，不并行启动共享库测试。' }
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*([A-Z_]+)=(.*)$' -and $allowedKeys -contains $Matches[1]) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    # 普通 Maven verify 默认不连接容器；仅本次进程显式打开集成测试条件。
    $env:TRAVEL_INTEGRATION_TESTS = 'true'
    # 集成测试手动推进发现和恢复，避免后台周期与故障注入争抢随机测试事件。
    $env:TRAVEL_ENGINE_ENABLED = 'false'
    $env:TRAVEL_EXTERNAL_CALLS_ENABLED = 'false'
    # 本轮测试只清理自身随机用户，禁止维护线程处理其他账号的真实附件。
    $env:TRAVEL_DOCUMENTS_CLEANUP_ENABLED = 'false'
    $env:TRAVEL_LIFECYCLE_MAINTENANCE_ENABLED = 'false'
    $env:TRAVEL_DELETION_ROOT = Join-Path $projectRoot '.runtime/lifecycle'
    Set-Location -LiteralPath $projectRoot
    docker compose up -d --wait postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL / Redis 启动失败。' }
    # 环境变量只控制本次JVM，不能关闭已运行容器的Worker；先隔离共享测试数据库的竞争者。
    $runningBackend = & docker compose ps --status running -q backend
    if ($LASTEXITCODE -ne 0) { throw '无法核对后端运行状态。' }
    $backendWasRunning = -not [string]::IsNullOrWhiteSpace(($runningBackend -join ''))
    if ($backendWasRunning) { docker compose stop backend; if ($LASTEXITCODE -ne 0) { throw '无法暂停容器后端。' } }
    $selectedTest = if ($Scope -eq 'Lifecycle') { '-Dtest=LifecycleIntegrationTest,DeletionLedgerTest,LifecycleCapacityTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'LifecycleCommit') { '-Dtest=LifecycleIntegrationTest,DeletionLedgerTest,LifecycleCapacityTest,LifecycleGateTest,DialogueWorkerTest,SourceReadIntegrationTest,DialogueIntegrationTest,JobIntegrationTest,CandidateAcceptanceIntegrationTest#deletingAcceptedTripRemovesFullGraph,ArtifactIntegrationTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'SupportCommit') { '-Dtest=SupportIntegrationTest,SupportServiceTest,ProblemStateTest,DiagnosticBufferTest,ModuleArchitectureTest,ExecutionEngineIntegrationTest,JobIntegrationTest,SessionContextIntegrationTest,DialogueIntegrationTest,DocumentIntegrationTest,AdviceIntegrationTest,SafetyGateTest,IdentityIntegrationTest' }
        elseif ($Scope -eq 'Support') { '-Dtest=SupportIntegrationTest,SupportServiceTest,ProblemStateTest,DiagnosticBufferTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'AdviceCommit') { '-Dtest=ExecutionEngineIntegrationTest,NegotiationIntegrationTest,AdviceIntegrationTest,AdviceContractTest,PlanningIntegrationTest#uploadedPlanAdviceUsesOriginalCandidateAndAcceptanceGates,CandidateAcceptanceIntegrationTest,TravelJsonTest' }
        elseif ($Scope -eq 'Advice') { '-Dtest=AdviceIntegrationTest,AdviceContractTest,DialogueIntegrationTest,DialogueIntentContractTest,SourceReadIntegrationTest,ModuleArchitectureTest,NegotiationIntegrationTest,CandidateAcceptanceIntegrationTest,SessionContextIntegrationTest,PlanningIntegrationTest#uploadedPlanAdviceUsesOriginalCandidateAndAcceptanceGates' }
        elseif ($Scope -eq 'Trace') { '-Dtest=TracedCompressionTest,SessionContextIntegrationTest,BudgetLedgerIntegrationTest,TravelToolContractTest,SourceToolkitTest,ControlledHarnessCallTest,GuardedTransportTest,GuardedStreamTest,SourceReadIntegrationTest,AgentStrategyTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'Strategy') { '-Dtest=AgentStrategyTest,ResearchIntegrationTest,ExecutionEngineIntegrationTest,PlanningIntegrationTest,SessionContextIntegrationTest,BudgetLedgerIntegrationTest,GuardedTransportTest,GuardedStreamTest,ControlledHarnessCallTest,HarnessContractTest,SourceToolkitTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'Source') { '-Dtest=SourceReadIntegrationTest,SourceToolkitTest,SessionContextIntegrationTest,DocumentIntegrationTest,ControlledHarnessCallTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'Document') { '-Dtest=DocumentLimitsTest,DocumentIntegrationTest,DocumentParserTest,JobIntegrationTest,ArtifactIntegrationTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'Session') { '-Dtest=BackgroundJobTest,TravelToolContractTest,SessionContextIntegrationTest,DialogueIntegrationTest,ContextIntegrationTest,JobIntegrationTest,SafetyGateTest,ControlledHarnessCallTest,DialogueIntentContractTest,GuardedTransportTest,GuardedStreamTest,HarnessContractTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'Job') { '-Dtest=PreferenceIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,IdentityIntegrationTest,JobIntegrationTest,DialogueIntegrationTest,BudgetLedgerIntegrationTest,DialogueWorkerTest,ExecutionResourcesTest,GuardedTransportTest,GuardedStreamTest,ModuleArchitectureTest,ModelCallAdmissionTest,BackgroundJobTest' }
        elseif ($Scope -eq 'Dialogue') { '-Dtest=DialogueIntegrationTest,CandidateAcceptanceIntegrationTest,NegotiationIntegrationTest,RunEventIntegrationTest,ModuleArchitectureTest,DialogueIntentContractTest,DialogueWorkerTest' }
        elseif ($Scope -eq 'Evaluation') { '-Dtest=EvaluationRunnerTest,EvaluationBaselineTest,ModuleArchitectureTest' }
        elseif ($Scope -eq 'Fault') { '-Dtest=ExecutionEngineIntegrationTest,BudgetLedgerIntegrationTest,RunEventIntegrationTest,CandidateAcceptanceIntegrationTest,TravelStorageIntegrationTest,NegotiationIntegrationTest,ResearchIntegrationTest,PlanningIntegrationTest,PreferenceIntegrationTest,ContextIntegrationTest,ArtifactIntegrationTest,ExecutionResourcesTest,GuardedTransportTest,GuardedStreamTest,HarnessContractTest,LocalModelFaultTest' }
        elseif ($Scope -eq 'Artifact') { '-Dtest=ArtifactIntegrationTest' }
        elseif ($Scope -eq 'Context') { '-Dtest=ContextIntegrationTest' }
        elseif ($Scope -eq 'Preference') { '-Dtest=PreferenceIntegrationTest' }
        elseif ($Scope -eq 'Identity') { '-Dtest=IdentityIntegrationTest' }
        elseif ($Scope -eq 'Travel') { '-Dtest=TravelStorageIntegrationTest' }
        elseif ($Scope -eq 'Engine') { '-Dtest=ExecutionEngineIntegrationTest' }
        elseif ($Scope -eq 'Candidate') { '-Dtest=CandidateAcceptanceIntegrationTest' }
        elseif ($Scope -eq 'Event') { '-Dtest=RunEventIntegrationTest' }
        elseif ($Scope -eq 'Negotiation') { '-Dtest=NegotiationIntegrationTest' }
        elseif ($Scope -eq 'Research') { '-Dtest=ResearchIntegrationTest' }
        elseif ($Scope -eq 'Planning') { '-Dtest=PlanningIntegrationTest' }
        elseif ($Scope -eq 'M1') { '-Dtest=IdentityIntegrationTest,TravelStorageIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,RunEventIntegrationTest' }
        elseif ($Scope -eq 'Workflow') { '-Dtest=IdentityIntegrationTest,TravelStorageIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,RunEventIntegrationTest,NegotiationIntegrationTest,ResearchIntegrationTest,PlanningIntegrationTest,PreferenceIntegrationTest,ContextIntegrationTest,ArtifactIntegrationTest' }
        else { '-Dtest=LocalInfrastructureTest,BudgetLedgerIntegrationTest,TravelToolIntegrationTest,IdentityIntegrationTest,TravelStorageIntegrationTest,ExecutionEngineIntegrationTest,CandidateAcceptanceIntegrationTest,RunEventIntegrationTest,NegotiationIntegrationTest,ResearchIntegrationTest,PlanningIntegrationTest,PreferenceIntegrationTest,ContextIntegrationTest,ArtifactIntegrationTest' }
    if ($TestMethod) {
        if ($Scope -eq 'Lifecycle') { $selectedTest = '-Dtest=LifecycleIntegrationTest' }
        if ($Scope -eq 'SupportCommit') { throw 'SupportCommit为多类回归，不接受方法选择。' }
        if ($Scope -eq 'Support') { $selectedTest = '-Dtest=SupportIntegrationTest' }
        if ($Scope -eq 'Dialogue') { $selectedTest = '-Dtest=DialogueIntegrationTest' }
        if ($Scope -eq 'Job') { $selectedTest = '-Dtest=JobIntegrationTest' }
        if ($Scope -eq 'Document') { $selectedTest = '-Dtest=DocumentIntegrationTest' }
        if ($Scope -eq 'Source') { $selectedTest = '-Dtest=SourceReadIntegrationTest' }
        if ($Scope -eq 'Advice') { $selectedTest = '-Dtest=AdviceIntegrationTest' }
        if ($Scope -eq 'AdviceCommit') { throw 'AdviceCommit为多类回归，不接受方法选择。' }
        if ($Scope -eq 'Trace') { throw 'Trace为多类回归，不接受方法选择。' }
        if ($Scope -eq 'Strategy') { $selectedTest = '-Dtest=ResearchIntegrationTest' }
        if ($Scope -eq 'Session') { $selectedTest = '-Dtest=SessionContextIntegrationTest' }
        $selectedTest += "#$TestMethod"
    }
    & (Join-Path $projectRoot 'mvnw.cmd') --batch-mode --no-transfer-progress `
        $selectedTest '-Dsurefire.failIfNoSpecifiedTests=false' test
    if ($LASTEXITCODE -ne 0) { throw '本地基础设施集成测试失败。' }
} finally {
    try {
        if ($backendWasRunning) { docker compose start backend; if ($LASTEXITCODE -ne 0) { throw '容器后端恢复启动失败。' } }
    } finally {
        Set-Location -LiteralPath $originalLocation
        foreach ($key in $previousValues.Keys) {
            # 精确还原缺失状态，失败或后端启动异常时也不留下真实调用开关。
            if ($null -eq $previousValues[$key]) {
                Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
            }
        }
        if ($lockHeld) { $verificationLock.ReleaseMutex() }
        $verificationLock.Dispose()
    }
}
