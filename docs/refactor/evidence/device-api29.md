# API 29（minSdk 边界）设备验收

- 日期：2026-10-12（Asia/Shanghai）。
- 设备：本地 AVD `bd2-x64-api29`，镜像 `system-images;android-29;google_apis;x86_64`，
  serial `emulator-5554`，x86_64，**API 29（= minSdk）**，页大小 4096。
- 为什么重要：`minSdk = 29`，所以 API 29 是**支持范围的下边界**；
  新 API 的用法、FUSE 语义、WebView 版本都可能在边界上不同。

## 1. 结果总览

| 类别 | 结果 |
|---|---|
| 通过 | **13 个类 / 68 次用例执行**（全部第 1 次尝试） |
| 前提不成立 | `GameFileCommitTest`（13 条里 3 条权限用例） |
| 已修（测试断言层） | `WebContentThemeTest#colourSchemeIsDeclaredSoFormControlsMatch` —— 修后 **6/6 PASS** |

通过的类：`AndroidPythonStagingTest` 4、`NativeEngineSmokeTest` 9、
`InstallServiceRunHostTest` 1、`ScanServiceTest` 5、`InstallServiceLifecycleTest` 4、
`ProcessDeathRecoveryTest` 4、`SnapshotStoreInstanceReuseTest` 1、`InsetsAdaptationTest` 4、
`ThemeModeResolutionTest` 5、`RecoveryBannerTest` 6、`TaskPanelLifecycleTest` 6、
`DestructiveActionConfirmationTest` 12、`ComponentContractTest` 7。

**Python 引擎、服务宿主、扫描、恢复、主题、Insets 在 minSdk 边界上都正常。**

## 2. 发现一：API 29 的外部卷**不兑现** `setPosixFilePermissions`

### 症状

`GameFileCommitTest` 的 3 条权限用例失败：

```
前提：本用例必须能控制父目录的权限位，否则判据无从谈起
  （实测 [OWNER_EXECUTE, OWNER_WRITE, GROUP_WRITE, OTHERS_EXECUTE, GROUP_READ, GROUP_EXECUTE, OWNER_READ]）
  expected:<[OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, GROUP_READ, GROUP_EXECUTE]>
   but was:<[OWNER_EXECUTE, OWNER_WRITE, GROUP_WRITE, OTHERS_EXECUTE, GROUP_READ, GROUP_EXECUTE, OWNER_READ]>
```

即：把父目录设成 `0750`，**读回是 0775**。这个卷 `setPosixFilePermissions` **既不抛异常、
也不生效**。

### 对照（同一份代码、同一个测试）

| 环境 | `GameFileCommitTest` |
|---|---|
| MuMu `127.0.0.1:16384`（API 32） | **13/13 PASS** |
| 16 KB AVD `emulator-5556`（API 36） | **13/13 PASS** |
| API 29 AVD `emulator-5554` | 3 条权限用例**前提不成立** |

所以**不是本仓库的回归**：代码在 API 30+ 上兑现契约，在 API 29 的该卷上无法兑现。

### 顺带修掉的**测试缺陷**（真实问题）

`commitDoesNotDowngradeTargetPermissions` 原先只 catch `UnsupportedOperationException`，
**不校验设置是否生效**。于是在这种卷上它报的是
「提交后必须保持目标原有权限，不许被暂存件的默认权限降级」——
把**平台的限制说成我们的回归**。已补上前提判定，现在报的是：

```
这个卷**不兑现** setPosixFilePermissions（设成 [OWNER_READ, OWNER_WRITE, GROUP_READ, OTHERS_READ]，
读回 [OWNER_WRITE, GROUP_WRITE, GROUP_READ, OWNER_READ]）——
「提交后保持目标权限」这条契约在该卷上无法成立，本用例的前提不成立。
这不是本次提交的回归；已登记在 docs/refactor/evidence/device-api29.md
```

（另两条用例本来就有前提断言，报错一直是清楚的。）

### 产品影响（登记，未解决）

`minSdk = 29`。若真实 API 29 设备的外部卷同样不兑现权限设置，那么
**「提交后保持目标权限」这条契约在 API 29 上无法保证** —— `__data` 会拿到该卷的默认
权限（实测 0664），而不是目标原有权限。对「游戏进程按自己的 uid 读 `__data`」这件事
是有实际影响的。

**未定位**：这是 Android 10 的 FUSE 语义，还是 emulator 镜像的差异 ——
需要一台**真实 API 29 设备**才能区分。本轮不改代码（平台行为，应用侧无法绕过 FUSE），
也**不**把测试改成静默跳过。

## 3. 发现二：API 29 的 WebView 不支持 `color-scheme`（已修测试）

`WebContentThemeTest#colourSchemeIsDeclaredSoFormControlsMatch` 失败：

```
org.junit.ComparisonFailure: expected:<["dark"]> but was:<[null]>
```

该镜像的 WebView 不认识 `color-scheme` CSS 属性（`getComputedStyle().colorScheme` → null）。
这是**渐进增强**：它让表单控件/滚动条跟着页面走，而正文/链接/组件底色都是显式设的
（同一类里其它 5 条断言在 API 29 上照样通过）。

原断言把一个渐进增强当成硬要求。已改成两层：

1. **硬断言**（与 WebView 版本无关）：我们注入的 CSS 里确实有 `color-scheme: dark|light`；
2. **软断言**：支持该属性的 WebView 上，计算值必须是 `dark`/`light`；不支持时
   `Log.i` 如实记录，不静默跳过。

修后 API 29 上 **6/6 PASS**。

## 4. 环境与恢复

- 启动命令：`emulator -avd bd2-x64-api29 -no-window -no-audio -no-snapshot
  -gpu swiftshader_indirect -port 5554`。
- **未**加进 `deviceWhitelist`：它与 MuMu、16 KB AVD 同为 x86_64，多台同时在线时
  `Get-Bd2Device` 会按设计报「必须显式指定 serial」；本轮用 `am instrument` 直接驱动，
  没有走白名单。
- 跑 API 29 用例时需先关掉其它同 ABI 的模拟器（或按 serial 直连）。

## 5. 仍未覆盖

- **真实 API 29 设备**：本轮只有 emulator 镜像，无法区分「Android 10 的 FUSE 语义」
  与「emulator 镜像差异」。
- **API 30/31/33/34/35**：AVD 里还有 `bd2-arm64-api34` 等，本轮未跑。
- **arm64 真实设备**：仍缺。
- 无独立审查。
