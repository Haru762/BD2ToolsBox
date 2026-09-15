# T03 APK baseline analysis driver.
#
# Runs the read-only tools in this directory over the preserved historical and
# rebuilt-original APKs and writes every output, plus a SHA-256 manifest, into
# an ignored output directory. All inputs are opened read-only; nothing is
# modified, moved or extracted.
#
# Usage:
#   powershell -File tools/apk-baseline/run-t03-analysis.ps1
#   powershell -File tools/apk-baseline/run-t03-analysis.ps1 -Root <repo> -OutDir <dir> -Python <exe>
#
# Exit code is non-zero if any step fails or if a tool reports an internal
# self-check failure.

param(
    [string]$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor',
    [string]$OutDir = 'local/refactor-artifacts/t03-apk-analysis',
    [string]$Python = ''
)

$ErrorActionPreference = 'Stop'
$Root = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
$Tools = Join-Path $Root 'tools/apk-baseline'
if (-not (Test-Path -LiteralPath $Tools)) { throw "tools/apk-baseline not found under $Root" }

if (-not $Python) {
    $candidate = Join-Path $env:LOCALAPPDATA 'Programs/Python/Python311/python.exe'
    if (Test-Path -LiteralPath $candidate) { $Python = $candidate }
    else { throw "No Python given and $candidate not found; pass -Python <exe>" }
}
if (-not (Test-Path -LiteralPath $Python)) { throw "Python not found: $Python" }

$Out = Join-Path $Root $OutDir
if (-not (Test-Path -LiteralPath $Out)) { New-Item -ItemType Directory -Path $Out -Force | Out-Null }

$hist = 'local/refactor-artifacts/historical-0.2.16'
$reb = 'local/refactor-artifacts/rebuilt-original'

# tag -> APK path (relative to $Root)
$packages = [ordered]@{
    'hist-arm64'    = "$hist/app-arm64-v8a-release.apk"
    'hist-x64'      = "$hist/app-x86_64-release.apk"
    'reb-arm64'     = "$reb/app-arm64-v8a-release.apk"
    'reb-x64'       = "$reb/app-x86_64-release.apk"
    'reb-universal' = "$reb/app-universal-release.apk"
}

# abi -> tags compared against each other
$pairs = [ordered]@{
    'arm64' = @('hist-arm64', 'reb-arm64')
    'x64'   = @('hist-x64', 'reb-x64')
}

$script:step = 0
$script:failures = @()

function Step {
    param(
        [string]$Label,
        [string]$Tool,
        [string[]]$Arguments,
        [string]$Capture = ''
    )
    $script:step++
    $toolPath = Join-Path $Tools $Tool
    if ($Capture) {
        & $Python $toolPath @Arguments | Out-File -LiteralPath $Capture -Encoding utf8
    } else {
        & $Python $toolPath @Arguments | Out-Null
    }
    $code = $LASTEXITCODE
    if ($code -ne 0) {
        $script:failures += ("{0} -> exit {1}" -f $Label, $code)
        Write-Host ("[{0,2}] {1,-22} exit {2}  <-- FAIL" -f $script:step, $Label, $code)
    } else {
        Write-Host ("[{0,2}] {1,-22} exit 0" -f $script:step, $Label)
    }
}

Write-Host "root   : $Root"
Write-Host "python : $Python"
Write-Host "output : $Out"
Write-Host ""

Set-Location -LiteralPath $Root

foreach ($tag in $packages.Keys) {
    $apk = $packages[$tag]
    if (-not (Test-Path -LiteralPath (Join-Path $Root $apk))) { throw "missing input APK: $apk" }
    Step "dump_entries $tag" 'dump_entries.py' @($apk, "$OutDir/dump-$tag.json")
    Step "classify $tag" 'classify_content.py' @($apk, '--package-id', $tag, '--out', "$OutDir/class-$tag.json")
}

foreach ($abi in $pairs.Keys) {
    $ta, $tb = $pairs[$abi]
    $apkA = $packages[$ta]
    $apkB = $packages[$tb]
    $jA = "$OutDir/dump-$ta.json"
    $jB = "$OutDir/dump-$tb.json"

    Step "compare $abi" 'compare.py' @($jA, $jB, $ta, $tb) "$OutDir/cmp-$abi.txt"
    Step "structure $abi" 'structure.py' @($apkA, $apkB, $jA, $jB) "$OutDir/struct-$abi.txt"
    Step "byte_diff $abi" 'byte_diff.py' @($apkA, $apkB, $jA, $jB, "$OutDir/bytediff-$abi.json") "$OutDir/bytediff-$abi.txt"
    Step "imy_diff $abi" 'imy_diff.py' @($apkA, $apkB, '--label-a', $ta, '--label-b', $tb, '--out', "$OutDir/imydiff-$abi.json") "$OutDir/imydiff-$abi.txt"
}

$lines = @()
Get-ChildItem -LiteralPath $Out -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object Name |
    ForEach-Object {
        $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLower()
        $lines += ("{0}  {1}" -f $h, $_.Name)
    }
$lines | Out-File -LiteralPath (Join-Path $Out 'SHA256SUMS.txt') -Encoding utf8

Write-Host ""
Write-Host ("output files hashed: {0}" -f $lines.Count)
if ($script:failures.Count -gt 0) {
    Write-Host "FAILURES:"
    $script:failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "all steps exited 0"
exit 0
