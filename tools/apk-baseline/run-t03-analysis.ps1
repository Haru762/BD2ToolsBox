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
# -Root defaults to the repository this script lives in (two levels up from
# $PSScriptRoot), so no machine-specific path is baked in.
#
# Every path handed to a tool is absolute and the working directory is never
# relied upon, so the driver may be invoked from anywhere.
#
# Exit code is non-zero if any step fails, if a tool reports a failed gating
# check, or if a declared output file is missing.
#
# What that does NOT cover: SHA256SUMS.txt is regenerated from the same
# in-memory list of outputs it is validated against, so the script cannot by
# itself detect a discrepancy between the manifest and what exists. Verifying
# the manifest means running `sha256sum -c SHA256SUMS.txt` separately, and the
# manifest has no committed counterpart to diff against (/local/ is ignored).
#
# classify_content.py is invoked with --expect against the committed
# docs/refactor/evidence/baseline.json, so a silent *misclassification* (a
# changed group predicate moving bytes between groups without changing any
# total) fails the run instead of passing a tautological sum check.

param(
    [string]$Root = '',
    [string]$OutDir = 'local/refactor-artifacts/t03-apk-analysis',
    [string]$Python = '',
    [string]$Expect = 'docs/refactor/evidence/baseline.json'
)

$ErrorActionPreference = 'Stop'

if (-not $Root) {
    # tools/apk-baseline -> tools -> repo root
    $Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
}
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

$ExpectPath = Join-Path $Root $Expect
if (-not (Test-Path -LiteralPath $ExpectPath)) { throw "expected-classification baseline not found: $Expect" }

$hist = 'local/refactor-artifacts/historical-0.2.16'
$reb = 'local/refactor-artifacts/rebuilt-original'

# tag -> @(apk path relative to $Root, packageId inside the baseline oracle)
$packages = [ordered]@{
    'hist-arm64'    = @("$hist/app-arm64-v8a-release.apk", 'historical-arm64-v8a')
    'hist-x64'      = @("$hist/app-x86_64-release.apk",    'historical-x86_64')
    'reb-arm64'     = @("$reb/app-arm64-v8a-release.apk",  'rebuilt-arm64-v8a')
    'reb-x64'       = @("$reb/app-x86_64-release.apk",     'rebuilt-x86_64')
    'reb-universal' = @("$reb/app-universal-release.apk",  'rebuilt-universal')
}

# abi -> tags compared against each other
$pairs = [ordered]@{
    'arm64' = @('hist-arm64', 'reb-arm64')
    'x64'   = @('hist-x64', 'reb-x64')
}

$script:step = 0
$script:failures = @()
# Exactly the files this run generates. Hand-written companions (RUN.md,
# apksigner-verify.txt, device-measure.txt) are deliberately NOT covered by
# SHA256SUMS.txt: a re-run does not regenerate them, so hashing them here would
# record a hash of a file the driver never produced.
$script:outputs = New-Object System.Collections.Generic.List[string]

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
        $script:outputs.Add($Capture)
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
Write-Host "oracle : $Expect"
Write-Host ""

foreach ($tag in $packages.Keys) {
    $apkRel, $expectId = $packages[$tag]
    $apkAbs = Join-Path $Root $apkRel
    if (-not (Test-Path -LiteralPath $apkAbs)) { throw "missing input APK: $apkRel" }
    $outAbs = Join-Path $Out "dump-$tag.json"
    Step "dump_entries $tag" 'dump_entries.py' @($apkAbs, $outAbs)
    $script:outputs.Add($outAbs)
    $classAbs = Join-Path $Out "class-$tag.json"
    Step "classify $tag" 'classify_content.py' @(
        $apkAbs, '--package-id', $tag, '--out', $classAbs,
        '--expect', $ExpectPath, '--expect-package', $expectId)
    $script:outputs.Add($classAbs)
}

foreach ($abi in $pairs.Keys) {
    $ta, $tb = $pairs[$abi]
    $apkA = Join-Path $Root $packages[$ta][0]
    $apkB = Join-Path $Root $packages[$tb][0]
    $jA = Join-Path $Out "dump-$ta.json"
    $jB = Join-Path $Out "dump-$tb.json"

    Step "compare $abi" 'compare.py' @($jA, $jB, $ta, $tb) (Join-Path $Out "cmp-$abi.txt")
    Step "structure $abi" 'structure.py' @($apkA, $apkB, $jA, $jB) (Join-Path $Out "struct-$abi.txt")
    $bdJson = Join-Path $Out "bytediff-$abi.json"
    Step "byte_diff $abi" 'byte_diff.py' @($apkA, $apkB, $jA, $jB, $bdJson) (Join-Path $Out "bytediff-$abi.txt")
    $script:outputs.Add($bdJson)
    $imyJson = Join-Path $Out "imydiff-$abi.json"
    Step "imy_diff $abi" 'imy_diff.py' @(
        $apkA, $apkB, '--label-a', $ta, '--label-b', $tb,
        '--out', $imyJson) (Join-Path $Out "imydiff-$abi.txt")
    $script:outputs.Add($imyJson)
}

# Out-File -Encoding utf8 emits a UTF-8 BOM on Windows PowerShell 5.1. A BOM at
# the head of a captured report is noise, and in SHA256SUMS.txt it makes the
# first line unparseable by `sha256sum -c`. Strip it from every file this run
# produced (the JSON outputs are written by the Python tools and never carry one).
$bom = [byte[]](0xEF, 0xBB, 0xBF)
foreach ($f in ($script:outputs | Sort-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $f)) { continue }
    $bytes = [IO.File]::ReadAllBytes($f)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq $bom[0] -and $bytes[1] -eq $bom[1] -and $bytes[2] -eq $bom[2]) {
        [IO.File]::WriteAllBytes($f, $bytes[3..($bytes.Length - 1)])
    }
}

$lines = @()
foreach ($f in ($script:outputs | Sort-Object -Unique)) {
    if (-not (Test-Path -LiteralPath $f)) {
        $script:failures += ("output missing: {0}" -f (Split-Path -Leaf $f))
        continue
    }
    $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $f).Hash.ToLower()
    $lines += ("{0}  {1}" -f $h, (Split-Path -Leaf $f))
}
# Written as raw bytes, not with WriteAllLines: on Windows that emits CRLF, and
# `sha256sum -c` then reads the trailing \r as part of the file name and reports
# every entry as "No such file or directory". SHA-256 manifests are consumed by
# POSIX tools, so the line ending must be LF. The encoding is UTF-8 with no BOM
# for the same reason (a BOM corrupts the first line).
$sumsPath = Join-Path $Out 'SHA256SUMS.txt'
$sumsBytes = (New-Object Text.UTF8Encoding($false)).GetBytes(($lines -join "`n") + "`n")
[IO.File]::WriteAllBytes($sumsPath, $sumsBytes)

Write-Host ""
Write-Host ("driver-generated output files hashed: {0}" -f $lines.Count)
if ($script:failures.Count -gt 0) {
    Write-Host "FAILURES:"
    $script:failures | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "all steps exited 0"
exit 0
