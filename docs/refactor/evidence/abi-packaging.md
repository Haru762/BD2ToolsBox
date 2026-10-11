# ABI 打包方式（T49）

- 日期：2026-10-11。
- 结论：ABI 发布改为**两个单 ABI flavor**（`arm64` / `x64`），不再用 ABI splits，也不再默认产出 universal。

## 1. 改前（splits）

```kotlin
defaultConfig {
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "x86_64"); isUniversalApk = true } }
    ndk { abiFilters.addAll(listOf("x86_64", "arm64-v8a")) }
}
buildTypes { release { ndk { abiFilters.add("arm64-v8a") } } }
```

实测缺陷（`docs/refactor/evidence/apk-before-arm64.json`）：

- `lib/` 目录**只有** `arm64-v8a`（看起来对），但 **`assets/chaquopy/` 里同时有 `stdlib-x86_64.imy` 与
  `requirements-x86_64.imy`** —— 「lib 目录正确」不等于「整个包正确」。
- 还多产一个 `app-universal-release.apk`，容易被误上传或被更新器选错。
- `release` buildType 把 `abiFilters` 写死成 `arm64-v8a`，与 splits 语义重叠。

## 2. 改后（flavor）

```kotlin
flavorDimensions += "abi"
productFlavors {
    create("arm64") { dimension = "abi"; ndk { abiFilters.add("arm64-v8a") } }
    create("x64")   { dimension = "abi"; ndk { abiFilters.add("x86_64") } }
}
```

- 两个 flavor 各自成为一个 variant，`ndk.abiFilters` 只含自己的 ABI，Chaquopy 因此只生成
  本 ABI 的 `stdlib-<abi>.imy` / `requirements-<abi>.imy`。
- `splits` 块与 `release` 的 `abiFilters` 一并删除；**不再产出 universal**。
- `defaultConfig` 的 `applicationId` / `versionCode` / `versionName` 与 `signingConfigs.release`
  不动 → 两个包**同应用、同签名、同版本**，可互相原地升级。

### 产物与任务

| 项 | 值 |
|---|---|
| 任务 | `:app:assembleArm64Release` / `:app:assembleX64Release` |
| 产物 | `app/build/outputs/apk/arm64/release/app-arm64-release.apk` / `apk/x64/release/app-x64-release.apk` |
| 单元测试 | `:app:testArm64DebugUnitTest` / `:app:testX64DebugUnitTest` |
| 设备测试 | `:app:assembleArm64Debug` + `:app:assembleArm64DebugAndroidTest`（x64 同理） |
| androidTest APK | `app/build/outputs/apk/androidTest/<flavor>/debug/app-<flavor>-debug-androidTest.apk` |
| R8 mapping | `app/build/outputs/mapping/<flavor>Release/mapping.txt` |

### Chaquopy 只在 :app 应用

`:release-probe` 是独立应用模块，**没有** `chaquopy` 插件（见 `settings.gradle.kts` 注释与 T04 证据），
所以不会出现「第二个模块也打一份 Python 运行时」。

## 3. verification.ps1 的任务映射

| 函数 | 改动 |
|---|---|
| `Get-Bd2ApkForAbi` | 先找 flavor 布局 `apk/<flavor>/<buildType>/app-<flavor>-<buildType>.apk`，找不到再回退旧的 splits 布局 |
| `Get-Bd2MappingForAbi` | 新增：`mapping/<flavor>Release/mapping.txt`，回退 `mapping/release/mapping.txt` |
| `Get-Bd2InstrumentationApk` | 新增 `-Abi`，找 `apk/androidTest/<flavor>/debug/*.apk`，回退旧布局 |
| `Test-Bd2Device` | 构建 `:app:assemble<Flavor>Debug :app:assemble<Flavor>DebugAndroidTest` |
| `Get-Bd2UnitTestTasks` | 已在 T49 之前预留：检测到 per-flavor 任务就用 `arm64Debug+x64Debug` |

`Test-Bd2Unit` 现在在**两个 flavor 变体**上各跑一遍（`Unit [arm64Debug+x64Debug]`），
所以同一份 JVM 用例会被验证两次 —— 这是有意的：两个 flavor 共用同一份代码，
但变体级的配置差异（ABI 过滤、Chaquopy 资产）必须在两个变体上都成立。

## 4. 顺带核对到的旧缺陷

`release` 的 `proguardFiles` 里写死 `build/generated/chaquopy/proguard.pro`。该路径在
Chaquopy 16.1.0 **已不存在**，每次 release 构建都会打印
`Supplied proguard configuration does not exist`。核对 `mapping/<variant>Release/configuration.txt`
的 section 头可见，Chaquopy 自己接入的是 `app/build/python/proguard-rules.pro`
（`-keep class com.chaquo.python.** { * ; }` 等），规则**并未缺失**，所以这条是纯噪声 +
误导（会让人以为 Chaquopy 规则没进 R8）。已删除该死路径，重建后 warning 归零、
`configuration.txt` 里 Chaquopy 规则仍在。

## 5. 体积影响

| 包 | 改前（splits） | 改后（flavor） | 变化 |
|---|---:|---:|---:|
| arm64 | 33.5184 MiB | 28.8306 MiB | **−4.6878 MiB（−13.99%）** |
| x86_64 | 33.4246 MiB | 28.9009 MiB | **−4.5237 MiB（−13.53%）** |

差额来自 Chaquopy 资产：arm64 包不再带 `stdlib-x86_64.imy` / `requirements-x86_64.imy`
（19.8582 → 15.2676 MiB）。

（T50 之后的最终值见 [T50.md](T50.md) 与 [apk-flavors-arm64.json](apk-flavors-arm64.json) /
[apk-flavors-x64.json](apk-flavors-x64.json)。）

## 6. 未覆盖

- 未在 arm64 真机上安装/冷启动这两个包（真机 `f7f409ac` 离线）。
- 未跑 `Test-Bd2ReleaseSmoke` 的 `resources-and-licenses` / `engine-and-preview` 用例 ——
  该函数目前只实现 `baseline-startup` 与 `snapshot-compatibility`（见 T49 证据 §未完成）。
