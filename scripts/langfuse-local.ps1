#Requires -Version 7.0
<#
.SYNOPSIS
    初始化或启动独立本地评测栈；不调用模型、不停止旅行助手、不删除卷。
.DESCRIPTION
    Init 仅在 .env 追加缺失的 LF_ 配置；Up 初始化后启动；Status 只显示服务状态。
    Verify 只运行本地合成链路测试；不启用真实模型、地图或检索开关。
    VerifyMetadata 使用随机测试用户验证PG元数据到本机平台，结束只清理该用户。
    首次初始化后登录邮箱与密码查看本机 .env 的 LF_USER_EMAIL、LF_USER_PASSWORD。
#>
param([ValidateSet('Init', 'Up', 'Status', 'Verify', 'VerifyMetadata')][string]$Action = 'Status')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root '.env'
$composePath = Join-Path $root 'compose.langfuse.yaml'

if ($Action -in @('Init', 'Up')) {
    $content = if (Test-Path -LiteralPath $envPath) { [IO.File]::ReadAllText($envPath) } else { '' }
    $defaults = [ordered]@{ LF_USER_EMAIL = 'operator@langfuse.local' }
    foreach ($name in @('LF_POSTGRES_PASSWORD', 'LF_REDIS_PASSWORD', 'LF_CLICKHOUSE_PASSWORD',
            'LF_MINIO_PASSWORD', 'LF_SALT', 'LF_ENCRYPTION_KEY', 'LF_NEXTAUTH_SECRET',
            'LF_PUBLIC_KEY', 'LF_SECRET_KEY', 'LF_USER_PASSWORD')) {
        $defaults[$name] = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    }
    $append = @()
    foreach ($entry in $defaults.GetEnumerator()) {
        $match = [regex]::Match($content, "(?m)^$($entry.Key)=(.*)$")
        if ($match.Success) {
            # 已存在但空白/占位时拒绝，防止意外重置已建库认证。
            if ([string]::IsNullOrWhiteSpace($match.Groups[1].Value) -or $match.Groups[1].Value -match 'replace-') {
                throw "LANGFUSE_CONFIG_REQUIRED: $($entry.Key)"
            }
        } else { $append += "$($entry.Key)=$($entry.Value)" }
    }
    if ($append.Count) {
        [IO.File]::AppendAllText($envPath, "`n# 独立本地 Langfuse 认证，不提交、不输出。`n" + ($append -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
    }
    'LANGFUSE_LOCAL_CONFIG_READY'
}
if ($Action -eq 'Up') {
    # 只输出状态；异常不转储 Docker 原始配置或包含凭据的响应。
    & docker compose --env-file $envPath -f $composePath up -d --wait --wait-timeout 300 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'LANGFUSE_START_FAILED: 使用 Status 查看服务状态，勿输出完整配置或日志。' }
    'LANGFUSE_READY http://127.0.0.1:3100'
}
if ($Action -eq 'Status') {
    & docker compose --env-file $envPath -f $composePath ps --format 'table {{.Service}}\t{{.State}}\t{{.Health}}'
    if ($LASTEXITCODE -ne 0) { throw 'LANGFUSE_STATUS_FAILED' }
}
if ($Action -in @('Verify', 'VerifyMetadata')) {
    $saved = @{}
    $names = @('LF_PUBLIC_KEY', 'LF_SECRET_KEY', 'LANGFUSE_LOCAL_VERIFY')
    try {
        foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
        foreach ($name in @('LF_PUBLIC_KEY', 'LF_SECRET_KEY')) {
            $match = [regex]::Match([IO.File]::ReadAllText($envPath), "(?m)^$name=([^\r\n]+)")
            if (-not $match.Success) { throw "LANGFUSE_CONFIG_REQUIRED: $name" }
            [Environment]::SetEnvironmentVariable($name, $match.Groups[1].Value, 'Process')
        }
        $env:LANGFUSE_LOCAL_VERIFY = 'true'
        Push-Location -LiteralPath $root
        try {
            if ($Action -eq 'VerifyMetadata') {
                & .\scripts\verify-local.ps1 -Scope Langfuse 2>&1 |
                    ForEach-Object { if ($_ -match 'Tests run:|BUILD SUCCESS|BUILD FAILURE|\[ERROR\]') { $_.ToString() } }
            } else {
                & .\mvnw.cmd --batch-mode --no-transfer-progress -pl bootstrap -am test '-Dtest=LangfuseLocalIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' 2>&1 |
                    ForEach-Object { if ($_ -match 'Tests run:|BUILD SUCCESS|BUILD FAILURE|\[ERROR\]') { $_.ToString() } }
            }
            if ($LASTEXITCODE -ne 0) { throw 'LANGFUSE_VERIFY_FAILED' }
        } finally { Pop-Location }
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    }
}
