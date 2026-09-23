# Release Python 分阶段启动探针实现计划

> 状态：**DIAGNOSTIC_COMPLETED（非全绿 / NOT ALL-GREEN）**（2026-09-22）。
> - Task 1（规范化载荷 ABI 目录）：已完成（commit `39af163`）。
> - Task 2（分阶段启动探针与转译设备诊断）：代码与单测完成（commit `c8032dd`，fix in `1ce7c28`）；存在历史操作前置项 FAIL（旧探针 `2306c941...` 哈希已知，但构建前遗漏字节备份导致保全失败；已检查的本地备份未找到同哈希原件，未能复原；设备端仅执行卸载未逐字节恢复）。定位到 `python.start` 阶段进程中断/启动失败。
> - Task 3（分离路径准备与 JNI 初始化）：已完成（commit `07f609d`）；设备端成功逐字节还原的是 Task 3 自身前置的 `13c577e3...` 状态（非早先缺失的 `2306c941...`）。定位到 `startNative` JNI 调用边界进程终止（退出码 1，无返回）。
> - 生产 APK 与 mapping 哈希全程未变，目标保持 non-debuggable；转译环境诊断结果不代表原生 ARM 通过，T10 保持 **IMPLEMENTED_NOT_VERIFIED**，T12 保持 **IN_PROGRESS**，不推进 T13。
> - **面向 AI 代理的工作者：** 使用 subagent-driven-development，逐任务实现、审查。实施者使用 Antigravity；主控负责协调和独立复核。

**目标：** 修复测试探针对 ARM64 目录名的误判，并定位原始 ARM64 Release 在官方 x86_64 转译环境中的 Python 启动中断阶段。

**架构：** 仅修改独立的 `release-probe` 模块。用纯 Kotlin 辅助函数规范化已支持的 ABI，用带 BEFORE/AFTER/ERROR 标记的反射调用定位 Chaquopy 初始化。生产 APK、R8、业务源码及验收设备白名单不变。

**技术栈：** Kotlin/JUnit4、AndroidJUnitRunner、Chaquopy 15.0.1 的既有反射入口、ADB；宿主 PowerShell 5.1 的二进制输出必须用 Python subprocess 或 adb pull 保全。

## 全局约束

- 工作区 `D:/bd2 mod/worktrees/bdroid-android-refactor`，分支 `refactor/android-architecture`，起点 `b3c64de2368ceac5c39b16273ec54097532c9e56`。不 push、不合并、不启动 T13。
- 不改 `app/`、`tools/verification.ps1`、设备白名单及主重构计划的完成标记。`arm64` 目录别名修正不是授权转译设备通过原生 ARM 验收。
- 实测只用专用 AVD `bd2-x64-api34`、serial `emulator-5260`。不操作 MuMu `127.0.0.1:16385` / `127.0.0.1:7555`，不访问游戏文件。
- 此 AVD 是 x86_64 + `libndk_translation.so`，结果必须标记 `TRANSLATED_ARM_DIAGNOSTIC`。T10 保持 IMPLEMENTED_NOT_VERIFIED，T12 保持 IN_PROGRESS，不推进 T13。
- 生产 ARM APK 必须保留原字节：SHA-256 `e2299b2782d95d02c55f19c8e70d45be40297f45e2cee4105403c85874118446`，33,816,761 字节，non-debuggable。不重编、不重签、不替换它。
- 只运行 `:release-probe:testDebugUnitTest` / `:release-probe:assembleDebug`，不用会隐式 assembleRelease 的总验证入口。探针旧 APK 先在忽略目录备份，新旧探针哈希和签名分别登记。
- 原始证据放 `local/pi-resume/python-startup-stages/`；协作简报/报告放本计划 SDD 目录。不得提交 local、APK、mapping、secrets、patch.py、diff.txt。不在仓库根创建临时脚本。
- 编码使用 UTF-8；日志通过 subprocess 的文件句柄直接流式保存，不把未读取的 PIPE 放到命令结束才读。缺标记、超时、进程死亡都不是 PASS。

## Task 1：ABI 目录名称规范化

**文件：**
- 新增 `release-probe/src/main/java/com/bd2toolsbox/integration/PayloadAbi.kt`
- 新增 `release-probe/src/test/java/com/bd2toolsbox/integration/PayloadAbiTest.kt`
- 修改 `release-probe/src/main/java/com/bd2toolsbox/integration/ReleaseContractProbeTest.kt` 的载荷目录比较
- 仅在单测确实需要时修改 `release-probe/build.gradle.kts`，使用既有 catalog 中 JUnit，不引入新版本。

- [x] 提取当前“目录末段原样返回”行为供单测调用；先添加字面量断言，运行单测，保存 `arm64 != arm64-v8a` 的真实失败。

```kotlin
assertEquals("arm64-v8a", payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm64"))
assertEquals("arm64-v8a", payloadAbiFromNativeLibraryDir("/data/app/example/lib/arm64-v8a/"))
assertEquals("x86_64", payloadAbiFromNativeLibraryDir("/data/app/example/lib/x86_64"))
assertNull(payloadAbiFromNativeLibraryDir(null))
assertNull(payloadAbiFromNativeLibraryDir("/data/app/example/lib/unknown"))
```

- [x] 最小修复：只接受本项目两个载荷 ABI。空串、根目录、未知末段、含糊的 `arm` 不猜测。

```kotlin
internal fun payloadAbiFromNativeLibraryDir(path: String?): String? =
    when (path?.trimEnd('/')?.substringAfterLast('/')) {
        "arm64", "arm64-v8a" -> "arm64-v8a"
        "x86_64" -> "x86_64"
        else -> null
    }
```

- [x] baselineStartup 同时保留 raw nativeLibraryDir 诊断值，规范化后仍严格要求 `Build.SUPPORTED_ABIS.contains(payloadAbi)`；未知目录继续失败。不改变包名、签名、non-debuggable 等检查。
- [x] `:release-probe:testDebugUnitTest --tests '*PayloadAbiTest'` 通过，确认测试能拒绝未知目录、错误 ABI，而不是简单放行。
- [x] 任务范围代码审查通过后提交明确文件，不触碰设备和生产 APK。

## Task 2：分阶段启动探针与设备诊断

**文件：**
- 新增 `release-probe/src/main/java/com/bd2toolsbox/integration/StartupStageTrace.kt`
- 新增 `release-probe/src/test/java/com/bd2toolsbox/integration/StartupStageTraceTest.kt`
- 新增 `release-probe/src/main/java/com/bd2toolsbox/integration/ReleasePythonStartupProbeTest.kt`
- 不修改原 `ReleasePythonDependencyProbeTest.kt` 的验收含义。

- [x] 先测试 stage 的真实执行顺序和异常传播。实测记录：Task 2 初始 RED 阶段为编译失败（`Unresolved reference: StartupStageTrace`），并非断言失败型 RED；实现辅助类后编译通过测试转 GREEN；后续 Fix R1 审查中通过可逆的吞异常变异体（swallow-exception mutant）证实断言失败，恢复后单测通过。不追溯改写 TDD 编年史为初始即断言合规。一次正常操作应输出 before、执行操作、after；抛错时输出 before、error，原异常必须向上传播，不能伪造 after 或继续执行后续阶段。

```kotlin
val events = mutableListOf<String>()
val trace = StartupStageTrace { events += it }
val result = trace.stage("sample") { events += "work"; 7 }
assertEquals(7, result)
assertEquals(listOf("before sample", "work", "after sample"), events)
```

- [x] 最小 stage helper，事件格式固定。ERROR 不包含业务数据，rethrow 同一异常。

```kotlin
internal class StartupStageTrace(private val emit: (String) -> Unit) {
    fun <T> stage(name: String, action: () -> T): T {
        emit("before $name")
        return try {
            val value = action()
            emit("after $name")
            value
        } catch (t: Throwable) {
            emit("error $name")
            throw t
        }
    }
}
```

- [x] 新 AndroidJUnit4 类包含 `controlWithoutPython` 和 `tracedPythonStartup`。两者先断言 targetContext/application process 都是 `com.bd2toolsbox`、目标 non-debuggable。控制组不加载任何 Python 类，只发控制标记并正常完成。
- [x] `tracedPythonStartup` 用 targetContext.classLoader、沿用已有探针的反射 API，每步独立命名并包进 stage：
  1. `python.class.load`：`Class.forName("com.chaquo.python.Python", false, loader)`
  2. `python.class.init`：同类 initialize=true
  3. `python.isStarted`：反射 `isStarted()`，要求新进程中为 false；true 则明确失败，不跳过初始化冒充通过
  4. `platform.base.load`：`com.chaquo.python.Python$Platform`
  5. `platform.class.load`：`com.chaquo.python.android.AndroidPlatform`
  6. `platform.construct`：其 Context 构造函数
  7. `python.start`：`Python.start(platform)`
  8. `python.instance`：`Python.getInstance()`
  9. `python.sys`：`getModule("sys")`
  10. `python.version`：用已保留的 `PyObject.get` 读取 major/minor，断言 3.8
- [x] 标记通过 `instrumentation.sendStatus(2, Bundle)` 的 stream 和 Log 输出；前缀 `BD2_PY_STARTUP`，带 PID、阶段、before/after/error。仅完整版本断言后输出完成标记。不开 Activity、不 import main_script、不联网、不调用业务写操作。
- [x] 跑全部 probe JVM 单测，然后只构建 probe。核对原生产 APK 和 mapping 哈希未变。
- [x] 唯一设备实施者核对 emulator-5260 的 ABI、native bridge、安装 APK SHA、non-debuggable、无残留 debugger。若离线，只能重启这个测试 AVD，既有命令为 `emulator.exe -avd bd2-x64-api34 -ports 5260,5261 -no-snapshot-load -no-boot-anim -no-window -gpu swiftshader_indirect -feature -Vulkan -no-audio -no-metrics`；等待 boot 完成，不能修改系统镜像或使用 MuMu 替代。
- [x] 探针与目标同签名后只覆盖安装 probe；先运行修正的 baselineStartup，再在分别 force-stop 后的独立进程运行 controlWithoutPython、tracedPythonStartup 两次。每次保留完整 stdout/stderr、独立 PID、退出码、JUnit 数和直接写文件的 logcat。

```powershell
# 必须使用带引号的 class 参数，每个用例前后均显式 force-stop；包含 Python/Native 初始化的用例预期在转译环境下终止失败（非 PASS，未成功启动），无需创建额外脚本框架：
adb -s emulator-5260 shell am force-stop com.bd2toolsbox
adb -s emulator-5260 shell am instrument -w -r -e class "com.bd2toolsbox.integration.ReleasePythonStartupProbeTest#controlWithoutPython" com.bd2toolsbox.releaseprobe/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5260 shell am force-stop com.bd2toolsbox
adb -s emulator-5260 shell am instrument -w -r -e class "com.bd2toolsbox.integration.ReleasePythonStartupProbeTest#tracedPythonStartup" com.bd2toolsbox.releaseprobe/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5260 shell am force-stop com.bd2toolsbox
```

- [ ] **[FAIL: 历史前置项未满足]** 依据最后一对 before/after 精确报告中断阶段，未通过则保留 FAIL，不盲改业务代码。恢复本轮开始时的 probe 安装状态（原来不存在则仅卸载probe；原来存在则恢复其字节）。（注：Task 2 旧探针 `2306c941...` 哈希已知但构建前遗漏备份导致字节保全失败；已检查的本地备份未找到同哈希原件，未能复原；对先前探针状态不作过度推断。设备端仅执行卸载未逐字节恢复旧探针，确认为历史操作 FAIL。后续 Task 3 恢复的是其自身的 `13c577e3...` 前置状态，并非早先缺失的 `2306c941...`；生产 APK/mapping 完整无损。）核对原生产APK仍相同，无新debug设置或forward。主控独立复核后记录证据。
- [x] 任务及总体差异审查：必须明确“规格合规”和“代码质量”两轴，确认没有放宽原生ARM门槛。通过后只提交相关探针源码/测试/计划；T10 保持 IMPLEMENTED_NOT_VERIFIED、T12 保持 IN_PROGRESS，T13 未启动。

## Task 3：在同一原始载荷上分离路径准备与 JNI 初始化

**依据：** 两次全流程探针均完成 `platform.construct`，在 `python.start` 阶段进程非预期终止（runner 报 `shortMsg=Process crashed.`）。已检查 Chaquopy 15.0.1 字节码（`local/pi-resume/python-startup-stages/internals/Python_javap.utf8.txt` 偏移 0-10 与 40-44）：Python 类 `static {}` 静态块在 `python.class.init` 时已完成单例 `instance = new Python()` 的构建；AndroidPlatform 构造加载五个库；Python.start 在持有 Python.class monitor 时依次执行 getPath、private startNative，并直接向 `onStart(instance)` 传入已构造的静态单例 `instance`（不调用 getter），最后写入 platform。若 JNI 调用返回，未完成的是 `platform.onStart(instance)` 与静态 `platform` 字段赋值（故 `isStarted()` 仍为 false）；public getInstance 在 platform 未设置时因 `isStarted() == false` 会再次调用 start，在局部诊断中不安全，不能用于这个隔离实验。

**文件：** 仅扩展 `release-probe/src/main/java/com/bd2toolsbox/integration/ReleasePythonStartupProbeTest.kt`。继续使用已测试的 StartupStageTrace，不修改生产 APK。

- [x] 新增 `tracedNativeStartOnly`：与原用例相同的目标身份、non-debuggable、fresh-process 检查；允许抽取共用的准备步骤，原 tracedPythonStartup 的 stage 名和顺序不变。
- [x] 在 `synchronized(pyClass)` 内执行并独立标记 AndroidPlatform.getPath，然后查找和调用当前自有应用载荷的 private JNI 方法：

```kotlin
val path = trace.stage("platform.getPath") {
    platformBase.getMethod("getPath").invoke(platform) as String
}
val nativeStart = trace.stage("python.startNative.lookup") {
    pyClass.getDeclaredMethod("startNative", platformBase, String::class.java)
        .apply { isAccessible = true }
}
trace.stage("python.startNative") {
    nativeStart.invoke(null, platform, path)
}
```

- [x] 只在该 JNI 调用返回时打印 `NATIVE_ONLY_RETURNED`，并明确 scope 为 partial-native-init。这不是 Python 正常完整启动或 import PASS。不调用 getInstance、onStart、不改 Python.platform/failed 字段。结束此独立测试进程，不能让别的用例复用部分初始化状态。
- [x] 构建前先把当前本地 probe APK 和设备上 probe 的初始存在性/字节分别备份并哈希，证据只存 `local/pi-resume/python-startup-stages/task3/`。历史旧探针漏备份的 FAIL 不改写；本轮必须完整保全。
- [x] 跑全部 probe JVM 单测和 probe-only assembleDebug；在独立进程分别运行 control、原 tracedPythonStartup、tracedNativeStartOnly 两次。保留每条命令/退出码/PID/最后标记，logcat 直接流式写文件。所有结果标记 TRANSLATED_ARM_DIAGNOSTIC。
- [x] 如果 JNI 阶段仍死亡/终止，只记录具体调用边界（Task 3 实测进程以退出码 1 终止，logcat 记录 `Process <PID> exited cleanly (1)`，无 Fatal signal/SIGSEGV/SIGABRT/backtrace），不推定具体C函数/库缺陷或转译器专有缺陷。若反射查找失败，记录 NoSuchMethod/访问错误，不归为相同native终止。
- [x] 恢复本轮开始的设备probe状态，清理本轮logcat，不动目标APK或全局设置。独立复核包哈希、raw stage日志并完成任务/总体代码审查；不得把这个局部诊断结果用于勾选 T10/T12（T10 保持 IMPLEMENTED_NOT_VERIFIED，T12 保持 IN_PROGRESS）。
