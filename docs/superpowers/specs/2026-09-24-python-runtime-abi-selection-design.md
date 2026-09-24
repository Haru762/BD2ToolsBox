# Python Runtime ABI Selection 设计

日期：2026-09-24
状态：已获用户批准，进入实现计划阶段

## 目标

修复 BD2 ToolsBox 在 ABI 转译环境或 universal APK 中启动 Chaquopy Python 时选择错误 bootstrap 资产的问题。运行时必须根据**已安装 APK 实际携带并加载的 native payload**选择 Chaquopy ABI，而不能仅依据 `Build.SUPPORTED_ABIS` 或设备首选 ABI。

## 已确认的问题

当前应用在多个调用点直接执行：

```kotlin
Python.start(AndroidPlatform(context))
```

Chaquopy 的默认选择在 x86_64 主机 + ARM 转译环境中选择了 x86_64 bootstrap，随后 ARM64 Python 加载 x86_64 `zlib.so`，导致：

```text
zlib.so is for EM_X86_64 (62) instead of EM_AARCH64 (183)
Fatal Python error: init_fs_encoding
```

诊断探针已证明：在相同进程条件下，强制选择与实际 ARM64 payload 对应的 `arm64-v8a` bootstrap 后，Python 3.8、zlib、完整依赖集合和 BC1 解码均可继续运行。

## 方案

新增一个小型、可测试的 `PythonRuntime` 启动门面，集中所有生产代码的 Python 初始化：

1. 若 Python 已启动，直接返回；
2. 在进程第一次启动前，解析 `context.applicationInfo.nativeLibraryDir`，将目录 ABI 规范化为：
   - `arm64` / `arm64-v8a` → `arm64-v8a`
   - `x86_64` → `x86_64`
3. 校验匹配 ABI 的 stdlib/bootstrap 资产存在，然后创建 `AndroidPlatform(context)`；
4. 构造函数会重选 ABI，因此在构造完成后覆盖 `AndroidPlatform.ABI`，再调用 `Python.start(platform)`；
5. 启动流程使用进程级同步，避免现有多线程调用点同时初始化 Python；
6. 对无法识别或不支持的目录名明确失败，不回退到设备 ABI 猜测。

所有现有直接启动点改为调用这个门面，避免 `MainViewModel`、`ModRepository` 和未来新增调用点产生不同选择逻辑。

## 测试策略

### 单元测试

覆盖以下行为：

- `arm64` 目录归一化为 `arm64-v8a`；
- `arm64-v8a`、`x86_64` 保持稳定；
- 未知 ABI 目录拒绝启动；
- 启动调用只执行一次；
- 已启动 Python 时不重复启动；
- 失败时不伪造“已启动”状态。

### Release probe

扩展现有 `release-probe`：

- 记录实际 `nativeLibraryDir`、默认 ABI 和最终选择 ABI；
- 在 x86_64 载荷和 ARM64 载荷条件下验证选择一致；
- 保留默认错误选择作为 RED 对照；
- 验证 Python 版本、zlib 路径、13 项依赖、20 个 decoder 导出和 BC1 解码；
- 不把转译环境结果写成原生 ARM64 验收。

### 回归

至少运行：

- 新增 PythonRuntime JVM 测试；
- 现有 `release-probe:testDebugUnitTest`；
- app JVM 回归；
- release-probe 构建；
- 现有 x86_64 生产 Python/native 验证；
- 保留现有 ARM64 真实设备未覆盖状态。

## 非目标

- 不重建或替换 `libastcenc.so`；
- 不修改 `libtexture2ddecoder.so`；
- 不修改 Python 依赖版本或 Chaquopy 版本；
- 不修改安装、快照、账本、日志恢复逻辑；
- 不把 x86_64 + ARM 转译器视为原生 ARM64 设备；
- 不删除现有诊断证据或覆盖 RED 失败记录。

## 风险与护栏

- Chaquopy 的 ABI 选择字段属于其 Android runtime 实现，可能不是稳定公共 API。实现必须将反射/兼容逻辑限制在一个文件，并在 release-probe 中验证字段存在、可写和最终路径。
- ABI 选择只能发生在 `Python.start` 之前；Python 已启动后不得修改全局 ABI 字段。
- 新门面必须保持异常传播，不将启动失败转换成成功状态。
- 不能使用 `Build.SUPPORTED_ABIS` 作为唯一来源，因为转译环境可能同时报告 x86_64 和 arm64-v8a。

## 实现前源码核查

已核查本地固定 Chaquopy 15.0.1 的 `AndroidPlatform.java` 和 `Python.java`：ABI 是 public static String（deprecated/internal），构造函数会覆盖它；`Python.start/isStarted/getInstance` 均使用 Python.class monitor。门面沿用同一 monitor，避免 check-then-start 竞争；使用直接字段访问让 API 变动在编译阶段暴露，兼容风险仅保留在单个适配文件中。失败后不重置 Chaquopy 的 failed 状态、不反射绕过重试保护。
