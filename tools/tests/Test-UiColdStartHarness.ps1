# Host-only tests. Fake adb writes only to this run's retained temporary directory.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../verification.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "Parse errors: $errors" }
$smoke = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-Bd2ReleaseSmoke' }, $true)
if ($smoke.Extent.Text -match 'uiColdStartVerified\s*=\s*\$true') {
    throw 'ASSERT: UI chrome alone must not certify snapshot cold-start restoration'
}
foreach ($name in @('Assert-Bd2UiChrome', 'Invoke-Bd2UiColdStart')) {
    $node = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true)
    if (-not $node) { throw "ASSERT: missing $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}
function Assert-Throws($action, $message) {
    $caught = $null
    try { & $action | Out-Null } catch { $caught = $_ }
    if (-not $caught -or $caught.Exception.Message -notmatch $message) { throw "ASSERT: expected $message; got $caught" }
    $script:passed++
}
$script:passed = 0
$good = '<hierarchy><node package="com.bd2toolsbox" content-desc="搜索"/><node package="com.bd2toolsbox" content-desc="筛选"/></hierarchy>'
$dialog = '<hierarchy><node package="com.bd2toolsbox" text="需要建立文件索引"/><node package="com.bd2toolsbox" text="跳过" bounds="[10,20][30,40]"/></hierarchy>'
Assert-Bd2UiChrome -XmlText $good | Out-Null; $passed++
Assert-Throws { Assert-Bd2UiChrome -XmlText ($good.Replace('com.bd2toolsbox','com.android.settings')) } 'main chrome'
Assert-Throws { Assert-Bd2UiChrome -XmlText $dialog } 'main chrome'
Assert-Throws { Assert-Bd2UiChrome -XmlText '<hierarchy><node' } 'XML'
Assert-Throws { Assert-Bd2UiChrome -XmlText '<!DOCTYPE hierarchy [<!ENTITY x SYSTEM "file:///not-allowed">]><hierarchy>&x;</hierarchy>' } 'XML'
$temp = Join-Path ([IO.Path]::GetTempPath()) ('bd2-ui-harness-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$Bd2Adb = Join-Path $temp 'fake-adb.ps1'
@'
$global:LASTEXITCODE = 0
$a = @($args); $global:uiCalls += ($a -join ' ')
if ($a[3] -eq 'am') {
    if ($global:uiFailure -eq 'launch') { $global:LASTEXITCODE=1 }
    "Status: ok\nLaunchState: __STATE__\nActivity: com.bd2toolsbox/.MainActivity".Replace('\n', [Environment]::NewLine).Replace('__STATE__', $global:uiLaunchState)
} elseif ($a[3] -eq 'uiautomator') {
    if ($global:uiFailure -eq 'dump') { $global:LASTEXITCODE=1 }
    'UI hierarchy dumped'
} elseif ($a[2] -eq 'pull') {
    if ($global:uiFailure -eq 'pull') { $global:LASTEXITCODE=1; return }
    $global:uiPullCount++
    $xml = if ($global:uiPullCount -eq 1) { $global:uiFirst } else { $global:uiSecond }
    [IO.File]::WriteAllText($a[4], $xml)
} elseif ($a[3] -eq 'input' -and $global:uiFailure -eq 'tap') { $global:LASTEXITCODE=1 }
'@ | Set-Content -LiteralPath $Bd2Adb -Encoding utf8
function Stop-Bd2ReleaseApp { param($Serial); $global:uiCalls += 'stop' }
function Start-Sleep { param($Seconds) }
function Reset-Fake {
    $global:uiCalls=@(); $global:uiFailure=''; $global:uiPullCount=0
    $global:uiFirst=$good; $global:uiSecond=$good; $global:uiLaunchState='COLD'
}
Reset-Fake
$result = Invoke-Bd2UiColdStart -Serial fake -RunDirectory $temp
if (-not $result.uiChromeColdStartVerified -or $result.uiColdStartVerified -or $global:uiCalls[0] -ne 'stop') { throw 'ASSERT: incorrect evidence scope/order' }; $passed++
Reset-Fake; $global:uiFirst=$dialog
$result = Invoke-Bd2UiColdStart -Serial fake -RunDirectory $temp
if ($global:uiPullCount -ne 2 -or -not ($global:uiCalls -match 'input tap 20 30')) { throw 'ASSERT: skip must re-read final screen' }; $passed++
Reset-Fake; $global:uiFirst=$dialog; $global:uiSecond=$dialog
Assert-Throws { Invoke-Bd2UiColdStart -Serial fake -RunDirectory $temp } 'main chrome'
foreach ($failure in @('launch','dump','pull','tap')) {
    Reset-Fake; $global:uiFailure=$failure; $global:uiFirst=$dialog
    Assert-Throws { Invoke-Bd2UiColdStart -Serial fake -RunDirectory $temp } 'failed'
}
Reset-Fake; $global:uiLaunchState='HOT'
Assert-Throws { Invoke-Bd2UiColdStart -Serial fake -RunDirectory $temp } 'COLD'
Reset-Fake; $global:uiFirst=$good.Replace('com.bd2toolsbox','com.android.settings')
Assert-Throws { Invoke-Bd2UiColdStart -Serial fake -RunDirectory $temp } 'main chrome'
Write-Output "PASS: $passed host-only UI cold-start tests; retained artifacts: $temp"
