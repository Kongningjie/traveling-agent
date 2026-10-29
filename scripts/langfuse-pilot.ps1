#Requires -Version 7.0
<#
.SYNOPSIS
    运行5例本地合成HTTP评测并导入Langfuse，或回查原生人工评分。
.DESCRIPTION
    不提供真实模型开关，不读取供应商Key。Run复用3个隔离HTTP批次；原始报告只留本地。
    平台认证由固定容器内部读取，不出现在参数、输出或报告。失败保留索引，不自动重发写入。
    Open启用明确的本地合成访问模式；Close回到原治理模式，不修改PG verified或删除标记。
#>
param([ValidateSet('Run','Inspect','Open','Close')][string]$Action='Run', [string]$ManifestPath)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$compose=Join-Path $root 'compose.langfuse.yaml'
$override=Join-Path $root 'compose.langfuse-pilot.yaml'
$envFile=Join-Path $root '.env'
$savedExport=$env:TRAVEL_LANGFUSE_PILOT_EXPORT
$location=Get-Location
try {
    Set-Location -LiteralPath $root
    if($Action -in @('Open','Close')) {
        $argsCompose=@('compose','--env-file',$envFile,'-f',$compose)
        if($Action -eq 'Open'){ $argsCompose+=@('-f',$override) }
        & docker @argsCompose up -d --no-deps --wait --wait-timeout 45 gateway 2>&1 | Out-Null
        if($LASTEXITCODE -ne 0){throw 'PILOT_GATEWAY_START_FAILED'}
        if($Action -eq 'Open'){'LANGFUSE_LOCAL_SYNTHETIC_ONLY http://127.0.0.1:3100'} else {'LANGFUSE_GOVERNED_GATE_RESTORED'}
        return
    }
    $web=& docker compose --env-file $envFile -f $compose ps -q web
    $image=& docker inspect --format '{{.Config.Image}}' $web
    if($LASTEXITCODE -ne 0 -or $image -ne 'docker.langfuse.com/langfuse/langfuse@sha256:5338ee88d4ec07028836f6af9d98a9b411f8eeda0ad9fae66502d849b04da724'){throw 'PILOT_FIXED_WEB_REQUIRED'}
    $runtime=Join-Path $root '.runtime/evaluations'
    if($Action -eq 'Run') {
        $id=[Guid]::NewGuid().ToString('N')
        $started=[DateTimeOffset]::UtcNow.ToString('o')
        $folder=Join-Path $runtime ('langfuse-pilot-'+$id)
        New-Item -ItemType Directory -Path $folder | Out-Null
        $env:TRAVEL_LANGFUSE_PILOT_EXPORT=Join-Path $folder 'suite.json'
        & ./mvnw.cmd --batch-mode --no-transfer-progress -pl bootstrap -am '-Dtest=LangfusePilotSuiteTest' '-Dsurefire.failIfNoSpecifiedTests=false' test 2>&1 |
            ForEach-Object {if($_ -match '^\[INFO\] (Tests run:|BUILD SUCCESS|BUILD FAILURE)|^\[ERROR\]'){ $_.ToString() }}
        if($LASTEXITCODE -ne 0){throw 'PILOT_SUITE_EXPORT_FAILED'}
        $paths=[Collections.Generic.List[string]]::new()
        foreach($batch in @('Conversation','Requirements','DocumentReferences')) {
            $found=$null
            & ./scripts/verify-dev35-http.ps1 -Batch $batch | ForEach-Object {
                if($_ -is [string] -and $_.StartsWith('REPORT: ')){ $found=$_.Substring(8) }
                elseif($_.ToString() -match 'DEV35_HTTP|BUILD SUCCESS|BUILD FAILURE|Tests run:'){ $_.ToString() }
            }
            if(-not $found -or -not(Test-Path -LiteralPath $found)){throw 'PILOT_HTTP_REPORT_REQUIRED'}
            $paths.Add($found)
            @{sourceReports=@($paths);createdAt=$started}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $folder 'sources.json') -Encoding utf8
        }
        $bundle=@{id=$id;startedAt=$started;suite=(Get-Content -LiteralPath $env:TRAVEL_LANGFUSE_PILOT_EXPORT -Raw|ConvertFrom-Json);
            reports=@($paths|ForEach-Object{Get-Content -LiteralPath $_ -Raw|ConvertFrom-Json})}
        $command=@{action='publish';bundle=$bundle}
        $ManifestPath=Join-Path $folder 'manifest.json'
        @{sourceReports=@($paths);createdAt=$started}|ConvertTo-Json -Depth 4|Set-Content -LiteralPath (Join-Path $folder 'sources.json') -Encoding utf8
    } else {
        if(-not $ManifestPath){throw 'PILOT_MANIFEST_REQUIRED'}
        $full=[IO.Path]::GetFullPath($ManifestPath,$root)
        $relative=[IO.Path]::GetRelativePath($runtime,$full)
        if($relative -notmatch '^langfuse-pilot-[a-f0-9]{32}[\\/]manifest\.json$'){throw 'PILOT_MANIFEST_PATH_INVALID'}
        $command=@{action=$(if($Action -eq 'Inspect'){'inspect'}else{'delete'});manifest=(Get-Content -LiteralPath $full -Raw|ConvertFrom-Json)}
        $ManifestPath=$full
    }
    # 材料通过stdin传入，绝不把认证或正文放到命令行；仅解析固定维护脚本的JSON结果。
    $jsonCommand=$command|ConvertTo-Json -Depth 50 -Compress
    foreach($line in Get-Content -LiteralPath $envFile) {
        $entry=[regex]::Match($line,'^([A-Za-z_][A-Za-z0-9_]*)=(.*)$')
        if($entry.Success -and $entry.Groups[1].Value -match '(KEY|SECRET|PASSWORD|TOKEN)') {
            $value=$entry.Groups[2].Value.Trim().Trim('"').Trim("'")
            if($value.Length -ge 8 -and $jsonCommand.Contains($value)){throw 'PILOT_SECRET_IN_MATERIAL'}
        }
    }
    $output=$jsonCommand|& docker compose --env-file $envFile -f $compose exec -T web node /maintenance/pilot-native.mjs 2>$null
    $exit=$LASTEXITCODE
    try{$result=($output -join "`n")|ConvertFrom-Json}catch{throw 'PILOT_NATIVE_RESULT_UNAVAILABLE'}
    if($result.manifest){$result.manifest|ConvertTo-Json -Depth 20|Set-Content -LiteralPath $ManifestPath -Encoding utf8}
    $result|ConvertTo-Json -Depth 25|Set-Content -LiteralPath (Join-Path (Split-Path $ManifestPath) 'platform-status.json') -Encoding utf8
    'PILOT_MANIFEST: '+$ManifestPath
    'PILOT_PLATFORM_STATUS: '+$result.status
    foreach($row in $result.rows){'PILOT case='+$row.caseId+' platform='+$row.platform+' program='+$row.program+' human='+$row.human.status+' model=NOT_RUN tool=NOT_RUN'}
    if($exit -ne 0 -or $result.status -eq 'FAILED'){throw ('PILOT_NATIVE_FAILED stage='+$result.stage+' category='+$result.category+' (索引保留，不自动重发)')}
} finally {
    [Environment]::SetEnvironmentVariable('TRAVEL_LANGFUSE_PILOT_EXPORT',$savedExport,'Process')
    Set-Location -LiteralPath $location
}
