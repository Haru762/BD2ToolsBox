# PythonRuntime ABI 选择修复证据

日期：2026-09-24
分支：`refactor/android-architecture`
目标提交基线：`8c121c3`（本轮实现尚未提交）

## 修复范围

新增 `core/python/PythonStartup.kt` 和 `PythonRuntime.kt`，把生产侧 Python 初始化集中到一个入口。入口从已安装 APK 的 `ApplicationInfo.nativeLibraryDir` 末级目录判断真实 native payload：`arm64` / `arm64-v8a` 归一化为 `arm64-v8a`，`x86_64` 保持为 `x86_64`。未知路径直接拒绝，不回退到 `Build.SUPPORTED_ABIS`。

启动顺序为：检查 Chaquopy 资产 → 构造 `AndroidPlatform` → 覆盖其内部 ABI 选择 → 调用 `Python.start`。整个 check/select/start 在 `Python.class` monitor 下完成，沿用 Chaquopy 15.0.1 的同步对象和失败状态。

已接入：

- `CharacterRepository`
- `ModRepository`
- `PrepackService`
- `MainViewModel` 的 3 个启动点
- `RestoreBundleDeterminismDeviceTest`
- `ReleasePythonDependencyProbeTest` 的未启动 fallback

当前 `app/src/main/java`、`app/src/androidTest` 和 release-probe 中，真正的 `Python.start` / `AndroidPlatform` 仅保留在 `PythonRuntime.kt` 和低层诊断注释中。

## TDD / JVM 结果

先运行缺少 `PythonStartup` 实现的单测，编译按预期失败（RED）：`Unresolved reference: PythonStartup`。

实现后：

```text
:app:testDebugUnitTest --tests com.bd2toolsbox.core.python.PythonStartupTest
BUILD SUCCESSFUL
```

覆盖 ABI 规范化、尾斜杠、未知路径拒绝、已启动幂等、失败传播、同步和 8 线程竞争。

最终全量 app JVM：

```text
:app:testDebugUnitTest
BUILD SUCCESSFUL in 34s
```

Python 宿主回归：

```text
python tools/run_python_tests.py --start-dir tests \
  --output local/python-runtime-abi-20260924/python-regression.json
```

结果：`discovered=124 executed=124 passed=124 failed=0 errors=0 skipped=0 notRun=0`。

## Release probe 构建结果

```text
:release-probe:testDebugUnitTest :release-probe:assembleDebug --rerun-tasks
BUILD SUCCESSFUL
```

probe JVM 已知测试集为 `6 tests, 0 failures, 0 errors, 0 skipped`。

`:app:assembleRelease --rerun-tasks` 在 debug keystore 环境变量完整提供后成功。产物身份仍为 `versionCode=17`、`versionName=0.2.16`，三份 APK 的证书 SHA-256 均为：

```text
892d95f0be2e42fa1293247e5153c1b90d04adb13a70ebff1e63a705156bdbd1
```

构建仍有仓库既有 Kotlin 警告和 Chaquopy 的 Python 3.11 buildPython `.pyc` 警告；本轮没有改变这些警告。

AndroidTest Kotlin 编译：`:app:compileDebugAndroidTestKotlin` 通过，只有仓库已有 nullable receiver 警告。

## x86_64 实机/模拟器验证

使用全新 `bd2-x64-api34` AVD，安装 minified x86_64 Release 和 universal Release，均强制停止目标进程后运行：

```text
ReleasePythonRuntimeProbeTest#productionRuntimeImportsAndBc1
```

两次均通过，且证明的是**实际生产入口**而非测试侧 ABI override：

- payload ABI：`x86_64`
- 生产入口最终选择：`x86_64`
- Python：`3.8.18`
- zlib：`bootstrap-native/x86_64/zlib.so`
- 9 个直接依赖 + 3 个 bridge 模块 import：全部通过
- native decoder：20 个导出、真实 smoke：通过
- 重复 `ensureStarted`：3 次，Python singleton 保持不变
- universal APK 在 x86_64 设备上同样选择 `x86_64`

随后单独运行修正后的 `ReleasePythonDependencyProbeTest`（2 tests），结果：`OK (2 tests)`。它的未启动路径已经改为反射调用生产 `PythonRuntime.ensureStarted`，不会再次使用默认 Chaquopy ABI 选择。

原始输出保留在：

```text
local/python-runtime-abi-20260924/runtime-probe-x64.log
local/python-runtime-abi-20260924/runtime-probe-universal-x64.log
local/python-runtime-abi-20260924/dependency-probe-universal-x64.log
```

## ARM64 边界

本轮没有真实 ARM64 硬件，未把 x86_64 + ARM 转译结果写成原生 ARM64 验收。`bd2-arm64-api34` 当前使用本机 x86_64 system image，不能作为 native ARM64 证据；本轮未以它产生任何通过结论。

因此：

- ABI 选择逻辑已在 x86_64 split/universal 的真实 minified Release 上验证；
- ARM64 payload 的生产入口选择、Python import 和 native decoder 仍需真实 ARM64 设备或明确可接受的 ARM64 运行环境；
- T10/T12 不因本轮而被虚假标记为完全 DONE。

## 产物和审查

- release 产物哈希与映射记录：`local/python-runtime-abi-20260924/artifacts-after.json`
- 旧产物保全：`local/python-runtime-abi-20260924/before/`
- 独立审查已处理：
  - standalone dependency probe 绕过生产入口；
  - `RestoreBundleDeterminismDeviceTest` 直接启动 Python；
  - `repeatedStarts` 误导性指标，改为 `repeatedEnsureCalls`。
- 未修改 native `.so`、Python 依赖、安装恢复链或游戏数据。
