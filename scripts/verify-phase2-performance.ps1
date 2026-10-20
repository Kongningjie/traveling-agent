#Requires -Version 7.0
<#
.SYNOPSIS
    隔离Compose内执行4CPU/8GiB、5个登录用户/20任务的一秒模型替身性能验收。
.DESCRIPTION
    不改变现有项目容器和卷，不载入供应商密钥。创建唯一测试项目及新卷，结束仅移除该项目。
    只保存结构化指标/资源证据；不保存Docker或构建日志。性能模式使用internal网络。
    UiOnly额外发布回环入口，关闭出口伪装；两种模式都不载入供应商密钥且关闭真实业务调用。
    默认先构建最新Jar；SkipBuild仅用于已有相同生产字节的定向复测，仍复制最新验收测试类。
#>
param([switch]$SkipBuild, [switch]$UiOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$previousReport = $env:ACCEPTANCE_REPORT_ROOT
$project = 'travel-acceptance-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$compose = Join-Path $root 'compose.acceptance.yaml'
$composeFiles = @('-f', $compose)
if ($UiOnly) { $composeFiles += @('-f', (Join-Path $root 'compose.acceptance-ui.yaml')) }
$buildRoot = Join-Path $root ('.runtime/acceptance-build-' + [Guid]::NewGuid().ToString('N'))
$createdProject = $false
try {
    Set-Location -LiteralPath $root
    $platform = (& docker info --format '{{.NCPU}} {{.MemTotal}} {{.ServerVersion}}') -split ' '
    if ($LASTEXITCODE -ne 0 -or [int]$platform[0] -lt 4 -or [long]$platform[1] -lt 8GB) { throw 'ACCEPTANCE_PLATFORM_INSUFFICIENT' }
    # Windows本次固定环境要求真实SSD证据，不能用低负载结果代替目标基准。
    $ssd = $IsWindows -and @(Get-PhysicalDisk | Where-Object MediaType -eq 'SSD').Count -gt 0
    if (-not $ssd) { throw 'ACCEPTANCE_SSD_NOT_VERIFIED' }
    if (-not $SkipBuild) {
        & ./mvnw.cmd --batch-mode --no-transfer-progress '-DskipTests' package 2>&1 | Select-String 'ERROR|BUILD SUCCESS|BUILD FAILURE'
        if ($LASTEXITCODE -ne 0) { throw 'ACCEPTANCE_BUILD_FAILED' }
    }
    [IO.Directory]::CreateDirectory($buildRoot) | Out-Null
    $jar = Join-Path $root 'bootstrap/target/traveling-agent.jar'
    # 只展开生产BOOT-INF运行字节，不复制整个工作区或所有测试类。
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/') -or $entry.FullName -notmatch '^BOOT-INF/(classes|lib)/(.+)$') { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $buildRoot ($Matches[1] + '/' + $Matches[2])))
            if (-not $target.StartsWith($buildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'ACCEPTANCE_ARCHIVE_PATH_INVALID' }
            [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
        }
    } finally { $archive.Dispose() }
    $probePath = 'com/lawrence/travel/bootstrap/acceptance'
    $destination = Join-Path $buildRoot ('probe/' + $probePath)
    [IO.Directory]::CreateDirectory($destination) | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $root ('bootstrap/target/test-classes/' + $probePath)) -Filter 'LocalAcceptanceProbe*.class' | Copy-Item -Destination $destination
    # 仅显式测试配置注解所需Jar，不引入Mockito或其他测试组件扫描。
    $testJar = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/org/springframework/boot/spring-boot-test/4.1.1/spring-boot-test-4.1.1.jar'
    Copy-Item -LiteralPath $testJar -Destination (Join-Path $buildRoot 'lib')
    Copy-Item -LiteralPath (Join-Path $root 'web/dist') -Destination (Join-Path $buildRoot 'ui') -Recurse
    & docker build --quiet -t traveling-agent-acceptance-probe -f bootstrap/Dockerfile.acceptance $buildRoot
    if ($LASTEXITCODE -ne 0) { throw 'ACCEPTANCE_IMAGE_FAILED' }
    $sourceHashes = @{}
    foreach ($file in @('bootstrap/target/traveling-agent.jar', 'web/dist/index.html', 'bootstrap/src/test/resources/evaluation/v2/suite-lock.json', 'scripts/verify-phase2-performance.ps1')) {
        $sourceHashes[$file] = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root $file)).Hash.ToLowerInvariant()
    }
    $probeHashes = @{}
    Get-ChildItem -LiteralPath $destination -Filter '*.class' | Sort-Object Name | ForEach-Object { $probeHashes[$_.Name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant() }
    $env:ACCEPTANCE_REPORT_ROOT = Join-Path $root ('.runtime/evaluations/phase2-performance-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($env:ACCEPTANCE_REPORT_ROOT) | Out-Null
    $createdProject = $true
    & docker compose -p $project @composeFiles up -d --wait postgres redis
    if ($LASTEXITCODE -ne 0) { throw 'ACCEPTANCE_INFRASTRUCTURE_FAILED' }
    & docker compose -p $project @composeFiles up -d backend web
    if ($LASTEXITCODE -ne 0) { throw 'ACCEPTANCE_START_FAILED' }
    $backend = (& docker compose -p $project @composeFiles ps -a -q backend).Trim()
    $resources = foreach ($service in @('postgres', 'redis', 'backend', 'web')) {
        $id = (& docker compose -p $project @composeFiles ps -a -q $service).Trim()
        $limits = (& docker inspect --format '{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}} {{.Image}}' $id) -split ' '
        [pscustomobject]@{ service = $service; nanoCpus = [long]$limits[0]; bytes = [long]$limits[1]; image = $limits[2] }
    }
    if (($resources | Measure-Object nanoCpus -Sum).Sum -ne 4000000000 -or ($resources | Measure-Object bytes -Sum).Sum -ne 8GB) { throw 'ACCEPTANCE_RESOURCE_CAP_INVALID' }
    $networkMode = if ($UiOnly) { 'internal_plus_loopback_ui_no_masquerade' } else { 'internal' }
    [ordered]@{ project = $project; hostLogicalCpus = [Environment]::ProcessorCount; dockerCpus = [int]$platform[0]; dockerMemory = [long]$platform[1]; docker = $platform[2]; ssd = $ssd; os = [Runtime.InteropServices.RuntimeInformation]::OSDescription; commit = (& git rev-parse HEAD).Trim(); sourceHashes = $sourceHashes; probeHashes = $probeHashes; resources = $resources; network = $networkMode; model = 'LOCAL_FIXED_1000MS'; realExternalRequests = 0 } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $env:ACCEPTANCE_REPORT_ROOT 'environment.json')
    if ($UiOnly) {
        Write-Output ('UI_LOCAL_ONLY http://127.0.0.1:4195/ PROJECT=' + $project)
        while ((& docker inspect --format '{{.State.Running}}' $backend) -eq 'true') { Start-Sleep -Seconds 2 }
        return
    }
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    while ((& docker inspect --format '{{.State.Running}}' $backend) -eq 'true') {
        if ([DateTime]::UtcNow -ge $deadline) { throw 'ACCEPTANCE_TIMEOUT' }
        Start-Sleep -Seconds 2
    }
    $exitCode = & docker inspect --format '{{.State.ExitCode}}' $backend
    if ($exitCode -ne '0') {
        # 仅固定诊断分类，不复制Spring异常/正文或整份控制台输出。
        & docker logs $backend 2>&1 | Select-String '^LOCAL_ACCEPTANCE_'
        throw 'ACCEPTANCE_PROBE_FAILED'
    }
    $report = Get-Content -LiteralPath (Join-Path $env:ACCEPTANCE_REPORT_ROOT 'performance.json') -Raw | ConvertFrom-Json
    if ($report.mode -ne 'LOCAL_CONTRACT' -or $report.status -ne 'PASS') { throw 'ACCEPTANCE_REPORT_NOT_PASSED' }
    Write-Output ('REPORT: ' + $env:ACCEPTANCE_REPORT_ROOT)
    $report.metrics | ConvertTo-Json -Compress
} finally {
    if ($createdProject) {
        # 只删除当前函数生成的不可复用随机项目；绝不使用生产项目名或清理既有卷。
        if ($project -notmatch '^travel-acceptance-[0-9a-f]{12}$') { throw 'ACCEPTANCE_CLEANUP_SCOPE_INVALID' }
        & docker compose -p $project @composeFiles down --volumes 2>&1 | Select-String 'Removed|Error'
    }
    # 解包目录不是验收证据；解析后的绝对路径和UUID叶名均校验后，只清理本次临时构建目录。
    $runtimeRoot = [IO.Path]::GetFullPath((Join-Path $root '.runtime')) + [IO.Path]::DirectorySeparatorChar
    $resolvedBuild = [IO.Path]::GetFullPath($buildRoot)
    if (-not $resolvedBuild.StartsWith($runtimeRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolvedBuild) -notmatch '^acceptance-build-[0-9a-f]{32}$') { throw 'ACCEPTANCE_BUILD_CLEANUP_SCOPE_INVALID' }
    if (Test-Path -LiteralPath $resolvedBuild) { Remove-Item -LiteralPath $resolvedBuild -Recurse -Force }
    if ($null -eq $previousReport) { Remove-Item Env:ACCEPTANCE_REPORT_ROOT -ErrorAction SilentlyContinue } else { $env:ACCEPTANCE_REPORT_ROOT = $previousReport }
    Set-Location -LiteralPath $original
}
