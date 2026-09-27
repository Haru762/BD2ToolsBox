# Texture2DDecoder：固定来源与受控重建

本文件落实 T12 的现有 Python/JNI 输入治理，不升级依赖、不接入新的 Gradle/CMake 构建链。两个 ABI 继续使用 `app/src/main/jniLibs/` 内经过验证的库；重建产物先进入 `local/`，不得未经验证就覆盖它们。

## 来源与契约

机器可读清单：[texture2ddecoder-source.json](../../tools/native/texture2ddecoder-source.json)。

- 上游：[K0lb3/Texture2DDecoder](https://github.com/K0lb3/Texture2DDecoder)，固定提交 `3ebc3b758bd6b1a3108b50148f7998ee34f058c6`；不是浮动分支或 latest。
- 适配补丁：[texture2ddecoder-c-linkage.patch](../../tools/native/texture2ddecoder-c-linkage.patch)。仅为 7 个头文件增加 C linkage，使 ctypes 能按原名查找符号，不修改解码算法。
- NDK：r26d / `26.3.11579264`，Windows x86_64 构建宿主，native target API 24。App 的 minSdk29 不因此改变。
- `decode_*` 成功码为 **1**，Python 包装层仅接受 `ret == 1`；Crunch 返回布尔值。
- 输出内存为 BGRA，现有 Python 包装层转换为 RGBA。ASTC 输出指针位于参数末尾，与通用入口不同。
- 库能加载、符号存在、真实解码正确是三项不同检查。不能只用 `_lib is not None` 验收。

两个库的现有字节数、SHA-256、完整 20 个必需导出、编译单元及旗标均在清单中。

**T15（2026-09-27）起打包输入是 16 KB 页对齐版本**：`commonFlags` 增加 `-Wl,-z,max-page-size=16384`，每个 PT_LOAD 的 `p_align = 0x4000`。用本文命令在同一源码上连续两次构建逐字节相同（x86_64 `50e695b8…31c9` / 563768 B，arm64 `cd75798a…6e91` / 603056 B）；导出集合与旧库相同；去掉地址后的反汇编，x86_64 逐条相同，arm64 只有 2 条 `ldr` 的页内偏移写法随段布局变化（`[x8]` ↔ `[x8, #imm]`）。旧 4 KB 库（x86_64 `4c7c8064…07c5`、arm64 `1e6fc051…68d2`）不再打包。验收见 [T15.md](evidence/T15.md) §5。arm64 库原本已跟踪；新增 x86_64 库使用精确的 `.gitignore` 例外，不为全部 `.so` 放开忽略规则。

## 在新目录准备源码

从仓库根运行 PowerShell。先在当前进程设置 `ANDROID_NDK_HOME`，指向已安装的指定 NDK；不把个人路径写入仓库。

```powershell
$ErrorActionPreference = 'Stop'
$Root = (git rev-parse --show-toplevel).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Run from the repository' }
$Manifest = Get-Content (Join-Path $Root 'tools/native/texture2ddecoder-source.json') -Raw | ConvertFrom-Json
$Patch = Join-Path $Root $Manifest.patch.path
$Source = Join-Path $Root 'local/native-rebuild/texture2ddecoder'
$Output = Join-Path $Root 'local/native-rebuild/output'
$Ndk = $env:ANDROID_NDK_HOME
if ([string]::IsNullOrWhiteSpace($Ndk)) { throw 'Set ANDROID_NDK_HOME first' }
$Properties = Get-Content (Join-Path $Ndk 'source.properties') -Raw
if ($Properties -notmatch '(?m)^Pkg.Revision\s*=\s*26\.3\.11579264\s*$') { throw 'Wrong NDK version' }
if ((Test-Path $Source) -or (Test-Path $Output)) { throw 'Use new source/output directories; do not delete existing evidence' }
if ((Get-FileHash $Patch -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Manifest.patch.sha256) { throw 'Patch hash mismatch' }

git clone --no-checkout $Manifest.upstream.repository $Source
if ($LASTEXITCODE -ne 0) { throw 'Clone failed' }
git -C $Source checkout --detach $Manifest.upstream.commit
if ($LASTEXITCODE -ne 0) { throw 'Pinned commit unavailable' }
if ((git -C $Source rev-parse HEAD).Trim() -ne $Manifest.upstream.commit) { throw 'Wrong source commit' }
if ((Get-FileHash (Join-Path $Source 'LICENSE') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Manifest.upstream.licenseSha256) { throw 'Upstream license hash mismatch' }
git -C $Source apply --check $Patch
if ($LASTEXITCODE -ne 0) { throw 'Patch does not apply to pinned source' }
git -C $Source apply $Patch
if ($LASTEXITCODE -ne 0) { throw 'Patch application failed' }
New-Item -ItemType Directory $Output | Out-Null
```

没有网络时，可从**已核验包含同一提交**的本地 Git clone 复制 Git 对象，再执行相同的 commit、LICENSE 和 patch 核验；不能仅复制某个未知版本的已修改源码目录。

## 构建：只产生独立输出

继续在上面的 PowerShell 会话运行：

```powershell
$Bin = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$Units = @($Manifest.translationUnits)
$Flags = @($Manifest.toolchain.commonFlags) + @('-I.', '-I./fp16')
Push-Location (Join-Path $Source $Manifest.sourceDirectory)
try {
    & (Join-Path $Bin 'x86_64-linux-android24-clang++.cmd') @Flags @($Manifest.toolchain.x86_64ExtraFlags) @Units -o (Join-Path $Output 'libtexture2ddecoder-x86_64.so')
    if ($LASTEXITCODE -ne 0) { throw 'x86_64 build failed' }
    & (Join-Path $Bin 'aarch64-linux-android24-clang++.cmd') @Flags @Units -o (Join-Path $Output 'libtexture2ddecoder-arm64-v8a.so')
    if ($LASTEXITCODE -ne 0) { throw 'arm64 build failed' }
} finally {
    Pop-Location
}
foreach ($Abi in @('x86_64', 'arm64-v8a')) {
    $Library = Join-Path $Output "libtexture2ddecoder-$Abi.so"
    Get-FileHash $Library -Algorithm SHA256
    & (Join-Path $Bin 'llvm-readelf.exe') -h -d $Library
    if ($LASTEXITCODE -ne 0) { throw 'ELF inspection failed' }
    $Symbols = & (Join-Path $Bin 'llvm-nm.exe') -D --defined-only $Library
    if ($LASTEXITCODE -ne 0) { throw 'Export inspection failed' }
    $Names = @($Symbols | ForEach-Object { ($_ -split '\s+')[-1] })
    $Missing = @($Manifest.requiredExports | Where-Object { $_ -notin $Names })
    if ($Missing.Count -gt 0) { throw "Missing C exports: $($Missing -join ', ')" }
}
```

核对 ELF Machine 分别为 x86-64/AArch64，动态依赖为 `libc.so`、`libm.so`、`libdl.so`，不应意外引入未打包的共享 C++ runtime。上述命令不安装 APK、不修改 `jniLibs`、不删除输出；失败时保留现场，后续尝试用新目录。

### 哈希为什么可能不同

历史 arm64 命令把源文件的绝对路径传给编译器，Crunch 的断言文件名因此带入库内。仅正反斜杠不同就能改变二进制字节。上面的可迁移命令使用相对源文件名，不再写入个人绝对路径，因此**不要求新 arm64 输出与历史库 SHA-256 相同**。

现有库的来源已经在本机用历史命令逐字节重建确认，见 [T12 补充证据](evidence/T12-native-contract-20260921.md)。清单内的哈希是**现有打包输入的身份**，不是任意目录、任意命令输出都应相同的承诺。新输出必须作为新产物验收、记录哈希；不能改清单数值以掩盖未知差异。

## 替换打包输入之前的门槛

1. 核对固定提交、适配 patch、编译器版本及实际命令；保留旧库，不能先删除。
2. 两 ABI 检查 ELF、动态依赖及全部必需符号。
3. 重跑 Python 包装层测试，包含成功码、失败码、缺库、通道交换与 Crunch 分支。
4. 在真实 minified 目标 APK 上运行 `ReleasePythonDependencyProbeTest`：先确认目标包/non-debuggable/实际 ABI，再验证 Python import、全部符号和完整 BC1 块的精确输出。
5. 白名单 MuMu 只提供 x86_64 结果；其 arm64 转译不等于 arm64 真机。
6. 确认 APK 包含本次接受的绑定与 native 字节后，才能记录新产物。不自动提交、推送或发布。

这份来源记录不闭合其他 JNI、FMOD 或 `uncompressed.tpk` 的原始来源，也不代表 T12 整体完成。
