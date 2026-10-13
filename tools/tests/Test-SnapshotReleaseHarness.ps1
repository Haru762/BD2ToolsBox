# Host-only contract tests: no adb, Gradle, app install or user files are touched.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../verification.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "PowerShell parse errors: $errors" }
foreach ($name in @('Assert-Bd2InstrumentationResult', 'Invoke-Bd2SnapshotPhases', 'Get-Bd2MappingForAbi', 'Get-Bd2CurrentReleaseArtifact', 'Get-Bd2ReleaseSmokeCase')) {
    $node = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true)
    if (-not $node) { throw "ASSERT: missing implementation $name" }
    . ([scriptblock]::Create($node.Extent.Text))
}
$script:calls = @()
$script:failure = ''
$script:samePid = $false
$script:missingMarker = $false
function Stop-Bd2ReleaseApp { param($Serial); $script:calls += 'stop' }
function Invoke-Bd2Instrumentation {
    param($Serial, $TestClass, $Method, $TestPackage, $Arguments)
    if ($Serial -ne 'fake-serial' -or $TestPackage -ne 'com.bd2toolsbox.releaseprobe' -or
        $TestClass -ne 'com.bd2toolsbox.integration.ReleaseSnapshotProbeTest' -or $Arguments.bd2RunId -ne 'test-run-123') {
        throw 'ASSERT: incorrect instrumentation arguments'
    }
    $phase = $Method -replace 'Snapshot$', ''
    $script:calls += $phase
    if ($script:failure -eq $phase) { throw "injected-$phase" }
    $pidValue = if ($phase -eq 'prepare' -or $script:samePid) { 100 } else { 101 }
    # 真实设备把 marker 经 sendStatus(2, REPORT_KEY_STREAMRESULT) 发出：它是一个
    # "INSTRUMENTATION_STATUS: stream=..." 块 + "INSTRUMENTATION_STATUS_CODE: 2"（进度），
    # 夹在开始(1)与通过(0)之间，而非裸文本（旧行为只进 logcat，runner 拒绝）。
    # 缺 marker 时该块整体不出现，模拟只进 logcat 那个真实失败。
    $markerBlock = if ($script:missingMarker) { '' } else {
        "INSTRUMENTATION_STATUS: stream=BD2_SNAPSHOT_PROBE phase=$phase runId=test-run-123 pid=$pidValue entries=5`nINSTRUMENTATION_STATUS_CODE: 2`n"
    }
    return @{ adbExit = 0; output = "INSTRUMENTATION_STATUS: test=$Method`nINSTRUMENTATION_STATUS_CODE: 1`n$markerBlock" + "INSTRUMENTATION_STATUS: test=$Method`nINSTRUMENTATION_STATUS_CODE: 0`nINSTRUMENTATION_RESULT: stream=OK (1 test)`nINSTRUMENTATION_CODE: -1" }
}
function Assert-Equal($expected, $actual, $label) {
    if ($expected -ne $actual) { throw "ASSERT $label expected=[$expected], actual=[$actual]" }
}
$temp = Join-Path ([IO.Path]::GetTempPath()) ('bd2-harness-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
$passed = 0
try {
    $result = Invoke-Bd2SnapshotPhases -Serial fake-serial -RunId test-run-123 -RunDirectory $temp
    Assert-Equal 'stop,prepare,stop,restore,cleanup' ($script:calls -join ',') 'phase order'
    Assert-Equal 100 $result.preparePid 'prepare pid'
    Assert-Equal 101 $result.restorePid 'restore pid'
    $passed++
    foreach ($phase in @('prepare', 'restore', 'cleanup')) {
        $script:calls = @(); $script:failure = $phase
        $caught = $null
        try { Invoke-Bd2SnapshotPhases -Serial fake-serial -RunId test-run-123 -RunDirectory $temp | Out-Null } catch { $caught = $_ }
        if (-not $caught -or $caught.Exception.Message -notmatch "injected-$phase") { throw "ASSERT: did not preserve $phase failure" }
        Assert-Equal cleanup $script:calls[-1] 'cleanup attempted on failure'
        $passed++
    }
    $script:failure = ''; $script:samePid = $true; $script:calls = @()
    $caught = $null
    try { Invoke-Bd2SnapshotPhases -Serial fake-serial -RunId test-run-123 -RunDirectory $temp | Out-Null } catch { $caught = $_ }
    if (-not $caught -or $caught.Exception.Message -notmatch 'PID') { throw 'ASSERT: same process was accepted as a restart' }
    Assert-Equal cleanup $script:calls[-1] 'cleanup after PID failure'; $passed++
    $script:samePid = $false; $script:missingMarker = $true; $script:calls = @()
    $caught = $null
    try { Invoke-Bd2SnapshotPhases -Serial fake-serial -RunId test-run-123 -RunDirectory $temp | Out-Null } catch { $caught = $_ }
    if (-not $caught) { throw 'ASSERT: absent phase marker accepted' }
    Assert-Equal cleanup $script:calls[-1] 'cleanup after absent marker'; $passed++
    $script:missingMarker = $false; $script:calls = @()
    $caught = $null
    try { Invoke-Bd2SnapshotPhases -Serial fake-serial -RunId '../unsafe' -RunDirectory $temp | Out-Null } catch { $caught = $_ }
    if (-not $caught) { throw 'ASSERT: unsafe run id accepted' }
    Assert-Equal 0 $script:calls.Count 'unsafe id must fail before any device call'; $passed++

    # 直接驱动真实 Assert-Bd2InstrumentationResult，核实它按原始协议判定：
    # 开始(1) + 进度 status2 若干 + 通过(0)一次 → 起止数匹配，返回 1。
    # marker 走 stream/进度码 2，不得被算进起止计数（started/passed）。
    $protoOk = @{ adbExit = 0; output = @(
        'INSTRUMENTATION_STATUS: test=protoOk'
        'INSTRUMENTATION_STATUS_CODE: 1'
        'INSTRUMENTATION_STATUS: stream=BD2_SNAPSHOT_PROBE phase=prepare runId=r pid=100 entries=5'
        'INSTRUMENTATION_STATUS_CODE: 2'
        'INSTRUMENTATION_STATUS: stream=BD2_SNAPSHOT_PROBE phase=prepare runId=r pid=100 entries=5'
        'INSTRUMENTATION_STATUS_CODE: 2'
        'INSTRUMENTATION_STATUS: test=protoOk'
        'INSTRUMENTATION_STATUS_CODE: 0'
        'INSTRUMENTATION_RESULT: stream=OK (1 test)'
        'INSTRUMENTATION_CODE: -1'
    ) -join "`n" }
    Assert-Equal 1 (Assert-Bd2InstrumentationResult -Result $protoOk -Label 'proto ok') 'protocol status2 progress accepted'; $passed++

    # 进度块误用通过码 0（而非 2）必须拒绝；可以在身份配对或汇总计数阶段发现，
    # 不把拒绝发生的阶段或中文错误文案当作契约。
    $protoBad = @{ adbExit = 0; output = @(
        'INSTRUMENTATION_STATUS: test=protoBad'
        'INSTRUMENTATION_STATUS_CODE: 1'
        'INSTRUMENTATION_STATUS: stream=BD2_SNAPSHOT_PROBE phase=prepare runId=r pid=100 entries=5'
        'INSTRUMENTATION_STATUS_CODE: 0'
        'INSTRUMENTATION_STATUS: test=protoBad'
        'INSTRUMENTATION_STATUS_CODE: 0'
        'INSTRUMENTATION_RESULT: stream=OK (1 test)'
        'INSTRUMENTATION_CODE: -1'
    ) -join "`n" }
    $caught = $null
    try { Assert-Bd2InstrumentationResult -Result $protoBad -Label 'proto bad' | Out-Null } catch { $caught = $_ }
    if (-not $caught) { throw 'ASSERT: mismatched progress code accepted' }
    $passed++
    # Current-code smoke must build release and retain an R8 mapping, never select the frozen APK.
    $Root = $temp
    $mappingDir = Join-Path $Root 'app/build/outputs/mapping/x64Release'
    New-Item -ItemType Directory -Path $mappingDir -Force | Out-Null
    $mappingFile = Join-Path $mappingDir 'mapping.txt'
    Set-Content -LiteralPath $mappingFile -Value 'test mapping only' -Encoding utf8
    function Invoke-Bd2Gradle { $script:gradleArgs = $args }
    function Get-Bd2ApkForAbi {
        param($BuildType, $Abi)
        Assert-Equal release $BuildType 'current release build type'
        Assert-Equal x86_64 $Abi 'current release ABI'
        return (Join-Path $Root 'app/build/outputs/apk/x64/release/app-x64-release.apk')
    }
    function Get-Bd2Sha256 { param($Path); return ('a' * 64) }
    $artifact = Get-Bd2CurrentReleaseArtifact -Abi x86_64
    Assert-Equal ':app:assembleX64Release,--console=plain' ($script:gradleArgs -join ',') 'build only the requested ABI'
    Assert-Equal 'app/build/outputs/apk/x64/release/app-x64-release.apk' $artifact.relative 'not frozen baseline'
    Assert-Equal ('a' * 64) $artifact.mappingSha256 'mapping evidence'; $passed++
    Set-Content -LiteralPath $mappingFile -Value '' -NoNewline
    $caught = $null
    try { Get-Bd2CurrentReleaseArtifact -Abi x86_64 | Out-Null } catch { $caught = $_ }
    if (-not $caught -or $caught.Exception.Message -notmatch 'R8 mapping') { throw 'ASSERT: empty R8 mapping accepted' }
    $passed++
    foreach ($abi in @('x86_64', 'arm64-v8a')) {
        $flavor = if ($abi -eq 'x86_64') { 'x64' } else { 'arm64' }
        $flavorMapping = Join-Path $Root "app/build/outputs/mapping/${flavor}Release/mapping.txt"
        New-Item -ItemType Directory -Path (Split-Path -Parent $flavorMapping) -Force | Out-Null
        Set-Content -LiteralPath $flavorMapping -Value "$flavor mapping" -Encoding utf8
        Assert-Equal $flavorMapping (Get-Bd2MappingForAbi -Abi $abi) 'mapping belongs to selected flavor'
        $passed++
    }

    # Exercise the real argument adapter against a local fake executable, never adb.
    $node = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Invoke-Bd2Instrumentation' }, $true)
    . ([scriptblock]::Create($node.Extent.Text))
    $Bd2Adb = Join-Path $temp 'fake-adb.ps1'
    $Bd2InstrumentationRunner = 'fake.Runner'
    Set-Content -LiteralPath $Bd2Adb -Value '$global:Bd2HarnessCapturedArgs = @($args); $global:LASTEXITCODE = 0; "fake-output"' -Encoding utf8
    $actual = Invoke-Bd2Instrumentation -Serial fake -TestClass Probe -Method prepareSnapshot -TestPackage probe -Arguments @{bd2RunId='safe-run-1'}
    Assert-Equal 'fake-output' $actual.output 'adapter output'
    Assert-Equal '-s,fake,shell,am,instrument,-w,-r,-e,class,Probe#prepareSnapshot,-e,bd2RunId,safe-run-1,probe/fake.Runner' ($global:Bd2HarnessCapturedArgs -join ',') 'adapter arguments'; $passed++
    $global:Bd2HarnessCapturedArgs = @()
    $caught = $null
    try { Invoke-Bd2Instrumentation -Serial fake -TestClass Probe -Arguments @{bd2RunId='unsafe;command'} | Out-Null } catch { $caught = $_ }
    if (-not $caught) { throw 'ASSERT: shell metacharacters accepted' }
    Assert-Equal 0 $global:Bd2HarnessCapturedArgs.Count 'unsafe extra argument fails before adb'; $passed++
    Remove-Variable -Name Bd2HarnessCapturedArgs -Scope Global -ErrorAction SilentlyContinue
    # Dispatch itself must preserve frozen-baseline behavior and device preflight ordering.
    $node = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-Bd2ReleaseSmoke' }, $true)
    . ([scriptblock]::Create($node.Extent.Text))
    function Assert-Bd2AndroidTools {}
    function Get-Bd2ReleaseArtifact { param($Abi); $script:selection += 'baseline'; throw 'selection-sentinel' }
    function Get-Bd2Device { param($Abi); $script:selection += 'device'; return @{serial='fake';abi=$Abi} }
    function Assert-Bd2DeviceOnline { param($Device); $script:selection += 'online'; return $Device }
    function Get-Bd2CurrentReleaseArtifact { param($Abi); $script:selection += 'current'; throw 'selection-sentinel' }
    foreach ($case in @('baseline-startup', 'snapshot-compatibility', 'unimplemented-case')) {
        $script:selection = @(); $caught = $null
        try { Test-Bd2ReleaseSmoke -Case $case -Abi x86_64 } catch { $caught = $_ }
        if (-not $caught) { throw 'ASSERT: dispatch sentinel did not stop execution' }
        $expected = switch ($case) { 'baseline-startup' {'baseline'} 'snapshot-compatibility' {'device,online,current'} default {''} }
        Assert-Equal $expected ($script:selection -join ',') 'release case dispatch'; $passed++
    }
    Write-Output "PASS: $passed host-only snapshot harness tests"
} finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($resolved) -eq $parent -and [IO.Path]::GetFileName($resolved) -match '^bd2-harness-test-[a-f0-9]{32}$') {
        Add-Type -AssemblyName Microsoft.VisualBasic
        [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory(
            $resolved, [Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,
            [Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin,
            [Microsoft.VisualBasic.FileIO.UICancelOption]::ThrowException)
    } else { throw 'Refused unsafe test directory cleanup' }
}
