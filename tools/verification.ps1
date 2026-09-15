param(
    [string]$ProjectRoot = 'D:/bd2 mod/worktrees/bdroid-android-refactor',
    [ValidateSet('Load', 'LocalAll')][string]$Mode = 'Load'
)
$ErrorActionPreference = 'Stop'
$Root = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
$ScriptRepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
if (-not [string]::Equals($Root, $ScriptRepoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Load verification.ps1 from the selected repository, not another checkout'
}
function Assert-Bd2Workspace {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { throw 'Implementation root is missing' }
    $top = (git -c safe.directory=$Root -C $Root rev-parse --show-toplevel) -join ''
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($top)) { throw 'Cannot identify Git worktree' }
    $top = [IO.Path]::GetFullPath($top.Trim()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not [string]::Equals($top, $Root, [StringComparison]::OrdinalIgnoreCase)) { throw 'Wrong repository root' }
    $branch = (git -c safe.directory=$Root -C $Root branch --show-current) -join ''
    if ($LASTEXITCODE -ne 0 -or $branch.Trim() -ne 'refactor/android-architecture') {
        throw 'Verification must run on the authorized refactor branch, never main or detached HEAD'
    }
    foreach ($relative in @('docs/refactor/handoff-manifest.json', 'docs/refactor/artifact-manifest.json')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Leaf)) {
            throw "T01 handoff is incomplete: $relative"
        }
    }
}
Assert-Bd2Workspace
# 刻意不 Set-Location：点源不得改动调用方的当前目录，所有动作显式带 -p $Root / -C $Root
$Gradlew = Join-Path $Root 'gradlew.bat'
$ArtifactRoot = Join-Path $Root 'local/refactor-artifacts'
$BeforeApk = Join-Path $ArtifactRoot 'pre-shrink/app-arm64-v8a-release.apk'
$BeforeX64Apk = Join-Path $ArtifactRoot 'pre-shrink/app-x86_64-release.apk'
$Python = $null
$localEnvironment = $null
$environmentFile = Join-Path $Root 'docs/refactor/evidence/environment.local.json'
if (Test-Path -LiteralPath $environmentFile -PathType Leaf) {
    $localEnvironment = Get-Content -LiteralPath $environmentFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $Python = [string]$localEnvironment.testPythonExe
    $javaHome = [string]$localEnvironment.javaHome
    if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin/java.exe') -PathType Leaf)) {
            throw 'Configured Gradle JDK is missing'
        }
        $env:JAVA_HOME = $javaHome
    }
}

# ---------------------------------------------------------------------------
# T04：显式环境加载与运行期状态
#
# 点源（Mode=Load）只做三件事：核对工作区/分支、读取本地配置、定义并校验入口。
# 它不构建、不安装、不清数据、不 exit —— 所有真正跑命令的动作都在显式调用
# Test-Bd2* 或 -Mode LocalAll 时才发生。
# ---------------------------------------------------------------------------

$Bd2Adb = $null
$Bd2Apksigner = $null
$Bd2Devices = @()
$Bd2LastExitCode = 1
$Bd2TaskList = $null
$Bd2ResultsRoot = Join-Path $ArtifactRoot 'test-results'
$Bd2FixtureManifest = Join-Path $Root 'docs/refactor/fixture-manifest.json'
$Bd2InstrumentationRunner = 'androidx.test.runner.AndroidJUnitRunner'

if ($null -ne $localEnvironment) {
    $Bd2Adb = [string]$localEnvironment.adbExe
    $Bd2Devices = @($localEnvironment.deviceWhitelist)
    $sdkDir = [string]$localEnvironment.sdkDir
    $buildTools = [string]$localEnvironment.buildToolsVersion
    if (-not [string]::IsNullOrWhiteSpace($sdkDir) -and -not [string]::IsNullOrWhiteSpace($buildTools)) {
        $candidate = Join-Path (Join-Path $sdkDir 'build-tools') (Join-Path $buildTools 'apksigner.bat')
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $Bd2Apksigner = $candidate }
    }
}

function Assert-Bd2Environment {
    # 命令入口每次重新核对：不能因为「点源时读到过」就假定后续仍然成立
    Assert-Bd2Workspace
    if ([string]::IsNullOrWhiteSpace($Python) -or -not (Test-Path -LiteralPath $Python -PathType Leaf)) {
        throw 'T02 must record an executable testPythonExe'
    }
}

function Assert-Bd2AndroidTools {
    Assert-Bd2Workspace
    if ([string]::IsNullOrWhiteSpace($Bd2Adb) -or -not (Test-Path -LiteralPath $Bd2Adb -PathType Leaf)) {
        throw 'T02 must record an executable adbExe'
    }
    if ([string]::IsNullOrWhiteSpace($Bd2Apksigner) -or -not (Test-Path -LiteralPath $Bd2Apksigner -PathType Leaf)) {
        throw 'apksigner was not found under the recorded SDK build-tools'
    }
}

function Get-Bd2Sha256 {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Cannot hash missing file: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Bd2Json {
    param([Parameter(Mandatory=$true)]$Value, [Parameter(Mandatory=$true)][string]$Path)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir -PathType Container)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    # 裸字节、UTF-8 无 BOM：Set-Content/Out-File 在 Windows PowerShell 5.1 下会加 BOM，
    # 让下游按 UTF-8 解析的读取方（Python json）把首字符吃成 \ufeff（T03 已记录过同类坑）
    $json = $Value | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

function New-Bd2RunDirectory {
    param([Parameter(Mandatory=$true)][string]$Label)
    $safe = ($Label -replace '[^A-Za-z0-9._-]+', '_').Trim('_')
    if ([string]::IsNullOrWhiteSpace($safe)) { $safe = 'run' }
    # 时间戳只到秒：同一入口在同一秒内跑两次会**撞名**，而 New-Item -Force 会静默复用
    # 目录 —— 后一次运行直接覆盖前一次的 summary.json，证据就没了。这不是假设：
    # 一次变异实验的 FAIL 摘要被紧随其后的「还原后重跑」盖成了 PASS，事后只能靠
    # 控制台记录才发现。因此撞名时加序号，绝不写进已有目录。
    $base = Join-Path $Bd2ResultsRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $safe)
    $dir = $base
    $n = 2
    while (Test-Path -LiteralPath $dir) {
        $dir = $base + '-' + $n
        $n++
    }
    New-Item -ItemType Directory -Path $dir | Out-Null
    return $dir
}

function Get-Bd2GitState {
    $head = ((git -c safe.directory=$Root -C $Root rev-parse HEAD) -join '').Trim()
    $dirty = -not [string]::IsNullOrWhiteSpace(((git -c safe.directory=$Root -C $Root status --porcelain) -join ''))
    return @{ head = $head; dirty = $dirty }
}

function Get-Bd2GradleTaskList {
    # Gradle 任务清单是真值来源：变体（T49 的 arm64/x64 flavors）是否存在由实际任务决定，
    # 不靠脚本里的字符串猜测。
    if ($null -eq $script:Bd2TaskList) {
        Invoke-Bd2Gradle :app:tasks --all --console=plain
        if ($script:Bd2LastExitCode -ne 0) { throw 'Cannot enumerate Gradle tasks' }
    }
    return $script:Bd2TaskList
}

function Invoke-Bd2GradleRaw {
    # 不抛异常的版本：测试任务失败时仍要读报告/归档产物，所以退出码由调用方自己判。
    # T04 修正 T02 移交的偏差：显式 -p $Root，不再依赖调用方当前工作目录。
    Assert-Bd2Workspace
    if (-not (Test-Path -LiteralPath $Gradlew -PathType Leaf)) { throw 'Gradle wrapper is missing' }
    $script:Bd2LastExitCode = 1
    & $Gradlew -p $Root @args
    $script:Bd2LastExitCode = $LASTEXITCODE
}

function Invoke-Bd2Gradle {
    Invoke-Bd2GradleRaw @args
    if ($script:Bd2LastExitCode -ne 0) { throw "Gradle command failed (exit $script:Bd2LastExitCode)" }
}

function Invoke-Bd2PythonRaw {
    Assert-Bd2Environment
    $script:Bd2LastExitCode = 1
    & $Python @args
    $script:Bd2LastExitCode = $LASTEXITCODE
}

function Invoke-Bd2Python {
    Invoke-Bd2PythonRaw @args
    if ($script:Bd2LastExitCode -ne 0) { throw "Python command failed (exit $script:Bd2LastExitCode)" }
}

function Show-Bd2Dependencies {
    param([string]$Dependency)
    $tasks = (Get-Bd2GradleTaskList) -join [Environment]::NewLine
    $configs = if ($tasks -match '(?m)^\s*testArm64DebugUnitTest(?:\s|$)' -and
                   $tasks -match '(?m)^\s*testX64DebugUnitTest(?:\s|$)') {
        @('arm64ReleaseRuntimeClasspath', 'x64ReleaseRuntimeClasspath')
    } else { @('releaseRuntimeClasspath') }
    foreach ($configuration in $configs) {
        if ([string]::IsNullOrWhiteSpace($Dependency)) {
            Invoke-Bd2Gradle :app:dependencies --configuration $configuration --console=plain
        } else {
            Invoke-Bd2Gradle :app:dependencyInsight --dependency $Dependency --configuration $configuration --console=plain
        }
    }
}

# ---------------------------------------------------------------------------
# 变体解析
# ---------------------------------------------------------------------------

function Get-Bd2UnitTestTasks {
    $tasks = (Get-Bd2GradleTaskList) -join [Environment]::NewLine
    # T49 会给出单 ABI flavors（arm64/x64）。有就用真实的 per-flavor 任务；还没有就
    # 回退到 testDebugUnitTest —— 但映射必须由**实际任务名**证实，两者都不存在即非零。
    if ($tasks -match '(?m)^\s*testArm64DebugUnitTest(?:\s|$)' -and
        $tasks -match '(?m)^\s*testX64DebugUnitTest(?:\s|$)') {
        return @{ tasks = @(':app:testArm64DebugUnitTest', ':app:testX64DebugUnitTest'); variant = 'arm64Debug+x64Debug' }
    }
    if ($tasks -match '(?m)^\s*testDebugUnitTest(?:\s|$)') {
        return @{ tasks = @(':app:testDebugUnitTest'); variant = 'debug-shared-abi' }
    }
    throw 'No supported unit-test variant task found (neither per-flavor nor testDebugUnitTest)'
}

function Get-Bd2UnitResultsDir {
    param([Parameter(Mandatory=$true)][string]$Task)
    # ':app:testDebugUnitTest' -> app/build/test-results/testDebugUnitTest
    $name = $Task.Split(':')[-1]
    return (Join-Path $Root (Join-Path 'app/build/test-results' $name))
}

function Read-Bd2UnitXml {
    param([Parameter(Mandatory=$true)][string]$ResultsDir)
    $agg = [ordered]@{
        xmlFiles = @(); classes = @(); tests = 0; failures = 0; errors = 0; skipped = 0
        failedTests = @(); skippedTests = @(); notRunTests = @()
    }
    if (-not (Test-Path -LiteralPath $ResultsDir -PathType Container)) { return $agg }
    $files = @(Get-ChildItem -LiteralPath $ResultsDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
    foreach ($file in $files) {
        $agg.xmlFiles += $file.FullName
        $xml = [xml](Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8)
        foreach ($suite in @($xml.testsuite)) {
            if ($null -eq $suite) { continue }
            $agg.classes += [string]$suite.name
            $agg.tests += [int]$suite.tests
            $agg.failures += [int]$suite.failures
            $agg.errors += [int]$suite.errors
            $agg.skipped += [int]$suite.skipped
            foreach ($case in @($suite.testcase)) {
                if ($null -eq $case) { continue }
                $label = "$($suite.name)#$($case.name)"
                foreach ($child in @($case.ChildNodes)) {
                    if ($child.Name -eq 'failure' -or $child.Name -eq 'error') { $agg.failedTests += $label }
                    elseif ($child.Name -eq 'skipped') { $agg.skippedTests += $label }
                }
            }
        }
    }
    return $agg
}

function Get-Bd2UnitFixtures {
    <#
      .SYNOPSIS
        取出 fixture-manifest 里声明给某个 JVM 测试类用的夹具条目。

      .DESCRIPTION
        manifest 的 usedBy 写的是**源码相对路径**（如
        app/src/test/java/com/bd2toolsbox/data/repository/GamekeeBattleV2RendererTest.kt），
        而调用方拿到的是类名。Kotlin/Java 测试类名与文件名一致，故按
        「usedBy 任一项以 <简单类名>.kt / .java 结尾」匹配。
        匹配不到 = 该类没有声明夹具，返回空数组，不是错误。
    #>
    param([Parameter(Mandatory=$true)][string]$TestClass)
    if (-not (Test-Path -LiteralPath $Bd2FixtureManifest -PathType Leaf)) {
        throw "fixture manifest is missing: $Bd2FixtureManifest"
    }
    $simple = ($TestClass -split '\.')[-1]
    $pattern = '(^|/)' + [regex]::Escape($simple) + '\.(kt|java)$'
    $manifest = Get-Content -LiteralPath $Bd2FixtureManifest -Raw -Encoding UTF8 | ConvertFrom-Json
    $entries = @()
    foreach ($fixture in @($manifest.fixtures)) {
        if (@($fixture.usedBy | Where-Object { $_ -match $pattern }).Count -eq 0) { continue }
        $entries += [ordered]@{
            id = [string]$fixture.id
            kind = 'fixture'
            storageClass = [string]$fixture.storageClass
            relative = [string]$fixture.relativePath
            expectedSha256 = [string]$fixture.sha256
            requiredness = [string]$fixture.requiredness
        }
    }
    foreach ($target in @($manifest.derivedTargets)) {
        if (@($target.usedBy | Where-Object { $_ -match $pattern }).Count -eq 0) { continue }
        $entries += [ordered]@{
            id = [string]$target.id
            kind = 'derivedTarget'
            storageClass = [string]$target.storageClass
            relative = [string]$target.targetPath
            expectedSha256 = [string]$target.sha256
            requiredness = [string]$target.requiredness
        }
    }
    return $entries
}

# ---------------------------------------------------------------------------
# JVM 单测入口
# ---------------------------------------------------------------------------

function Test-Bd2Unit {
    <#
      .SYNOPSIS
        在**实际 variant** 上跑指定 JVM 用例类，并读 JUnit XML 判定。

      .DESCRIPTION
        只看 Gradle 退出码不够：`--tests` 命中不到类时 Gradle 会失败，但报告缺失/为空、
        用例被 skip 这类「假绿色」不会体现在退出码上。所以本函数

          · 跑之前删掉该任务的 test-results 目录，避免上一轮的 XML 被当成这一轮的结果；
          · 跑之后**必须**读到 TEST-*.xml，且里面真的有这个类、至少 1 个用例；
          · 失败 / 错误 / 必需用例被 skip / 零用例 / 无报告 —— 任一命中即抛错（非零）。
    #>
    param(
        [Parameter(Mandatory=$true)][string]$TestClass,
        [switch]$AllowSkip
    )
    Assert-Bd2Environment
    $state = Get-Bd2GitState
    $variant = Get-Bd2UnitTestTasks
    $runDir = New-Bd2RunDirectory ('unit-' + $TestClass)
    $plan = [ordered]@{
        runId = Split-Path -Leaf $runDir
        git = $state
        testClass = $TestClass
        variant = $variant.variant
        tasks = $variant.tasks
        allowSkip = [bool]$AllowSkip
        counts = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
        taskResults = @()
        fixtures = @()
        reasons = @()
        result = 'FAIL'
    }
    $reasonList = New-Object System.Collections.ArrayList

    foreach ($task in $variant.tasks) {
        $resultsDir = Get-Bd2UnitResultsDir -Task $task
        if (Test-Path -LiteralPath $resultsDir) { Remove-Item -LiteralPath $resultsDir -Recurse -Force }
        Invoke-Bd2GradleRaw $task --tests $TestClass --console=plain
        $gradleExit = $script:Bd2LastExitCode
        $summary = Read-Bd2UnitXml -ResultsDir $resultsDir
        $entry = [ordered]@{
            task = $task
            gradleExit = $gradleExit
            resultsDir = $resultsDir
            xmlFiles = @()
            xmlSha256 = @()
            classes = $summary.classes
            tests = $summary.tests
            failures = $summary.failures
            errors = $summary.errors
            skipped = $summary.skipped
            failedTests = $summary.failedTests
            skippedTests = $summary.skippedTests
        }
        foreach ($file in $summary.xmlFiles) {
            $entry.xmlFiles += $file
            $entry.xmlSha256 += (Get-Bd2Sha256 -Path $file)
            Copy-Item -LiteralPath $file -Destination (Join-Path $runDir (Split-Path -Leaf $file)) -Force
        }

        if ($summary.xmlFiles.Count -eq 0) {
            [void]$reasonList.Add("${task}:未产生 JUnit XML（Gradle exit $gradleExit）")
        } else {
            if (-not ($summary.classes -contains $TestClass)) {
                [void]$reasonList.Add("${task}:报告里没有 $TestClass（classes: $($summary.classes -join ', ')）")
            }
            if ($summary.tests -le 0) {
                [void]$reasonList.Add("${task}:0 个用例")
            }
            if ($summary.failures -gt 0 -or $summary.errors -gt 0) {
                [void]$reasonList.Add("${task}:failures=$($summary.failures) errors=$($summary.errors) -> $($summary.failedTests -join '; ')")
            }
            if ($summary.skipped -gt 0 -and -not $AllowSkip) {
                [void]$reasonList.Add("${task}:skipped=$($summary.skipped) -> $($summary.skippedTests -join '; ')（skip 不抵扣必需覆盖）")
            }
            if ($gradleExit -ne 0 -and $summary.failures -eq 0 -and $summary.errors -eq 0) {
                [void]$reasonList.Add("${task}:Gradle 退出码 $gradleExit，但 XML 无失败记录——报告与退出码不一致")
            }
        }

        $plan.counts.tests += $summary.tests
        $plan.counts.failures += $summary.failures
        $plan.counts.errors += $summary.errors
        $plan.counts.skipped += $summary.skipped
        $plan.taskResults += $entry
    }

    # 夹具判定：规则说「夹具缺失或哈希不符非零」，但此前只有 Test-Bd2Python 实施
    # （经 run_python_tests.py）。JVM 侧的 classpath 夹具因此只是**声明性**的——
    # 把 repo.wiki-v2-detail-2238 换掉，GamekeeBattleV2RendererTest 的 9 条照样 PASS。
    # 这里按 manifest 的 usedBy 找出本类声明的夹具，逐个核对存在性与哈希。
    foreach ($declared in (Get-Bd2UnitFixtures -TestClass $TestClass)) {
        $path = Join-Path $Root $declared.relative
        $record = [ordered]@{
            id = $declared.id
            kind = $declared.kind
            storageClass = $declared.storageClass
            relative = $declared.relative
            requiredness = $declared.requiredness
            expectedSha256 = $declared.expectedSha256
            actualSha256 = $null
            ok = $false
            reason = $null
        }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $record.reason = 'fixture-missing'
            [void]$reasonList.Add("fixture $($declared.id): fixture-missing -> $($declared.relative)")
        } else {
            $record.actualSha256 = Get-Bd2Sha256 -Path $path
            if ($declared.requiredness -ne 'optional' -and $record.actualSha256 -ne $declared.expectedSha256) {
                $record.reason = 'fixture-hash-mismatch'
                [void]$reasonList.Add("fixture $($declared.id): fixture-hash-mismatch -> 期望 $($declared.expectedSha256)，实际 $($record.actualSha256)")
            } else {
                $record.ok = $true
            }
        }
        $plan.fixtures += $record
    }

    if ($plan.counts.tests -le 0) { [void]$reasonList.Add('所有变体合计 0 个用例') }
    if ($reasonList.Count -gt 0) {
        $plan.reasons = @($reasonList)
    } else {
        $plan.result = 'PASS'
    }
    Write-Bd2Json -Value $plan -Path (Join-Path $runDir 'summary.json')

    $plan | ConvertTo-Json -Depth 8 -Compress | Out-Null
    Write-Host ("Unit [{0}] {1}: tests={2} failures={3} errors={4} skipped={5} -> {6}" -f `
        $variant.variant, $TestClass, $plan.counts.tests, $plan.counts.failures, `
        $plan.counts.errors, $plan.counts.skipped, $plan.result)
    Write-Host ("  report: {0}" -f (Join-Path $runDir 'summary.json'))
    if ($reasonList.Count -gt 0) {
        foreach ($reason in $reasonList) { Write-Host ("  reason: {0}" -f $reason) }
        throw "Test-Bd2Unit FAILED for $TestClass (see $(Join-Path $runDir 'summary.json'))"
    }
}

# ---------------------------------------------------------------------------
# Python 入口
# ---------------------------------------------------------------------------

function Test-Bd2Python {
    <#
      .SYNOPSIS
        用已验证解释器跑 tools/run_python_tests.py，并对结构化报告做独立复核。

      .DESCRIPTION
        BD2_TEST_CATALOG / BD2_TEST_CATALOG_SHA256 只在本函数调用期间注入（取自本地
        配置 externalFixtures.catalogAlpha），结束后**恢复原进程环境值**，不留下全局副作用。
        退出码只作为输入之一：报告里的 discovered/executed/skipped、fixtures 结论和
        reasons 必须自己站得住，任何 skip / 零用例 / 坏哈希 / 未产生报告都非零。
    #>
    param(
        [Parameter(Mandatory=$true)][string]$Pattern,
        [string[]]$RequireTest,
        [string[]]$AllowSkip
    )
    Assert-Bd2Environment
    if (-not (Test-Path -LiteralPath $Bd2FixtureManifest -PathType Leaf)) {
        throw "fixture manifest is missing: $Bd2FixtureManifest"
    }
    $state = Get-Bd2GitState
    $runDir = New-Bd2RunDirectory ('python-' + $Pattern)
    $reportPath = Join-Path $runDir 'report.json'

    $savedCatalog = $env:BD2_TEST_CATALOG
    $savedCatalogSha = $env:BD2_TEST_CATALOG_SHA256
    $catalogFixture = $null
    if ($null -ne $localEnvironment -and $null -ne $localEnvironment.externalFixtures) {
        $catalogFixture = $localEnvironment.externalFixtures.catalogAlpha
    }
    try {
        if ($null -ne $catalogFixture) {
            $env:BD2_TEST_CATALOG = [string]$catalogFixture.path
            $env:BD2_TEST_CATALOG_SHA256 = [string]$catalogFixture.sha256
        }
        $arguments = @(
            (Join-Path $Root 'tools/run_python_tests.py'),
            '--start-dir', (Join-Path $Root 'tests'),
            '--pattern', $Pattern,
            '--fixture-manifest', $Bd2FixtureManifest,
            '--environment', $environmentFile,
            '--output', $reportPath
        )
        foreach ($required in @($RequireTest)) {
            if (-not [string]::IsNullOrWhiteSpace($required)) { $arguments += @('--required', $required) }
        }
        foreach ($allowed in @($AllowSkip)) {
            if (-not [string]::IsNullOrWhiteSpace($allowed)) { $arguments += @('--allow-skip', $allowed) }
        }
        Invoke-Bd2PythonRaw @arguments
        $runnerExit = $script:Bd2LastExitCode
    } finally {
        # 恢复原进程环境值（包括「原本没有」的情况）
        if ($null -eq $savedCatalog) { Remove-Item Env:BD2_TEST_CATALOG -ErrorAction SilentlyContinue }
        else { $env:BD2_TEST_CATALOG = $savedCatalog }
        if ($null -eq $savedCatalogSha) { Remove-Item Env:BD2_TEST_CATALOG_SHA256 -ErrorAction SilentlyContinue }
        else { $env:BD2_TEST_CATALOG_SHA256 = $savedCatalogSha }
    }

    $reasons = New-Object System.Collections.ArrayList
    $report = $null
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        [void]$reasons.Add("未产生报告：$reportPath（runner exit $runnerExit）")
    } else {
        try {
            $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
        } catch {
            [void]$reasons.Add("报告无法解析：$($_.Exception.Message)")
        }
    }
    if ($null -ne $report) {
        if ([string]$report.result -ne 'PASS') { [void]$reasons.Add("报告判定为 $($report.result)") }
        if ([int]$report.counts.discovered -le 0) { [void]$reasons.Add('discovered=0') }
        if ([int]$report.counts.executed -le 0) { [void]$reasons.Add('executed=0') }
        if ([int]$report.counts.skipped -gt 0) { [void]$reasons.Add("skipped=$($report.counts.skipped)（skip 不抵扣必需覆盖）") }
        if ([int]$report.counts.failed -gt 0) { [void]$reasons.Add("failed=$($report.counts.failed)") }
        if ([int]$report.counts.errors -gt 0) { [void]$reasons.Add("errors=$($report.counts.errors)") }
        foreach ($fixture in @($report.fixtures)) {
            if ($fixture -and -not [bool]$fixture.ok) { [void]$reasons.Add("fixture $($fixture.id): $($fixture.reason)") }
        }
        foreach ($reason in @($report.reasons)) {
            if ($reason) { [void]$reasons.Add("$($reason.code): $($reason.detail)") }
        }
        if ([int]$report.exitCode -ne 0 -and $reasons.Count -eq 0) {
            [void]$reasons.Add("runner 报告 exitCode=$($report.exitCode)")
        }
    }
    if ($runnerExit -ne 0 -and $reasons.Count -eq 0) { [void]$reasons.Add("runner exit $runnerExit") }

    $summary = [ordered]@{
        runId = Split-Path -Leaf $runDir
        git = $state
        pattern = $Pattern
        runnerExit = $runnerExit
        report = $reportPath
        reportSha256 = if (Test-Path -LiteralPath $reportPath -PathType Leaf) { Get-Bd2Sha256 -Path $reportPath } else { $null }
        counts = if ($null -ne $report) { $report.counts } else { $null }
        reasons = @($reasons)
        result = if ($reasons.Count -eq 0) { 'PASS' } else { 'FAIL' }
    }
    Write-Bd2Json -Value $summary -Path (Join-Path $runDir 'summary.json')

    Write-Host ("Python [{0}]: {1}" -f $Pattern, $summary.result)
    if ($null -ne $report) {
        Write-Host ("  counts: discovered={0} executed={1} passed={2} failed={3} errors={4} skipped={5} notRun={6}" -f `
            $report.counts.discovered, $report.counts.executed, $report.counts.passed, `
            $report.counts.failed, $report.counts.errors, $report.counts.skipped, $report.counts.notRun)
    }
    Write-Host ("  report: {0}" -f $reportPath)
    if ($reasons.Count -gt 0) {
        foreach ($reason in $reasons) { Write-Host ("  reason: {0}" -f $reason) }
        throw "Test-Bd2Python FAILED for $Pattern (see $(Join-Path $runDir 'summary.json'))"
    }
}

# ---------------------------------------------------------------------------
# 设备选择（显式 serial，永不使用「全部已连接设备」）
# ---------------------------------------------------------------------------

function Get-Bd2Device {
    param([Parameter(Mandatory=$true)][ValidateSet('arm64-v8a', 'x86_64')][string]$Abi)
    Assert-Bd2AndroidTools
    # 只认白名单里 abi 字段**精确等于**目标 ABI 的设备。abilist 里出现的同名项是转译
    # 提供的（MuMu 的 arm64-v8a 即属此类），不是真实设备，绝不能据此把 arm64 验收判成通过。
    $hits = @($Bd2Devices | Where-Object { $_ -and ([string]$_.abi) -eq $Abi })
    if ($hits.Count -eq 0) {
        throw "没有已授权设备声明 abi=$Abi。abilist 里的同名项经转译提供，不等于真实设备；该 ABI 验收保持未完成，不放宽为猜测或默认设备。"
    }
    if ($hits.Count -gt 1) {
        throw "abi=$Abi 命中 $($hits.Count) 台已授权设备，必须显式指定 serial，不能默认挑一台"
    }
    return $hits[0]
}

function Assert-Bd2DeviceOnline {
    param([Parameter(Mandatory=$true)]$Device)
    $serial = [string]$Device.serial
    if ([string]::IsNullOrWhiteSpace($serial)) { throw 'Authorized device entry has no serial' }
    $state = ((& $Bd2Adb -s $serial get-state) -join '').Trim()
    if ($LASTEXITCODE -ne 0 -or $state -ne 'device') {
        throw "设备 $serial 不在线（get-state=$state, exit $LASTEXITCODE）"
    }
    $reportedAbi = ((& $Bd2Adb -s $serial shell getprop ro.product.cpu.abi) -join '').Trim()
    if ($reportedAbi -ne [string]$Device.abi) {
        throw "ABI 不匹配：白名单声明 $($Device.abi)，设备实际报告 $reportedAbi —— 拒绝安装"
    }
    return @{ serial = $serial; abi = $reportedAbi }
}

function Get-Bd2SystemSettings {
    param([Parameter(Mandatory=$true)][string]$Serial)
    # 不假定字体/主题原值：先读下来，跑完再核对并恢复
    return @{
        fontScale = ((& $Bd2Adb -s $Serial shell settings get system font_scale) -join '').Trim()
        nightMode = ((& $Bd2Adb -s $Serial shell settings get secure ui_night_mode) -join '').Trim()
    }
}

function Restore-Bd2SystemSettings {
    param([Parameter(Mandatory=$true)][string]$Serial, [Parameter(Mandatory=$true)]$Before)
    $after = Get-Bd2SystemSettings -Serial $Serial
    foreach ($key in @('fontScale', 'nightMode')) {
        if ($after[$key] -ne $Before[$key]) {
            if ($key -eq 'fontScale') {
                & $Bd2Adb -s $Serial shell settings put system font_scale $Before[$key] | Out-Null
            } else {
                & $Bd2Adb -s $Serial shell settings put secure ui_night_mode $Before[$key] | Out-Null
            }
            Write-Host ("  restored {0}: {1} -> {2}" -f $key, $after[$key], $Before[$key])
        }
    }
}

# ---------------------------------------------------------------------------
# instrumentation 入口
# ---------------------------------------------------------------------------

function Get-Bd2InstrumentationApk {
    $pattern = Join-Path $Root 'app/build/outputs/apk/androidTest/debug/*.apk'
    $files = @(Get-ChildItem -Path $pattern -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw "androidTest APK not found under $pattern" }
    if ($files.Count -gt 1) { throw "multiple androidTest APKs found: $($files.FullName -join ', ')" }
    return $files[0].FullName
}

function Get-Bd2ReleaseProbeApk {
    # :release-probe 是一个**自足的普通应用**，不是 :app 的 androidTest 包 —— 只有这样
    # 它才自带完整运行时，能被用来驱动预先构建好的 minified release 包。理由与实测见
    # docs/refactor/evidence/T04.md。
    $pattern = Join-Path $Root 'release-probe/build/outputs/apk/debug/*.apk'
    $files = @(Get-ChildItem -Path $pattern -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { throw "release probe APK not found under $pattern（先构建 :release-probe:assembleDebug）" }
    if ($files.Count -gt 1) { throw "multiple release probe APKs found: $($files.FullName -join ', ')" }
    return $files[0].FullName
}

function Get-Bd2ApkForAbi {
    param([Parameter(Mandatory=$true)][string]$BuildType, [Parameter(Mandatory=$true)][string]$Abi)
    $dir = Join-Path $Root ("app/build/outputs/apk/" + $BuildType)
    $candidate = Join-Path $dir ("app-" + $Abi + "-" + $BuildType + ".apk")
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "ABI split APK missing for $Abi/${BuildType}: $candidate"
    }
    return $candidate
}

function Invoke-Bd2Instrumentation {
    param(
        [Parameter(Mandatory=$true)][string]$Serial,
        [Parameter(Mandatory=$true)][string]$TestClass,
        [string]$Method,
        [string]$TestPackage = 'com.bd2toolsbox.test'
    )
    $target = if ([string]::IsNullOrWhiteSpace($Method)) { $TestClass } else { "$TestClass#$Method" }
    # -r 保留原始协议：逐条用例的 INSTRUMENTATION_STATUS_CODE 是唯一能区分
    # 「通过」与「被跳过」的信号（见 Assert-Bd2InstrumentationResult）。
    $output = (& $Bd2Adb -s $Serial shell am instrument -w -r -e class $target "$TestPackage/$Bd2InstrumentationRunner") -join [Environment]::NewLine
    $adapterExit = $LASTEXITCODE
    Write-Host $output
    return @{ output = $output; adbExit = $adapterExit }
}

function Assert-Bd2InstrumentationResult {
    param([Parameter(Mandatory=$true)][hashtable]$Result, [Parameter(Mandatory=$true)][string]$Label)
    # adb 的退出码对 am instrument 不可靠：以输出里的 runner 终判为准。
    # 但**只**看尾部 "OK (n tests)" 不够：n 把 @Ignore（code -3）和 Assume 不成立
    # （code -4）也算在内，被跳过的用例会被判成 PASS。所以以 -r 原始协议里的
    # 逐条 INSTRUMENTATION_STATUS_CODE 为准（1=开始 0=通过 -1=异常 -2=失败）。
    $tailFail = [regex]::Match($Result.output, 'Tests run: (\d+),\s+Failures: (\d+)')
    if ($tailFail.Success) {
        throw "${Label}:instrumentation 失败（Tests run: $($tailFail.Groups[1].Value), Failures: $($tailFail.Groups[2].Value), adb exit $($Result.adbExit)）"
    }

    $codes = @{}
    $skippedNames = New-Object System.Collections.Generic.List[string]
    $currentTest = $null
    foreach ($line in ($Result.output -split "`r?`n")) {
        if ($line -match 'INSTRUMENTATION_STATUS: test=(.+?)\s*$') { $currentTest = $Matches[1]; continue }
        if ($line -match 'INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$') {
            $code = [int]$Matches[1]
            $codes[$code] = [int]$codes[$code] + 1
            if (($code -eq -3 -or $code -eq -4) -and $currentTest) { $skippedNames.Add($currentTest) }
        }
    }
    $passed = [int]$codes[0]
    $started = [int]$codes[1]

    if ($started -le 0) {
        # 没拿到原始协议（例如调用方未加 -r）：退回尾部判定，但绝不能因此放过跳过
        $ok = [regex]::Match($Result.output, 'OK \((\d+) tests?\)')
        if ($ok.Success -and [int]$ok.Groups[1].Value -gt 0) {
            throw "${Label}:输出中没有原始协议，无法判定是否有用例被跳过——用 -r 重跑（$($ok.Groups[1].Value) 条）"
        }
        throw "${Label}:无法从输出判定结果（adb exit $($Result.adbExit)）——未运行不算通过"
    }
    if ($skippedNames.Count -gt 0) {
        throw "${Label}:$($skippedNames.Count) 条用例被跳过（$($skippedNames -join '、')）——skip 不是通过（passed=$passed/$started）"
    }
    if ($passed -le 0) { throw "${Label}:runner 报 OK 但通过用例数为 0" }
    if ($passed -ne $started) {
        throw "${Label}:起止数不匹配（started=$started, passed=$passed, adb exit $($Result.adbExit)）"
    }
    return $passed
}

# ---------------------------------------------------------------------------
# 设备测试入口
# ---------------------------------------------------------------------------

function Test-Bd2Device {
    <#
      .SYNOPSIS
        在白名单内显式选定的设备上跑指定 instrumentation 类。

      .DESCRIPTION
        设备必须由白名单里的 serial + ABI 唯一确定；ABI 不匹配、设备不在线、
        项目根越界都**拒绝安装**。构建测试包、安装、取结果全程显式 -s <serial>，
        不使用「所有已连接设备」，也不依赖 ANDROID_SERIAL 是否生效。
        跑完恢复字体/主题，并卸掉测试包（不动 app 数据）。
    #>
    param(
        [Parameter(Mandatory=$true)][string]$TestClass,
        [Parameter(Mandatory=$true)][ValidateSet('arm64-v8a', 'x86_64')][string]$Abi,
        [string]$ProjectRoot
    )
    Assert-Bd2AndroidTools
    if (-not [string]::IsNullOrWhiteSpace($ProjectRoot)) {
        $requested = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)
        if (-not [string]::Equals($requested, $Root, [StringComparison]::OrdinalIgnoreCase)) {
            throw "ProjectRoot 越界：只允许实施根内的项目（$Root）；T13 的独立探针项目尚未建立"
        }
    }
    $device = Get-Bd2Device -Abi $Abi
    $identity = Assert-Bd2DeviceOnline -Device $device
    $serial = $identity.serial
    Write-Host ("Device: {0} ({1}, {2})" -f $serial, [string]$device.product, $identity.abi)

    $before = Get-Bd2SystemSettings -Serial $serial
    $runDir = New-Bd2RunDirectory ('device-' + $Abi + '-' + $TestClass)
    # summary 在**动手之前**就建好：构建、安装、取结果任何一步抛错都要留下带 reason 的现场。
    # 此前只在断言失败的路径上落盘，构建/安装阶段挂掉就只剩一个 0 文件目录（见 T04.md §5.3）。
    $summary = [ordered]@{
        runId = Split-Path -Leaf $runDir
        git = Get-Bd2GitState
        serial = $serial
        abi = $identity.abi
        testClass = $TestClass
        appApk = $null
        appApkSha256 = $null
        testApk = $null
        testApkSha256 = $null
        tests = 0
        result = 'FAIL'
    }
    try {
        Invoke-Bd2Gradle :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        $appApk = Get-Bd2ApkForAbi -BuildType 'debug' -Abi $Abi
        $testApk = Get-Bd2InstrumentationApk
        $summary.appApk = $appApk
        $summary.appApkSha256 = Get-Bd2Sha256 -Path $appApk
        $summary.testApk = $testApk
        $summary.testApkSha256 = Get-Bd2Sha256 -Path $testApk

        foreach ($apk in @($appApk, $testApk)) {
            $install = (& $Bd2Adb -s $serial install -r -t $apk) -join ' '
            if ($LASTEXITCODE -ne 0 -or $install -notmatch 'Success') {
                throw "安装失败：$apk -> $install (exit $LASTEXITCODE)"
            }
        }
        $result = Invoke-Bd2Instrumentation -Serial $serial -TestClass $TestClass
        # 先落盘再断言：断言失败时归档里必须留下现场，不能只剩一个空目录（见 T04.md §5.3）
        [IO.File]::WriteAllText((Join-Path $runDir 'instrumentation.txt'), $result.output, (New-Object Text.UTF8Encoding($false)))
        $count = Assert-Bd2InstrumentationResult -Result $result -Label $TestClass
        $summary.tests = $count
        $summary.result = 'PASS'
        Write-Host ("Device {0} [{1}]: {2} tests -> PASS" -f $serial, $TestClass, $count)
    } catch {
        $summary.reason = $_.Exception.Message
        throw
    } finally {
        # 恢复与卸载放进内层 finally，保证「恢复本身出错」也不会吞掉 summary
        try {
            Restore-Bd2SystemSettings -Serial $serial -Before $before
            # 只卸测试包；app 数据保留（不 pm clear，不卸载日用应用）
            & $Bd2Adb -s $serial uninstall com.bd2toolsbox.test | Out-Null
        } finally {
            Write-Bd2Json -Value $summary -Path (Join-Path $runDir 'summary.json')
        }
    }
}

# ---------------------------------------------------------------------------
# release smoke 入口
# ---------------------------------------------------------------------------

function Get-Bd2ReleaseArtifact {
    param([Parameter(Mandatory=$true)][ValidateSet('arm64-v8a', 'x86_64')][string]$Abi)
    # baseline-startup 面向 T03 冻结的「同源原工具链重建包」：绝对路径 + 清单哈希，
    # 不用刚构建出来的新包冒充基线。
    $relative = "local/refactor-artifacts/rebuilt-original/app-$Abi-release.apk"
    $path = Join-Path $Root $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "release baseline APK missing: $relative" }
    $manifest = Get-Content -LiteralPath $Bd2FixtureManifest -Raw -Encoding UTF8 | ConvertFrom-Json
    $fixture = @($manifest.fixtures | Where-Object { $_.id -eq "artifact.rebuilt-original.$(& { if ($Abi -eq 'arm64-v8a') { 'arm64' } else { 'x86_64' } })" })
    if ($fixture.Count -ne 1) { throw "fixture manifest has no unique entry for the $Abi release baseline" }
    return @{ path = $path; relative = $relative; expectedSha256 = [string]$fixture[0].sha256 }
}

function Test-Bd2ReleaseSmoke {
    <#
      .SYNOPSIS
        对真实 minified release 包做签名/身份/安装/启动 smoke。

      .DESCRIPTION
        baseline-startup 只做「原版/同源重建包能装、能起、身份对得上、测试 runner 能跑」，
        不改业务 DTO、不依赖后续修复、不写真实游戏文件。签名与哈希用 apksigner + 清单核验，
        不通过测试专用 keep、替换生产 DTO 或反射引用让 R8 缺陷消失。
        其余 case（snapshot-compatibility / json-migration / …）本项**未实现**，必须非零失败。
    #>
    param(
        [Parameter(Mandatory=$true)][string]$Case,
        [Parameter(Mandatory=$true)][ValidateSet('arm64-v8a', 'x86_64')][string]$Abi
    )
    Assert-Bd2AndroidTools
    if ($Case -ne 'baseline-startup') {
        throw "release case '$Case' 尚未实现（T04 只落实 baseline-startup；其余归 T44/T57）。拒绝返回空成功。"
    }

    $artifact = Get-Bd2ReleaseArtifact -Abi $Abi
    $actualSha = Get-Bd2Sha256 -Path $artifact.path
    if ($actualSha -ne $artifact.expectedSha256) {
        throw "release 基线哈希不符：$($artifact.relative) 期望 $($artifact.expectedSha256)，实际 $actualSha"
    }
    $signer = (& $Bd2Apksigner verify --verbose --print-certs $artifact.path) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw "apksigner verify 失败：$($artifact.relative)" }
    $certMatch = [regex]::Match($signer, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $certMatch.Success) { throw "无法从 apksigner 输出读出证书指纹：$($artifact.relative)" }
    $cert = $certMatch.Groups[1].Value.ToLowerInvariant()
    $expectedCert = ''
    if ($null -ne $localEnvironment -and $null -ne $localEnvironment.releaseSigning) {
        $expectedCert = ([string]$localEnvironment.releaseSigning.certSha256).ToLowerInvariant()
    }
    if ([string]::IsNullOrWhiteSpace($expectedCert)) { throw '本地配置缺少 releaseSigning.certSha256' }
    if ($cert -ne $expectedCert) {
        throw "release 签名证书不符：期望 $expectedCert，实际 $cert"
    }

    $device = Get-Bd2Device -Abi $Abi
    $identity = Assert-Bd2DeviceOnline -Device $device
    $serial = $identity.serial

    $before = Get-Bd2SystemSettings -Serial $serial
    $runDir = New-Bd2RunDirectory ('smoke-' + $Case + '-' + $Abi)
    $summary = [ordered]@{
        runId = Split-Path -Leaf $runDir
        git = Get-Bd2GitState
        case = $Case
        abi = $Abi
        serial = $serial
        apk = $artifact.relative
        apkSha256 = $actualSha
        apkSha256MatchesManifest = $true
        signerCertSha256 = $cert
        apksignerVerified = $true
        instrumentationHost = 'com.bd2toolsbox.releaseprobe'
        steps = @()
        result = 'FAIL'
    }
    try {
        # 用自足的 :release-probe APK，不用 :app 的 androidTest 包。两条实测过的死路：
        #   1) debug androidTest 包驱动 release app → ClassNotFoundException:
        #      kotlin.jvm.internal.Intrinsics（release 里它已被 R8 改名为 P1.h）；
        #   2) androidTest 改走 release 变体 → NoSuchMethodError: P1.h.e(Object,String)V
        #      （测试包按**当前源码**的映射混淆，而冻结基线里该方法已被 R8 消掉）。
        # 探针不依赖 :app 的类，故两条都不受影响。详见 T04 证据。
        Invoke-Bd2Gradle :release-probe:assembleDebug --console=plain
        $testApk = Get-Bd2ReleaseProbeApk
        $installApp = (& $Bd2Adb -s $serial install -r -t $artifact.path) -join ' '
        if ($LASTEXITCODE -ne 0 -or $installApp -notmatch 'Success') {
            throw "release 安装失败：$installApp (exit $LASTEXITCODE)"
        }
        $summary.steps += 'install-release-apk'
        $installTest = (& $Bd2Adb -s $serial install -r -t $testApk) -join ' '
        if ($LASTEXITCODE -ne 0 -or $installTest -notmatch 'Success') {
            throw "探针包安装失败：$installTest (exit $LASTEXITCODE)"
        }
        $summary.steps += 'install-release-probe-apk'

        $probe = Invoke-Bd2Instrumentation -Serial $serial `
            -TestClass 'com.bd2toolsbox.integration.ReleaseContractProbeTest' `
            -Method 'baselineStartup' `
            -TestPackage 'com.bd2toolsbox.releaseprobe'
        # 先落盘再断言（同 Test-Bd2Device：失败也要留下现场）
        [IO.File]::WriteAllText((Join-Path $runDir 'probe.txt'), $probe.output, (New-Object Text.UTF8Encoding($false)))
        $count = Assert-Bd2InstrumentationResult -Result $probe -Label 'ReleaseContractProbeTest#baselineStartup'
        $summary.probeTests = $count
        $summary.steps += 'release-contract-probe'

        # 主界面启动：显式指名组件，要求 Status: ok 且组件确实是生产 MainActivity
        $launch = (& $Bd2Adb -s $serial shell am start -W -n 'com.bd2toolsbox/.MainActivity') -join [Environment]::NewLine
        [IO.File]::WriteAllText((Join-Path $runDir 'launch.txt'), $launch, (New-Object Text.UTF8Encoding($false)))
        if ($launch -notmatch 'Status:\s*ok') { throw "主界面启动失败：$launch" }
        $summary.steps += 'launch-main-activity'
        & $Bd2Adb -s $serial shell am force-stop com.bd2toolsbox | Out-Null

        # 应用身份：包名/版本号/版本名必须与冻结基线一致（0.2.16 / 17）
        $badging = ((& $Bd2Adb -s $serial shell dumpsys package com.bd2toolsbox) -join [Environment]::NewLine)
        [IO.File]::WriteAllText((Join-Path $runDir 'package.txt'), $badging, (New-Object Text.UTF8Encoding($false)))
        if ($badging -notmatch 'versionCode=17') { throw '应用身份不符：设备上的 versionCode 不是 17' }
        if ($badging -notmatch 'versionName=0\.2\.16') { throw '应用身份不符：设备上的 versionName 不是 0.2.16' }
        $summary.steps += 'verify-app-identity'

        $summary.result = 'PASS'
    } catch {
        # 失败也要留档：summary 里 result 默认就是 FAIL，落盘统一交给下面的 finally
        $summary.reason = $_.Exception.Message
        throw
    } finally {
        # 恢复与卸载放进内层 finally，保证「恢复本身出错」也不会吞掉 summary
        try {
            Restore-Bd2SystemSettings -Serial $serial -Before $before
            # 只卸探针包；被测的 release 包保留（不 pm clear、不卸载日用应用、不动 app 数据）
            & $Bd2Adb -s $serial uninstall com.bd2toolsbox.releaseprobe | Out-Null
        } finally {
            Write-Bd2Json -Value $summary -Path (Join-Path $runDir 'summary.json')
        }
    }
    Write-Host ("ReleaseSmoke [{0}/{1}] {2} -> PASS" -f $Case, $Abi, $serial)
    Write-Host ("  report: {0}" -f (Join-Path $runDir 'summary.json'))
}

# ---------------------------------------------------------------------------
# LocalAll
# ---------------------------------------------------------------------------

function Get-Bd2UnitTestClasses {
    $dir = Join-Path $Root 'app/src/test/java'
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) { throw "unit test source dir missing: $dir" }
    $classes = @()
    foreach ($file in Get-ChildItem -LiteralPath $dir -Recurse -Filter '*Test.kt' -File) {
        $relative = $file.FullName.Substring($dir.Length).TrimStart('\', '/')
        $classes += ($relative -replace '\.kt$', '') -replace '[\\/]', '.'
    }
    return $classes
}

function Get-Bd2PythonTestPatterns {
    $dir = Join-Path $Root 'tests'
    return @(Get-ChildItem -LiteralPath $dir -Filter 'test_*.py' -File | ForEach-Object { $_.Name } | Sort-Object)
}

function Invoke-Bd2LocalAll {
    <#
      .SYNOPSIS
        本地验证总入口（Mode=LocalAll）。

      .DESCRIPTION
        §6.1 把**完整** LocalAll 调度交给 T60；T04 负责测试入口本身。本函数因此：

          1. 真的跑本轮已实现的本地验证（全部 JVM 单测 + 全部 Python 测试，逐个走
             Test-Bd2Unit / Test-Bd2Python 的报告与 skip 判定），不返回空成功；
          2. 跑完之后**明确拒绝**把这次运行当作完整 LocalAll —— release 重建、
             架构检查、许可与包体静态审计所需的工具（T48 的 audit_apk_size.py、
             T55/T60 的 check_architecture.py、T37/T38 的许可清单）在 T04 时尚不存在，
             缺失步骤必须非零，不能静默少跑几步就 exit 0。

        不隐式安装设备、不自动发布、不访问真实游戏目录 —— 设备动作只在显式调用
        Test-Bd2Device / Test-Bd2ReleaseSmoke 时发生。
    #>
    Assert-Bd2Environment
    Write-Host 'LocalAll: 本轮已实现的本地验证开始（JVM 单测 + Python 测试）'
    $unitClasses = Get-Bd2UnitTestClasses
    if ($unitClasses.Count -eq 0) { throw 'LocalAll: 未发现任何 JVM 单测类' }
    foreach ($class in $unitClasses) { Test-Bd2Unit -TestClass $class }
    foreach ($pattern in Get-Bd2PythonTestPatterns) { Test-Bd2Python -Pattern $pattern }

    $missing = New-Object System.Collections.ArrayList
    foreach ($tool in @('tools/check_architecture.py', 'tools/audit_apk_size.py', 'tools/stage_android_python.py')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $tool) -PathType Leaf)) {
            [void]$missing.Add($tool)
        }
    }
    foreach ($task in @(':app:assembleArm64Release', ':app:assembleX64Release')) {
        $tasks = (Get-Bd2GradleTaskList) -join [Environment]::NewLine
        if ($tasks -notmatch ('(?m)^\s*' + [regex]::Escape($task.Split(':')[-1]) + '(?:\s|$)')) {
            [void]$missing.Add("Gradle task $task")
        }
    }
    if ($missing.Count -gt 0) {
        throw ("LocalAll 未完成：完整调度需要以下步骤，本项（T04）尚未实现，T60 负责补齐 —— " `
               + ($missing -join '; ') + '。本轮已跑的测试结果见 local/refactor-artifacts/test-results/。')
    }
    Write-Host 'LocalAll: PASS'
}

if ($Mode -eq 'LocalAll') {
    Invoke-Bd2LocalAll
}
