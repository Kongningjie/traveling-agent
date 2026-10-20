#Requires -Version 7.0
# 仅由backup-local.ps1点加载；使用该脚本已验证的固定SQL执行器和受限清单根。
<#
.SYNOPSIS
    读取业务备份之外的最新技术删除头，缺失/链接/损坏均失败。
#>
function Read-DeletionHead {
    if (-not (Test-Path -LiteralPath $deletionsRoot -PathType Container)) { throw 'LATEST_DELETIONS_MISSING' }
    if ((Get-Item -LiteralPath $deletionsRoot).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'LATEST_DELETIONS_INVALID' }
    $file=Join-Path $deletionsRoot 'head'
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'LATEST_DELETIONS_MISSING' }
    $item=Get-Item -LiteralPath $file
    if ($item.Length -gt 1024 -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'LATEST_DELETIONS_INVALID' }
    $p=[IO.File]::ReadAllText($file).Split("`t")
    if ($p.Count -ne 4 -or $p[0] -ne 'v1' -or $p[2] -notmatch '^\d{1,18}$' -or $p[3] -notmatch '^[a-f0-9]{64}$') { throw 'LATEST_DELETIONS_INVALID' }
    try { $instance=([guid]$p[1]).ToString() } catch { throw 'LATEST_DELETIONS_INVALID' }
    if ([long]$p[2] -gt 1000000) {throw 'LATEST_DELETIONS_INVALID'}
    # 只读恢复脚本不能替应用补封独立头；检测到未封存完整条目时必须先运行最新应用恢复。
    if(Test-Path -LiteralPath (Join-Path $deletionsRoot (([long]$p[2]+1).ToString('D20')+'.del'))){throw 'LATEST_DELETIONS_UNSEALED'}
    if ([long]$p[2] -gt 0) {
        $last=Join-Path $deletionsRoot (([long]$p[2]).ToString('D20')+'.del')
        if (-not (Test-Path -LiteralPath $last -PathType Leaf)) {throw 'LATEST_DELETIONS_INCOMPLETE'}
        $lastItem=Get-Item -LiteralPath $last
        if ($lastItem.Length -gt 1024 -or ($lastItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) {throw 'LATEST_DELETIONS_INVALID'}
        $record=[IO.File]::ReadAllText($last).Split("`n")
        if ($record.Count -ne 3 -or $record[2] -ne $p[3]) {throw 'LATEST_DELETIONS_CHAIN'}
        $entry=Read-DeletionEntry ([long]$p[2]) $record[0]
        if ($entry.hash -ne $p[3]) {throw 'LATEST_DELETIONS_CHAIN'}
    }
    return @{instance=$instance;sequence=[long]$p[2];hash=$p[3]}
}
<#
.SYNOPSIS
    校验单条连续记录，SQL参数仅限规范UUID/枚举/时间，不使用用户正文。
.PARAMETER Sequence
    当前可信头内的下一顺序。
.PARAMETER Previous
    前序已验证SHA256。
#>
function Read-DeletionEntry {
    param([long]$Sequence,[string]$Previous)
    $file=Join-Path $deletionsRoot ($Sequence.ToString('D20')+'.del')
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'LATEST_DELETIONS_INCOMPLETE' }
    $item=Get-Item -LiteralPath $file
    if ($item.Length -gt 1024 -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'LATEST_DELETIONS_INVALID' }
    $lines=[IO.File]::ReadAllText($file).Split("`n")
    if ($lines.Count -ne 3 -or $lines[0] -ne $Previous) { throw 'LATEST_DELETIONS_CHAIN' }
    $actual=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($lines[0]+"`n"+$lines[1]))).ToLowerInvariant()
    if ($actual -ne $lines[2]) { throw 'LATEST_DELETIONS_CHAIN' }
    $v=$lines[1].Split("`t")
    if ($v.Count -ne 8 -or $v[0] -ne $Sequence.ToString() -or $v[3] -notin @('CHAT','TASK','DOCUMENT')) { throw 'LATEST_DELETIONS_INVALID' }
    try {
        $request=([guid]$v[1]).ToString();$user=([guid]$v[2]).ToString();$target=([guid]$v[4]).ToString()
        $chat=if($v[5] -eq '-'){'NULL'}else{"'$(([guid]$v[5]).ToString())'::uuid"}
        $task=if($v[6] -eq '-'){'NULL'}else{"'$(([guid]$v[6]).ToString())'::uuid"}
        $at=([datetimeoffset]::Parse($v[7],[Globalization.CultureInfo]::InvariantCulture)).UtcDateTime.ToString('o',[Globalization.CultureInfo]::InvariantCulture)
    } catch { throw 'LATEST_DELETIONS_INVALID' }
    return @{sql="SELECT traveling_agent.apply_deletion($Sequence,'$request'::uuid,'$user'::uuid,'$($v[3])','$target'::uuid,$chat,$task,'$at'::timestamptz);";hash=$actual}
}
<#
.SYNOPSIS
    在隔离恢复库重放独立最新清单；不能从旧备份替换独立清单。
.PARAMETER Database
    内部生成的临时恢复数据库名。
#>
function Replay-LatestDeletions {
    param([string]$Database)
    $disk=Read-DeletionHead
    $raw=((Invoke-Sql "SELECT instance::text||'|'||sequence::text||'|'||hash FROM traveling_agent.lifecycle_state WHERE id=1 AND initialized" $Database)-join '').Trim().Split('|')
    if ($raw.Count -ne 3 -or $raw[0] -ne $disk.instance -or [long]$raw[1] -gt $disk.sequence) { throw 'LATEST_DELETIONS_MISMATCH' }
    $previous=$raw[2]
    for($n=[long]$raw[1]+1;$n -le $disk.sequence;$n++){
        $entry=Read-DeletionEntry $n $previous
        Invoke-Sql ("BEGIN;SET LOCAL search_path=traveling_agent;"+$entry.sql+"UPDATE traveling_agent.lifecycle_state SET sequence=$n,hash='$($entry.hash)' WHERE id=1;COMMIT;") $Database | Out-Null
        $previous=$entry.hash
    }
    if ($previous -ne $disk.hash) { throw 'LATEST_DELETIONS_CHAIN' }
    $final=Read-DeletionHead
    if ($final.sequence -ne $disk.sequence -or $final.hash -ne $disk.hash) { throw 'LATEST_DELETIONS_CHANGED_RETRY' }
}
