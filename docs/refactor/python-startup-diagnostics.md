# Release Python 启动分阶段诊断报告

> 文档状态：**已完成诊断（DIAGNOSTIC_COMPLETED，非全绿）**（2026-09-22）。
> 诊断标记：`TRANSLATED_ARM_DIAGNOSTIC`。
> 门槛说明：实测环境为 x86_64 模拟器配合 ARM 转译层，**绝非原生 ARM64 真实设备**；应用在转译环境下仍于 `startNative` 阶段进程异常终止（退出码 1，启动失败），不能作为 T10 或 T12 的通过依据，T10 保持 **IMPLEMENTED_NOT_VERIFIED**，T12 保持 **IN_PROGRESS**，不推进 T13。

---

## 1. 概述与背景

本轮诊断旨在修复测试探针对 ARM64 载荷目录别名的误判，并在官方转译环境中将原始 ARM64 Release APK 的 Python 启动中断点精确定位到 JNI 内部边界。

所有诊断代码与测试用例严格隔离于独立的 [`release-probe`](../../release-probe/) 模块，生产应用源码（`app/`）、R8 混淆规则、`tools/verification.ps1` 及生产签名未作任何修改。

### 提交沿革
- `39af163`: 修复载荷 ABI 目录名别名识别（将 `arm64` 规范化为 `arm64-v8a`，拒绝未知/模糊目录），新增 [`PayloadAbi.kt`](../../release-probe/src/main/java/com/bd2toolsbox/integration/PayloadAbi.kt) 及单测 [`PayloadAbiTest.kt`](../../release-probe/src/test/java/com/bd2toolsbox/integration/PayloadAbiTest.kt)。
- `c8032dd`: 新增分阶段启动探针辅助类 [`StartupStageTrace.kt`](../../release-probe/src/main/java/com/bd2toolsbox/integration/StartupStageTrace.kt) 与仪器测试用例 [`ReleasePythonStartupProbeTest.kt`](../../release-probe/src/main/java/com/bd2toolsbox/integration/ReleasePythonStartupProbeTest.kt)。
- `1ce7c28`: Task 2 Fix R1 强化单测断言（`assertSame` 实例同一性断言与异常传播阻断验证），清理无用局部变量绑定。
- `07f609d`: Task 3 新增 `tracedNativeStartOnly` 探针，在 `synchronized(pyClass)` 下将 `platform.getPath()` 与 private JNI `startNative` 独立拆分调用。

---

## 2. 环境配置与安全操作规程

### 2.1 诊断运行环境
- **设备**: 官方 Android Emulator AVD `bd2-x64-api34`，serial `emulator-5260`。
- **架构与转译**: 原生 `x86_64`，搭载 Google 官方 ARM 动态转译库 `libndk_translation.so`。
- **运行分类**: 属于 `TRANSLATED_ARM_DIAGNOSTIC`。此前将崩溃归咎于 MuMu 模拟器 `libhoudini.so` 专有缺陷缺乏充分证据；搭载 Google 官方 ARM 转译器（`libndk_translation.so`）的 AVD 在 JNI 初始化阶段同样发生进程非预期终止（退出码 1）。不同转译桥与 APK 环境下的相似症状不能证明底层 C 原因完全一致，亦不能断言任何一款转译器是根本原因。

### 2.2 严格操作规程
1. **独立进程隔离**：
   每个测试用例**必须**在独立的全新 OS 进程中分别运行，用例之间及结束时显式执行 `force-stop`，禁止整类或整包批量执行，以防止静态/native 运行时状态污染。在当前转译诊断环境下，包含 Python/Native 初始化的用例预期在 `startNative` 阶段终止失败而非通过（PASS），无需额外创建测试脚本框架：
   ```powershell
   # 1. 无 Python 控制组（预期 PASS）
   adb -s emulator-5260 shell am force-stop com.bd2toolsbox
   adb -s emulator-5260 shell am instrument -w -r -e class "com.bd2toolsbox.integration.ReleasePythonStartupProbeTest#controlWithoutPython" com.bd2toolsbox.releaseprobe/androidx.test.runner.AndroidJUnitRunner
   adb -s emulator-5260 shell am force-stop com.bd2toolsbox

   # 2. 全流程 Python 启动探针（预期在 python.start 阶段终止失败）
   adb -s emulator-5260 shell am force-stop com.bd2toolsbox
   adb -s emulator-5260 shell am instrument -w -r -e class "com.bd2toolsbox.integration.ReleasePythonStartupProbeTest#tracedPythonStartup" com.bd2toolsbox.releaseprobe/androidx.test.runner.AndroidJUnitRunner
   adb -s emulator-5260 shell am force-stop com.bd2toolsbox

   # 3. 隔离 Native JNI 启动探针（预期在 python.startNative 阶段终止失败，退出码 1）
   adb -s emulator-5260 shell am force-stop com.bd2toolsbox
   adb -s emulator-5260 shell am instrument -w -r -e class "com.bd2toolsbox.integration.ReleasePythonStartupProbeTest#tracedNativeStartOnly" com.bd2toolsbox.releaseprobe/androidx.test.runner.AndroidJUnitRunner
   adb -s emulator-5260 shell am force-stop com.bd2toolsbox
   ```
   亦可通过受控循环按序执行（用例间及末尾显式 force-stop）：
   ```powershell
   $methods = @('controlWithoutPython', 'tracedPythonStartup', 'tracedNativeStartOnly')
   foreach ($m in $methods) {
       adb -s emulator-5260 shell am force-stop com.bd2toolsbox
       adb -s emulator-5260 shell am instrument -w -r -e class "com.bd2toolsbox.integration.ReleasePythonStartupProbeTest#$m" com.bd2toolsbox.releaseprobe/androidx.test.runner.AndroidJUnitRunner
       adb -s emulator-5260 shell am force-stop com.bd2toolsbox
   }
   ```
2. **禁止破坏性清除**：
   严禁执行 `pm clear` 或 `pm uninstall` 生产应用包 `com.bd2toolsbox`，防止破坏既有数据文件与权限上下文。
3. **探针生命周期与同签名要求**：
   探针 APK 在每次重新构建前必须将本地及设备端已有文件备份并登记 SHA-256；探针安装需与生产应用使用相同的本地基线签名证书覆盖安装；测试结束后必须恢复设备原有探针或卸载测试探针。严禁将证书凭据或本地私有绝对路径硬编码到仓库中。

---

## 3. Chaquopy 15.0.1 启动内部调用链（字节码查证）

通过反编译静态分析 `chaquopy_java-15.0.1.jar`（SHA-1 `f6b564301b4e5aaf8bfaa1b2076dc1d6786b0f6`），确认了关键类的执行序列与类加载顺序：

1. **`AndroidPlatform.<init>` 库加载顺序**：
   在实例化 `AndroidPlatform(Context)` 时，私有方法 `loadNativeLibs()` 依次通过 `System.loadLibrary` 加载：
   1. `crypto_chaquopy`
   2. `ssl_chaquopy`
   3. `sqlite3_chaquopy`
   4. `python3.8`
   5. `chaquopy_java`
2. **`Python.start(Platform)` 内部执行序列**（字节码 `Python_javap.utf8.txt` 偏移 0-51 查证）：
   - `isStarted()` 检查（要求尚未启动）
   - `failed` 标志检查
   - `platform.getPath()`：通过 Java 平台对象提取应用文件系统路径
   - `startNative(platform, path)`：private static native JNI 方法，进入 C/C++ 运行时核心初始化
   - `platform.onStart(instance)`：字节码偏移 40-44 直接通过 `getstatic` 将静态初始化的 `instance` 单例传入 `onStart(instance)`，**绝非调用公共 `getInstance()` getter**（若在 `platform` 字段赋值前调用公共 getter 会因 `isStarted() == false` 递归重入 `start`；注：字节码 `static {}` 偏移 0-10 在 `python.class.init` 类初始化时已构建 `instance = new Python()` 并赋给静态字段，单例已就绪）
   - `platform = platform`：字节码偏移 48 写入静态 `platform` 字段，完成启动标志
3. **隔离探针的设计考量**：
   公共 `Python.getInstance()` 在 `platform` 尚未就绪时因检查未通过会再次尝试启动，在局部诊断中属于不安全调用。因此，Task 3 的 `tracedNativeStartOnly` 探针在反射调用 private `startNative` 后，**刻意不调用** `getInstance()` 或 `onStart()`，也不修改静态 `platform` 字段，从而干净切分底层 JNI 初始化边界。若原生调用返回，未完成的是 `platform.onStart(instance)` 与静态 `platform` 赋值（故 `isStarted()` 仍为 false），不可误认为单例 `Python.instance` 未构建。

---

## 4. 诊断执行事实与证据

所有运行日志、logcat 流及中间数据保存在 `local/pi-resume/python-startup-stages/` 下（被 Git 忽略）。

### 4.1 Task 2 实测结果
- `controlWithoutPython`：**PASS**（PID 4173）。断言 `Application.getProcessName()` 为 `com.bd2toolsbox`，目标应用 `FLAG_DEBUGGABLE == 0`，不加载任何 Python 相关类。
- `tracedPythonStartup`（运行 1）：**Process crashed**（PID 4229）。完整通过 `before/after platform.construct`，停在 `before python.start`，无 `after` 标记。测试 runner 报 `shortMsg=Process crashed.`（见 `local/pi-resume/python-startup-stages/task2/traced_stdout.txt`）。
- `tracedPythonStartup`（运行 2）：**Process crashed**（PID 4312）。与运行 1 表现完全一致（见 `local/pi-resume/python-startup-stages/task2/traced2_stdout.txt`）。
- **Task 2 日志审计说明**：经审计，`task2/logcat.txt` 未捕获自身 PID 4229/4312 的系统退出行，仅有 runner 报告的 `shortMsg=Process crashed.` 与退出码 0，故不为 Task 2 外推特定退出码。
- **TDD 执行记录**：Task 2 初始 RED 阶段为编译失败（`Unresolved reference: StartupStageTrace`），并非断言失败型 RED；实现辅助类后编译通过且测试转 GREEN。随后在 Fix R1 审查中，通过引入可逆的吞异常变异体（swallow-exception mutant）验证了断言失败（RED），还原后单测全部通过。如实记录此编年过程，不追溯改写 TDD 记录为初始即断言合规。

### 4.2 Task 3 实测结果
详细结果记录于 [`task3/result.json`](../../local/pi-resume/python-startup-stages/task3/result.json)：
- `controlWithoutPython`：**PASS**（PID 5262）。
- `tracedPythonStartup`：**终止失败**（PID 5328）。停在 `before python.start`，无 `after` 标记。runner 报 `shortMsg=Process crashed.`，Zygote 明确记录 `Process 5328 exited cleanly (1)`（见原始日志 `local/pi-resume/python-startup-stages/task3/logcat_tracedPythonStartup_1.txt:82`，无 Fatal signal/SIGSEGV/SIGABRT/backtrace）。
- `tracedNativeStartOnly`（运行 1，PID 5385）：
  - 成功输出 `after platform.getPath`（路径提取正常）
  - 成功输出 `after python.startNative.lookup`（反射获取 private native 方法成功）
  - 输出 `before python.startNative`
  - 紧接着进程以退出码 1 终止（runner 报 `shortMsg=Process crashed.`，Zygote 明确记录 `Process 5385 exited cleanly (1)`，见原始日志 `local/pi-resume/python-startup-stages/task3/logcat_tracedNativeStartOnly_1.txt:89`，无信号或回溯），未输出 `after` 标记，亦未输出 `NATIVE_ONLY_RETURNED`。
- `tracedNativeStartOnly`（运行 2，PID 5442）：
  - 过程与运行 1 逐标记一致，终止在同一调用点（Zygote 明确记录 `Process 5442 exited cleanly (1)`，见原始日志 `local/pi-resume/python-startup-stages/task3/logcat_tracedNativeStartOnly_2.txt:88`，无信号或回溯）。

### 4.3 事实推论边界与证据校正（2026-09-23 补正）
- **观察到的确证边界**：进程终止严格发生在进入 private native JNI 方法 `startNative(Platform, String)` 之后、其正常返回之前（介于 `before python.startNative` 与 `after` 标记之间，未观察到成功返回）。Java 端反射机制、`platform.getPath()` 以及 `AndroidPlatform` 构造过程中的动态库加载均无故障。
- **终止特征与推论限制（事实校正）**：
  - **非硬件信号崩溃**：Task 3 原始 logcat（`logcat_tracedPythonStartup_1.txt:82`、`logcat_tracedNativeStartOnly_1.txt:89`、`logcat_tracedNativeStartOnly_2.txt:88`）一致记录为 `Process <PID> exited cleanly (1)`，全文**无** `Fatal signal`、`SIGSEGV`、`SIGABRT` 或 tombstone backtrace。Instrumentation runner 输出的 `shortMsg=Process crashed.` 属于 Android 测试框架在目标进程非预期终止时的通用报错，绝非硬件段错误或 abort 的证据。
  - **启动失败而非成功**：`exited cleanly (1)` 明确代表 Python 启动失败，绝不能解释为启动成功。现有证据无法断言究竟是哪个具体线程或底层 C 函数发起了退出，亦不能断言属于转译器专有缺陷。切勿将其与此前 MuMu 模拟器上观察到的 destroyed mutex（SIGABRT）相混淆。此结论收敛了调用边界，不构成修复方案。

---

## 5. 产物完整性与环境验证

### 5.1 生产载荷与混淆映射表完整性
对比验证证据 [`final-verification/device-after.json`](../../local/pi-resume/python-startup-stages/final-verification/device-after.json)：
- **目标 APK** (`app/build/outputs/apk/release/app-arm64-v8a-release.apk`):
  - 字节数：`33,816,761` 字节
  - SHA-256：`e2299b2782d95d02c55f19c8e70d45be40297f45e2cee4105403c85874118446`（与基线完全一致，未修改、未重编、未重签）
  - 属性：`FLAG_DEBUGGABLE == 0`（non-debuggable）
- **混淆映射表** (`app/build/outputs/mapping/release/mapping.txt`):
  - 字节数：`36,397,682` 字节
  - SHA-256：`8c9e057eec4047a6da974597c7aa2ca2af8ea39eb3265020b3e50fd36cc57daf`（完全一致）

### 5.2 设备端保全与恢复状态
- Task 3 恢复的是其自身前置的设备探针（SHA-256 `13c577e3f3c0b872d93696582f44ed3ad58134d0f8d012421023ded57f78bd46`，4,564,353 字节），测试后已逐字节恢复到设备，并非早先缺失的 `2306c941...`。
- 最终设备状态核查（[`device-after.json`](../../local/pi-resume/python-startup-stages/final-verification/device-after.json)）：
  - `debug_app`: `null`
  - `wait_for_debugger`: `0`
  - 无残留端口转发（`forwards` 为空）
  - 无残留调试器或 lldb 进程（`debug_processes: []`）

### 5.3 历史未满足前置项（Operational FAIL）
在 Task 2 执行中，本地旧探针 APK（SHA-256 `2306c9418405a0f4ae808b527c384cdfecd8dc3ff9cbd98e5dd12a01e415fc46`，4,552,745 字节）哈希已知，但在重新构建前遗漏备份导致字节保全失败。在后续修复排查中，已检查的本地备份未找到同哈希原件，未能复原；对先前探针状态不作超出实据的推断。Task 2 在设备端仅完成卸载，未能逐字节还原该旧探针。

该历史失误被如实记录为操作前置项 **FAIL**，不伪称通过。因该未满足的前置项以及应用在转译环境下启动失败（进程以退出码 1 终止）依旧存在，本诊断计划整体状态为**非全绿**。生产 APK 及 mapping 均未受任何影响。

### 5.4 探针模块单测最新复验
主控最新复验单测（运行命令 `./gradlew :release-probe:testDebugUnitTest --rerun-tasks`）：
- **测试结果**（见 [`final-verification/unit-result.json`](../../local/pi-resume/python-startup-stages/final-verification/unit-result.json) 与 [`unit.log`](../../local/pi-resume/python-startup-stages/final-verification/unit.log)）：
  - `com.bd2toolsbox.integration.PayloadAbiTest`：4/4 PASS，0 skip，0 failure，0 error。
  - `com.bd2toolsbox.integration.StartupStageTraceTest`：2/2 PASS，0 skip，0 failure，0 error。
  - 合计 6 测试通过，退出码 0。
- XML 测试报告证据：
  - [`PayloadAbiTest.xml`](../../local/pi-resume/python-startup-stages/final-verification/TEST-com.bd2toolsbox.integration.PayloadAbiTest.xml)
  - [`StartupStageTraceTest.xml`](../../local/pi-resume/python-startup-stages/final-verification/TEST-com.bd2toolsbox.integration.StartupStageTraceTest.xml)

---

## 6. 结论与后续注意事项

1. **JNI 调用边界收敛**：Chaquopy 启动失败明确收敛至 private JNI 方法 `startNative` 调用期间（进程以退出码 1 终止，无成功返回）。
2. **KDoc 使用警告**：[`ReleasePythonStartupProbeTest.kt`](../../release-probe/src/main/java/com/bd2toolsbox/integration/ReleasePythonStartupProbeTest.kt) 已增加文档警告，说明 `tracedNativeStartOnly` 即使成功返回也仅属于局部原生初始化，Java 层的 `platform.onStart(instance)` 与静态 `platform` 赋值尚未完成（单例 `Python.instance` 已在 `<clinit>` 构建），不可在此状态下继续调用 Python 方法，亦不可将其作为运行时就绪的标志。
3. **后续推进**：针对 ARM64 的真正验收必须依赖原生 ARM64 硬件环境，禁止将模拟器转译环境的运行结果外推为 T10 或 T12 的通过条件。T10 保持 **IMPLEMENTED_NOT_VERIFIED**，T12 保持 **IN_PROGRESS**，不推进 T13。
