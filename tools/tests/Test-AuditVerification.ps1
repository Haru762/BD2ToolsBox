# Tests only extracted functions; never sources the top-level device/build setup.
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../verification.ps1'
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw "Parse errors: $errors" }
foreach ($name in @('Assert-Bd2InstrumentationResult','Test-Bd2Unit','Read-Bd2UnitXml','Get-Bd2UnitFixtures','Write-Bd2Json')) {
    $node = $ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name}, $true)
    . ([scriptblock]::Create($node.Extent.Text))
}
$failures = @(); $passed = 0
function Check($label, $action) {
    try { & $action; $script:passed++; Write-Output "PASS $label" }
    catch { $script:failures += "$label : $($_.Exception.Message)"; Write-Output "FAIL $label : $($_.Exception.Message)" }
}
function MustThrow($action) {
    $thrown = $false
    try { & $action | Out-Null } catch { $thrown = $true }
    if (-not $thrown) { throw 'Unsafe result was accepted' }
}
$protocol = "INSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 1`nINSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 0`n"
$finish = "INSTRUMENTATION_RESULT: stream=OK (1 test)`nINSTRUMENTATION_CODE: -1"
Check 'complete protocol' { if ((Assert-Bd2InstrumentationResult @{output=$protocol+$finish;adbExit=0} 'probe') -ne 1) { throw 'Wrong count' } }
Check 'missing terminal' { MustThrow { Assert-Bd2InstrumentationResult @{output=$protocol;adbExit=0} 'probe' } }
Check 'crash after one test' { MustThrow { Assert-Bd2InstrumentationResult @{output=$protocol+"INSTRUMENTATION_RESULT: shortMsg=Process crashed.`nINSTRUMENTATION_CODE: 0";adbExit=0} 'probe' } }
Check 'transport failed after pass' { MustThrow { Assert-Bd2InstrumentationResult @{output=$protocol+$finish;adbExit=1} 'probe' } }
Check 'declared tests not all started' { MustThrow { Assert-Bd2InstrumentationResult @{output="INSTRUMENTATION_STATUS: numtests=2`n"+$protocol+$finish;adbExit=0} 'probe' } }
Check 'unknown raw status cannot hide in a passing run' {
    MustThrow { Assert-Bd2InstrumentationResult @{output=$protocol+"INSTRUMENTATION_STATUS_CODE: 99`n"+$finish;adbExit=0} 'probe' }
}
Check 'passing status must belong to the test which started' {
    $mismatch = "INSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 1`nINSTRUMENTATION_STATUS: test=other`nINSTRUMENTATION_STATUS_CODE: 0`n"
    MustThrow { Assert-Bd2InstrumentationResult @{output=$mismatch+$finish;adbExit=0} 'probe' }
}
Check 'passing status cannot precede its start' {
    $reversed = "INSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 0`nINSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 1`n"
    MustThrow { Assert-Bd2InstrumentationResult @{output=$reversed+$finish;adbExit=0} 'probe' }
}
Check 'raw status after terminal is not a complete run' {
    MustThrow { Assert-Bd2InstrumentationResult @{output=$finish+"`n"+$protocol;adbExit=0} 'probe' }
}
Check 'skip without a test name is still not a pass' {
    MustThrow { Assert-Bd2InstrumentationResult @{output=$protocol+"INSTRUMENTATION_STATUS_CODE: -3`n"+$finish;adbExit=0} 'probe' }
}
Check 'progress status remains supported' {
    $progress = "INSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 1`nINSTRUMENTATION_STATUS: stream=progress`nINSTRUMENTATION_STATUS_CODE: 2`nINSTRUMENTATION_STATUS: test=one`nINSTRUMENTATION_STATUS_CODE: 0`n"
    if ((Assert-Bd2InstrumentationResult @{output=$progress+$finish;adbExit=0} 'probe') -ne 1) { throw 'Wrong count' }
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ('bd2-audit-harness-' + [guid]::NewGuid().ToString('N'))
$Root = $temp; $results = Join-Path $temp 'xml'; $Bd2FixtureManifest = Join-Path $temp 'fixtures.json'
New-Item -ItemType Directory $results -Force | Out-Null
try {
    $fixture = Join-Path $temp 'fixture.txt'; [IO.File]::WriteAllText($fixture, 'fixture')
    $hash = (Get-FileHash $fixture -Algorithm SHA256).Hash.ToLowerInvariant()
    @{fixtures=@(@{id='a';usedBy=@('app/src/test/java/test/A.kt');relativePath='fixture.txt';sha256=$hash;requiredness='g0-required'},@{id='b';usedBy=@('app/src/test/java/test/B.kt');relativePath='fixture.txt';sha256=$hash;requiredness='g0-required'})} | ConvertTo-Json -Depth 6 | Set-Content $Bd2FixtureManifest -Encoding UTF8
    function Assert-Bd2Environment {}
    function Get-Bd2GitState { return @{head='isolated-test';dirty=$false} }
    $script:unitTasks = @(':app:testDebugUnitTest')
    function Get-Bd2UnitTestTasks { return @{tasks=$script:unitTasks;variant='test'} }
    function New-Bd2RunDirectory { param($Label); $script:run=Join-Path $temp ([guid]::NewGuid().ToString('N')); New-Item -ItemType Directory $script:run | Out-Null; return $script:run }
    function Get-Bd2UnitResultsDir { param($Task); return $results }
    function Move-Bd2UnitResultsToRecycleBin { param($ResultsDir) }
    function Get-Bd2Sha256 { param($Path); return (Get-FileHash $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
    $script:xmlMode = 'pass'
    function Invoke-Bd2GradleRaw {
        $script:Bd2LastExitCode = 0
        foreach ($class in @('test.A','test.B')) {
            $cases = "<testcase name='$($args[0])'/>"
            $skipped = 0
            if ($script:xmlMode -eq 'empty-cases') { $cases = '' }
            if ($script:xmlMode -eq 'hidden-skip') { $cases = "<testcase name='works'><skipped/></testcase>" }
            if ($script:xmlMode -eq 'skip') { $cases = "<testcase name='works'><skipped/></testcase>"; $skipped = 1 }
            [IO.File]::WriteAllText((Join-Path $results "TEST-$class.xml"), "<testsuite name='$class' tests='1' failures='0' errors='0' skipped='$skipped'>$cases</testsuite>")
        }
    }
    Check 'wildcard selects real XML and both fixtures' {
        Test-Bd2Unit -TestClass 'test.*'
        $report = Get-Content (Join-Path $script:run 'summary.json') -Raw | ConvertFrom-Json
        if ($report.result -ne 'PASS' -or $report.counts.tests -ne 2 -or @($report.fixtures).Count -ne 2) { throw 'Wildcard lost tests or fixtures' }
    }
    Check 'both ABI reports retain their own XML evidence' {
        $script:unitTasks = @(':app:testArm64DebugUnitTest', ':app:testX64DebugUnitTest')
        try {
            Test-Bd2Unit -TestClass 'test.*'
            $files = @(Get-ChildItem -LiteralPath $script:run -Recurse -Filter 'TEST-*.xml' -File)
            if ($files.Count -ne 4) { throw "Expected four archived XML files, got $($files.Count)" }
            foreach ($task in $script:unitTasks) {
                foreach ($class in @('test.A', 'test.B')) {
                    $path = Join-Path (Join-Path $script:run $task.Split(':')[-1]) "TEST-$class.xml"
                    $xml = [xml](Get-Content -LiteralPath $path -Raw)
                    if ($xml.testsuite.testcase.name -ne $task) { throw 'Another ABI overwrote the archived testcase' }
                }
            }
        } finally { $script:unitTasks = @(':app:testDebugUnitTest') }
    }
    Check 'unmatched selector fails' { MustThrow { Test-Bd2Unit -TestClass 'absent.*' } }
    Check 'AllowSkip cannot waive selected JVM tests' {
        $script:xmlMode = 'skip'
        try { MustThrow { Test-Bd2Unit -TestClass 'test.*' -AllowSkip } }
        finally { $script:xmlMode = 'pass' }
    }
    Check 'testcase skip cannot be hidden by a zero skipped attribute' {
        $script:xmlMode = 'hidden-skip'
        try { MustThrow { Test-Bd2Unit -TestClass 'test.*' } }
        finally { $script:xmlMode = 'pass' }
    }
    Check 'nonzero tests attribute is not proof of executed testcases' {
        $script:xmlMode = 'empty-cases'
        try { MustThrow { Test-Bd2Unit -TestClass 'test.*' } }
        finally { $script:xmlMode = 'pass' }
    }
} finally {
    # This exact directory was created by this test. No project or device paths.
    [IO.Directory]::Delete($temp, $true)
}
Write-Output "Tests: $($passed + $failures.Count), passed=$passed, failed=$($failures.Count)"
if ($failures.Count) { throw ($failures -join '; ') }
