#Requires -Version 7.0
<#
.SYNOPSIS
    执行冻结场景的本地领域／应用契约基线，生成JSON与Markdown报告。
.DESCRIPTION
    默认8开发样本，IncludeHoldout显式包含4留出样本；RepeatRepresentatives本地重复4例各3次。
    无真实模型／工具执行适配器，不接受真实调用开关，不读取API Key。
    通过verify-local暂停容器后端并恢复，使用真实本机PG；不归档控制台日志。
    报告含明确覆盖限制和逐条NOT_RUN，不能作为模型质量／完整E2E的验收结果。
.EXAMPLE
    .\scripts\evaluate-local.ps1 -IncludeHoldout -RepeatRepresentatives
#>
param([switch]$IncludeHoldout, [switch]$RepeatRepresentatives)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$previous = @{}
$values = @('TRAVEL_EVALUATION','TRAVEL_EVAL_HOLDOUT','TRAVEL_EVAL_REPEAT','TRAVEL_EVAL_DIRECTORY','TRAVEL_EVAL_COMMIT','TRAVEL_EVAL_SOURCE_HASH')
foreach ($name in $values) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$oldLocation = Get-Location
try {
    Set-Location -LiteralPath $root
    $env:TRAVEL_EVALUATION = 'true'
    $env:TRAVEL_EVAL_HOLDOUT = $IncludeHoldout.ToString().ToLowerInvariant()
    $env:TRAVEL_EVAL_REPEAT = $RepeatRepresentatives.ToString().ToLowerInvariant()
    $parent = Join-Path $root '.runtime/evaluations'
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $env:TRAVEL_EVAL_DIRECTORY = Join-Path $parent ('contract-' + [Guid]::NewGuid().ToString('N'))
    $env:TRAVEL_EVAL_COMMIT = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'EVALUATION_COMMIT_UNAVAILABLE' }
    # 所有非忽略受管／新源码的字节指纹，明确包含未提交工作区，绝不读取.env或本地私有docs。
    [string[]]$files = @(& git ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'EVALUATION_SOURCE_UNAVAILABLE' }
    [Array]::Sort($files, [StringComparer]::Ordinal)
    $fingerprints = foreach ($file in $files) { if (Test-Path -LiteralPath $file -PathType Leaf) { $file.Replace('\','/') + ':' + (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash } }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($fingerprints -join "`n"))
    $env:TRAVEL_EVAL_SOURCE_HASH = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    & (Join-Path $PSScriptRoot 'verify-local.ps1') -Scope Evaluation
    Write-Output ('REPORT: ' + $env:TRAVEL_EVAL_DIRECTORY)
} finally {
    Set-Location -LiteralPath $oldLocation
    foreach ($name in $values) { if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue } else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') } }
}
