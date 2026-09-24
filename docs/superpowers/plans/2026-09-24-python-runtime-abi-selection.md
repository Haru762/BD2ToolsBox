# Python Runtime ABI Selection 实现计划

> **面向 AI 代理的工作者：** 使用 subagent-driven-development 进行独立任务和审查；主实现留在现有 worktree，避免其他工作树的安全修复混入。

**目标：** 生产启动路径按实际 nativeLibraryDir 选择 Chaquopy bootstrap，消除 ARM64 载荷误加载 x86_64 zlib。

**架构：** PythonRuntime.ensureStarted(Context) 是唯一生产启动入口。纯 Kotlin PythonStartup 封装 ABI 规范化和 monitor 下的幂等启动；Android 适配器验证资产、构造平台、覆盖 ABI 后执行完整 Python.start。

**技术栈：** Kotlin 1.9 / Chaquopy 15.0.1 / Android API 29+ / JUnit4 / Gradle 8.9。

## 文件与职责
- 新增 app/src/main/java/com/bd2toolsbox/core/python/PythonStartup.kt：不依赖 Android 的实际 ABI 策略与同步启动。
- 新增 app/src/main/java/com/bd2toolsbox/core/python/PythonRuntime.kt：Android/Chaquopy 适配器。
- 新增 app/src/test/java/com/bd2toolsbox/core/python/PythonStartupTest.kt：规范化、拒绝未知路径、幂等、失败传播与并发验证。
- 修改 CharacterRepository.kt、ModRepository.kt、MainViewModel.kt、PrepackService.kt：六个启动点使用门面，不改外层错误处理和线程要求。
- 新增 release-probe 的生产启动探针：通过真实生产入口初始化，然后复用未改断言的依赖/decoder 检查，不使用测试侧 ABI override。
- docs/refactor/python-runtime-abi-selection.md：命令、测试计数、产物和设备范围证据。

## 任务 1：TDD 实现启动策略与生产适配器
- [x] 先写 JUnit：arm64/arm64-v8a/x86_64，尾斜杠，拒绝未知/空/相似名字。
- [x] 测试已启动时不读路径、不创建平台；启动失败原样传播、下次检查不伪装成功；多线程只能启动一次。
- [x] 运行 `:app:testDebugUnitTest --tests com.bd2toolsbox.core.python.PythonStartupTest` 保存 RED。
- [x] 实现 `PythonStartup(lock, isStarted, startWithAbi).ensureStarted(nativeLibraryDir)`：monitor 内检查状态、精确解析末级目录，再调用 startWithAbi。
- [x] 实现 `PythonRuntime.ensureStarted(context)`：使用 Python.class monitor；匹配 stdlib 和 zlib 资产需能 open/close；构造 AndroidPlatform 后设置 ABI，执行 Python.start。
- [x] 运行同一测试获得 GREEN。

## 任务 2：全部生产调用点接入
- [x] 先用 `rg` 建立 RED 基线，确认 CharacterRepository、ModRepository、PrepackService 和 MainViewModel 的直接启动点。该一次性审计结果只保留在验证日志，不交付源码文本测试。
- [x] 替换六个生产启动点，并将 RestoreBundleDeterminismDeviceTest 和 release-probe 的 fallback 也接入统一入口，保留业务上下文和 IO dispatcher，不改安装逻辑。
- [x] 运行 app JVM 全套，并用 `rg` 核对主代码和设备测试唯一的 `Python.start` / `AndroidPlatform` 位置。

## 任务 3：真实生产入口探针与构建
- [x] 独立测试探针调用 `com.bd2toolsbox.core.python.PythonRuntime.ensureStarted(Context)`，不用诊断 override。
- [x] 验证重复调用返回同一已启动解释器；匹配 payload ABI 的 zlib 路径；复用完整 import 和 decoder 精确断言。
- [x] 如 release 混淆需要，添加只保留单一门面入口的精准规则，不扩大 Chaquopy 的既有规则。
- [x] 执行 `:release-probe:testDebugUnitTest :release-probe:assembleDebug :app:assembleRelease`，对原产物先保全并记录新哈希。
- [ ] 若独立官方 AVD 能启动，在不触碰 MuMu/游戏数据的情况下测试 arm64 转译、x86_64 及 universal；保留/恢复原包，不把转译结果升格为原生 ARM。

## 任务 4：全量验证与复核

> ARM64 生产入口仍未在真实 ARM64 设备上运行；x86_64 split/universal 已完成。
- [x] 运行 tools/run_python_tests.py，保留结构化报告与精确计数；检查没有 skip 被算作 PASS。
- [x] 独立代码审查规格、质量、线程安全和失败路径；已处理 probe 绕过、设备测试绕过和指标措辞问题，并重跑受影响测试。
- [x] git diff --check，证据写明本次未覆盖范围与真实 ARM64 门槛，T10/T12 不虚报完成。
- [x] 只提交本次源文件和文档变更，不混入用户已有探针/计划/diff.txt/patch.py，不 push 或发布。
