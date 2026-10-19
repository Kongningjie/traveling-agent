#Requires -Version 7.0
<#
.SYNOPSIS
    构建固定解析镜像，或在专用终端运行本机runner；不启动业务Worker。
.DESCRIPTION
    Build只打包并构建镜像，将精确镜像ID及缺失的随机runner秘密写入本地.env，不打印值。
    Run只读取runner白名单配置；默认回环监听。Compose通过host.docker.internal访问；其他平台需先验证可达性并限制防火墙。
    不传真实凭据到命令行，不归档控制台或Docker日志。先完成项目验收，再交付镜像。
#>
param([ValidateSet('Build','Run')][string]$Action='Run')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$envFile=Join-Path $projectRoot '.env'
Set-Location -LiteralPath $projectRoot
if (-not (Test-Path -LiteralPath $envFile)) {throw 'LOCAL_ENV_MISSING'}
if ($Action -eq 'Build') {
    & .\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
    if ($LASTEXITCODE -ne 0) {throw 'PARSER_PACKAGE_FAILED'}
    & docker build -f bootstrap/Parser.Dockerfile -t traveling-agent-parser:dev28 .
    if ($LASTEXITCODE -ne 0) {throw 'PARSER_IMAGE_FAILED'}
    $image=(& docker image inspect traveling-agent-parser:dev28 --format '{{.Id}}').Trim()
    if ($LASTEXITCODE -ne 0 -or $image -notmatch '^sha256:[0-9a-f]{64}$') {throw 'PARSER_IMAGE_INVALID'}
    $lines=[IO.File]::ReadAllLines($envFile)
    $lines=@($lines | Where-Object {$_ -notmatch '^DOCUMENT_PARSER_IMAGE='}) + "DOCUMENT_PARSER_IMAGE=$image"
    if (-not ($lines | Where-Object {$_ -match '^DOCUMENT_PARSER_SECRET=.{32,}$'})) {
        $lines=@($lines | Where-Object {$_ -notmatch '^DOCUMENT_PARSER_SECRET='}) + ('DOCUMENT_PARSER_SECRET='+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
    }
    [IO.File]::WriteAllText($envFile,($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    Write-Output '解析镜像已构建，本地认证和镜像ID已配置；未显示秘密。'
} else {
    $allowed=@('DOCUMENT_PARSER_SECRET','DOCUMENT_PARSER_IMAGE','DOCUMENT_RUNNER_BIND','TRAVEL_DOCUMENTS_ROOT','DOCUMENT_MAX_BYTES','DOCUMENT_MAX_PAGES','DOCUMENT_MAX_TEXT','DOCUMENT_MAX_EXPANDED','DOCUMENT_MAX_SECONDS','DOCUMENT_MAX_PER_CHAT','DOCUMENT_MAX_USER_BYTES','DOCUMENT_MAX_USER_TEXT')
    $previous=@{}
    try {
        foreach($key in $allowed) {$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
        foreach($line in [IO.File]::ReadAllLines($envFile)) {if($line -match '^([A-Z_]+)=(.*)$' -and $allowed -contains $Matches[1]) {[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2].Trim(),'Process')}}
        & java -jar bootstrap/target/traveling-agent.jar --document-runner
        if ($LASTEXITCODE -ne 0) {throw 'PARSER_RUNNER_FAILED'}
    } finally {foreach($key in $previous.Keys) {[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}}
}
