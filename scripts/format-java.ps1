#Requires -Version 7.0
<#
.SYNOPSIS
    展开Java文档标签并按四空格格式化手写源码；Check仅检查，不修改源码。
.DESCRIPTION
    使用固定版本与SHA256的google-java-format；首次运行从Maven Central下载到本地忽略目录。
    只处理Git管理或未忽略的六模块Java源码，不处理生成代码、配置、密钥或冻结文档。
    不排序/删除import、不改写长字符串，保持业务表达式及SQL/提示词字面量。
#>
param([switch]$Check)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$original = Get-Location
$version = '1.24.0'
$expectedHash = '812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1'
$tool = Join-Path $root ".runtime/tooling/google-java-format-$version-all-deps.jar"

# 词法优先跳过文本块、字符串、字符及行注释，只转换真正的Javadoc，不能改到SQL或提示词。
$tokens = [regex]::new('"""(?:\\[\s\S]|(?!""")[\s\S])*"""|"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|//[^\n]*|/\*[\s\S]*?\*/')
$tags = '(?<!\{)\s+(@(?:param|return|throws|exception|see|since|deprecated|apiNote|implNote|implSpec)\b)'

try {
    Set-Location -LiteralPath $root
    if (-not (Test-Path -LiteralPath $tool)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $tool) | Out-Null
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/$version/google-java-format-$version-all-deps.jar" -OutFile $tool
    }
    if ((Get-FileHash -LiteralPath $tool -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'FORMATTER_CHECKSUM_MISMATCH' }
    $files = @(& git ls-files --cached --others --exclude-standard -- '*.java' | Where-Object { $_ -match '^(domain|application|api|agent|infrastructure|bootstrap)/src/(main|test)/java/' })
    if ($LASTEXITCODE -ne 0 -or -not $files.Count) { throw 'JAVA_SOURCE_LIST_FAILED' }
    $utf8 = [Text.UTF8Encoding]::new($false)
    $invalidDocs = 0
    foreach ($file in $files) {
        $content = [IO.File]::ReadAllText((Join-Path $root $file))
        $expanded = $tokens.Replace($content, [Text.RegularExpressions.MatchEvaluator]{
            param($match)
            if (-not $match.Value.StartsWith('/**') -or $match.Value -notmatch '@(?:param|return|throws|exception|see|since|deprecated|apiNote|implNote|implSpec)\b') { return $match.Value }
            $prefix = $content.Substring(0,$match.Index)
            $indent = ($prefix -split "`n")[-1]
            if ($indent.Trim().Length) { $indent = '' }
            $body = $match.Value.Substring(3,$match.Length-5)
            $body = [regex]::Replace($body,'\r?\n\s*\* ?',"`n").Trim()
            $body = [regex]::Replace($body,$tags,"`n`$1")
            $lines = @($body -split '\r?\n' | ForEach-Object { $_.TrimEnd() })
            return "/**`n" + (($lines | ForEach-Object { $indent + ' *' + $(if ($_.Length) { ' ' + $_ } else { '' }) }) -join "`n") + "`n$indent */"
        })
        if ($expanded -cne $content.Replace("`r`n","`n")) {
            if ($Check) { $invalidDocs++ } else { [IO.File]::WriteAllText((Join-Path $root $file),$expanded.Replace("`r`n","`n"),$utf8) }
        }
    }
    if ($invalidDocs) { throw "JAVADOC_LAYOUT_INVALID_FILES=$invalidDocs" }
    # 直接调用格式器API：固定导出JDK编译器包，避免Windows内存映射原地写入失败。
    $toolDirectory = Split-Path -Parent $tool
    & javac -encoding UTF-8 -cp $tool -d $toolDirectory (Join-Path $PSScriptRoot 'java/FormatJavaSource.java')
    if ($LASTEXITCODE -ne 0) { throw 'JAVA_FORMAT_LAUNCHER_COMPILE_FAILED' }
    $exports = @('api','file','parser','tree','util','code') | ForEach-Object { "--add-exports=jdk.compiler/com.sun.tools.javac.$_=ALL-UNNAMED" }
    $mode = if ($Check) { 'check' } else { 'apply' }
    # 批量参数避免Windows命令行长度限制，不输出或归档源码正文。
    for ($index=0; $index -lt $files.Count; $index+=25) {
        $batch = @($files[$index..([Math]::Min($index+24,$files.Count-1))])
        & java @exports -cp "$tool$([IO.Path]::PathSeparator)$toolDirectory" FormatJavaSource $mode @batch
        if ($LASTEXITCODE -ne 0) { throw 'JAVA_FORMAT_CHECK_OR_APPLY_FAILED' }
    }
    "JAVA_FORMAT_$(if ($Check) { 'CHECK' } else { 'APPLY' })_PASS files=$($files.Count)"
} finally { Set-Location -LiteralPath $original }
