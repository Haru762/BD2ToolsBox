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
Set-Location -LiteralPath $Root
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
function Invoke-Bd2Gradle {
    Assert-Bd2Workspace
    if (-not (Test-Path -LiteralPath $Gradlew -PathType Leaf)) { throw 'Gradle wrapper is missing' }
    & $Gradlew @args
    if ($LASTEXITCODE -ne 0) { throw 'Gradle command failed' }
}
function Invoke-Bd2Python {
    Assert-Bd2Workspace
    if ([string]::IsNullOrWhiteSpace($Python) -or -not (Test-Path -LiteralPath $Python -PathType Leaf)) {
        throw 'T02 must record an executable testPythonExe'
    }
    & $Python @args
    if ($LASTEXITCODE -ne 0) { throw 'Python command failed' }
}
function Test-Bd2Unit {
    param([Parameter(Mandatory=$true)][string]$TestClass)
    $tasks = (Invoke-Bd2Gradle :app:tasks --all --console=plain) -join [Environment]::NewLine
    if ($tasks -match '(?m)^\s*testArm64DebugUnitTest(?:\s|$)' -and
        $tasks -match '(?m)^\s*testX64DebugUnitTest(?:\s|$)') {
        $unitTasks = @(':app:testArm64DebugUnitTest', ':app:testX64DebugUnitTest')
    } elseif ($tasks -match '(?m)^\s*testDebugUnitTest(?:\s|$)') {
        $unitTasks = @(':app:testDebugUnitTest')
    } else { throw 'No supported unit-test variant found' }
    foreach ($task in $unitTasks) {
        Invoke-Bd2Gradle $task --tests $TestClass --console=plain
    }
    Write-Warning 'G0 bootstrap only: record raw test counts; T04 must add required-test and skip validation'
}
function Show-Bd2Dependencies {
    param([string]$Dependency)
    $tasks = (Invoke-Bd2Gradle :app:tasks --all --console=plain) -join [Environment]::NewLine
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
if ($Mode -ne 'Load') {
    throw 'The T02 bootstrap is not full verification; T60 must implement LocalAll orchestration'
}
