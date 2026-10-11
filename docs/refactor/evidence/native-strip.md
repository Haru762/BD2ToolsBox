# Native 调试信息剥离（T51）

- 日期：2026-10-11。
- 结论：**剥离此前根本没有发生**（不是「NDK 缺失的历史线索」，是当轮构建日志可证的事实）；固定 NDK 后 AGP 走受支持的剥离流程，arm64 少 1.30 MiB、x86_64 少 1.22 MiB，动态依赖与导出**逐项不变**。

## 1. 原因确认（以构建日志为准）

`Invoke-Bd2Gradle :app:stripArm64ReleaseDebugSymbols --rerun-tasks --info`：

```
C/C++: android.ndkVersion from module build.gradle is [27.0.12077973]
C/C++: Folder D:\Android\Sdk\ndk\27.0.12077973 does not exist. Ignoring.
C/C++: Folder D:\Android\Sdk\ndk-bundle does not exist. Ignoring.
Unable to strip library '…libandroidx.graphics.path.so' due to missing strip tool for ABI 'armeabi-v7a'. Packaging it as is.
```

无 `--info` 时的汇总行：

```
Unable to strip the following libraries, packaging them as they are:
libandroidx.graphics.path.so, libastcenc.so, libchaquopy_java.so, libcrypto_chaquopy.so,
libcrypto_python.so, libpython3.8.so, libsqlite3_chaquopy.so, libsqlite3_python.so,
libssl_chaquopy.so, libssl_python.so, libtexture2ddecoder.so.
```

即：AGP 8.11.1 的默认 `android.ndkVersion` 是 **27.0.12077973**，本机构建机上**只装了
26.3.11579264**，于是 strip 工具找不到，**11 个库全部原样进包**。

### 可剥离节（剥离前，读自 APK 内的 ELF 节表）

| 库 | 文件字节 | `.debug_*` + `.symtab`/`.strtab` |
|---|---:|---:|
| `libastcenc.so` | 1,713,680 | **1,191,292**（`.debug_loc/.debug_abbrev/.debug_info/.debug_ranges/.debug_str/.debug_line/.symtab/.strtab`） |
| `libtexture2ddecoder.so` | 603,056 | **164,060**（`.symtab`/`.strtab`） |
| 其余 9 个 | — | 0 |
| **合计** | | **1,355,352 B（arm64）** |

## 2. 修复

- `gradle/libs.versions.toml`：新增 `ndkVersion = "26.3.11579264"`（附原因注释）。
- `app/build.gradle.kts`：`ndkVersion = libs.versions.ndkVersion.get()`。

剥离流程仍是 **AGP 官方路径**：`:app:strip<Flavor>ReleaseDebugSymbols` 用 NDK 的
`llvm-strip`，输入 `build/intermediates/merged_native_libs/…`，输出
`build/intermediates/stripped_native_libs/…` 再进 APK。
**没有**手工 `strip`、**没有**删库、**没有**删 JNI 符号。

重建后同一命令的日志里 `Unable to strip` 计数为 **0**。

## 3. 剥离前后对照（实测）

| 项 | arm64-v8a | x86_64 |
|---|---:|---:|
| `libastcenc.so` | 1,713,680 → **521,776**（−1,191,904） | 1,673,240 → **536,920**（−1,136,320） |
| `libtexture2ddecoder.so` | 603,056 → **438,840**（−164,216） | 563,768 → **440,104**（−123,664） |
| 其余 9 个库 | 不变 | 不变 |
| `nativeDebugBytes`（工具汇总） | 1,355,352 → **0** | 1,259,218 → **0** |
| APK 文件 | 24,414,726 → **23,054,878**（−1,359,848 B = −1.297 MiB） | 24,504,891 → **23,226,963**（−1,277,928 B = −1.219 MiB） |

### 动态依赖 / 必要导出 / 加载段：逐项一致

用既有的 `tools/audit_native_abi.py`（它本来就读 `.dynsym` 导出、`DT_NEEDED`、
`PT_LOAD` 对齐与 ZIP 对齐）对剥离前后的同一个包各跑一次，去掉 `bytes`/`sha256`/
`zipDataOffset`（后者只是被前面变小的大库挪位）后**逐字段相同**：

```
arm64 semantic diff (needed/exportCount/align): []
x64   semantic diff (needed/exportCount/align): []
failures same: True
```

例：`libastcenc.so` 剥离前后都是 `soname=libastcenc-neon-shared.so`、
`needed=[libm.so, libdl.so, libc.so]`、`exportCount=700`、`minLoadAlign=4096`。

## 4. 符号归档（对应回最终二进制）

剥离前的原件（AGP strip 的**输入**）归档到受保护的本地产物目录，不进 `build/`、
不随 `clean` 清理：

```
local/refactor-artifacts/native-strip/arm64-v8a/*.so     （11 个）
local/refactor-artifacts/native-strip/x86_64/*.so        （11 个）
```

登记在 `docs/refactor/artifact-manifest.json` 的 `t51NativeSymbols`：
每个文件记录 `unstrippedBytes`/`unstrippedSha256` 与对应的
`strippedBytes`/`strippedSha256` —— 符号能对应回最终二进制。

## 5. 16 KB 页兼容（**独立问题，strip 不修链接对齐**）

- ZIP 侧：APK 内 `lib/*.so` 未压缩存放且数据偏移 16 KB 对齐 —— 由
  `PlatformBehaviorTest.packagedJniLibrariesAre16KbAligned` 在设备上逐条核对。
- 链接侧：`libastcenc.so` 的 `PT_LOAD p_align` 仍是 **4096**（4 KB），
  已在 T15 登记为已知例外（来源不明、无法重编），设备用例会**要求它仍然是 4 KB**，
  哪天换成 16 KB 版本就会红，提醒更新登记与兼容声明。
- 本轮 strip **没有**改变任何 `p_align`（对照表见 §3），因此既没有修好也没有引入
  16 KB 问题。必要的 native 兼容更新与本次减包收益是两件事，分开记录。

## 6. 复验

```powershell
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Invoke-Bd2Gradle :app:assembleArm64Release :app:assembleX64Release --console=plain   # 日志里不应再有 "Unable to strip"
Test-Bd2Python (Join-Path $Root 'tools/audit_native_abi.py') --apk app/build/outputs/apk/arm64/release/app-arm64-release.apk --abi arm64-v8a
Test-Bd2Device -TestClass 'com.bd2toolsbox.platform.PlatformBehaviorTest' -Abi 'x86_64'
```
