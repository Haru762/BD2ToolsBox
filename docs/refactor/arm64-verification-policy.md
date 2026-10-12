# arm64 验证策略（2026-09-26 起生效，用户授权）

## 授权

用户于 2026-09-26 在讨论「有没有办法不用 arm64 真机」后选择「方法一、三」：

- **方法一：** 收窄 arm64 真机门槛，只让涉及 native / Python / 按 ABI 打包的任务保留原生 arm64 验收；
- **方法三：** 用本机静态 ABI 审计补足其余任务的 arm64 覆盖。

这是验收门槛的变化。按 TODO §0.3，在此登记原因与范围；需求本身不变。

## 原因

- 两个 ABI 真正不同的只有 native 代码：Chaquopy/CPython、`libastcenc.so`、`libtexture2ddecoder.so`、bootstrap-native 扩展，以及 APK 里按 ABI 分发的资产。
- Kotlin/dex、Compose UI、数据迁移、Service 生命周期、Hilt 对象图在两个 ABI 上执行的是同一份字节码，在 x86_64 上验证即可代表其行为。
- 唯一可用的 arm64 真机是用户的个人手机（MIUI/HyperOS）。它会拦截 instrumentation 发起的 Activity 启动（T13 §7），而且覆盖正式包名有数据风险，不适合作为每个任务的常规验收设备。

## 新规则

### A. 仍要求原生 arm64 真机的任务（7 个）

| 任务 | 原因 |
|---|---|
| T14 | Chaquopy 15.0.1 → 16.1.0，Python 运行时与 wheel 变化 |
| T18 | Kotlin ↔ Python/JNI 引擎适配层 |
| T49 | 单 ABI flavors：每个 APK 只含本 ABI 的 native / Chaquopy 资产 |
| T50 | 裁剪 Android Python 归档 |
| T51 | 剥离 native 调试信息 |
| T54 | 减包后功能零损失 |
| T57 | 平台 / ABI / 真实 release 最终验收矩阵 |

这些任务可以把 arm64 验证集中到同一次连接里完成，并优先使用隔离包名。在拿到原生 arm64 结果之前，只能标 IMPLEMENTED_NOT_VERIFIED。

### B. 其余任务：x86_64 设备验收 + arm64 静态审计

TODO 里原本写着 `-Abi 'arm64-v8a'` 的设备或 release smoke 命令，对下列任务改为：

1. 在 x86_64（白名单 MuMu 或本机 AVD）上执行原命令，结果必须 PASS；
2. 对同一源码构建的 arm64 release APK 运行静态审计，结果必须 PASS：
   `python tools/audit_native_abi.py --apk <arm64 apk> --abi arm64-v8a --output <evidence>.json`

适用任务：T10、T15、T16、T19、T20、T22、T23、T25、T26、T29、T32、T33、T34、T62、T35、T36、T37、T38、T40–T47、T48、T52、T53、T56、T58、T59、T60、T61。

证据里必须写明「arm64：静态审计 PASS，未在原生 arm64 运行」，不能写成 arm64 PASS。

### C. 不因此放宽的事项

- 任务里其他与 ABI 无关的验收项（功能、数据、UI、平台行为）一律照原标准执行。
- T57 的最终验收仍须在原生 arm64 真机上运行真实 release；发布前至少有一次原生 arm64 全量 smoke。
- 若某个 B 类任务在实施中改动了 native / Python / 打包（例如新增 JNI 或修改 Chaquopy 配置），该任务自动升格为 A 类。
- ARM 转译环境（MuMu Houdini、带 ARM 转译的 x86_64 AVD）的结果只能标为「转译通过」，不能代替 A 类的原生验收。
- 覆盖正式包名 `com.bd2toolsbox` 的 arm64 操作（例如 T10 在正式包名下的快照迁移）不再作为常规要求。若将来在专用设备上补做，需要另行获准。

## 静态审计覆盖面

`tools/audit_native_abi.py`（真实 APK 证伪：`tools/tests/test_audit_native_abi.py`；无需构建的归档回归：`tools/tests/test_audit_native_archives.py`）只读解析 APK，检查：

- `lib/<abi>/` 是否存在，有没有多余的 ABI 目录；
- 每个 `.so` 的 ELF64 / 小端 / `e_machine`（arm64=183、x86_64=62）；
- 必需库（`libpython3.8.so`、`libchaquopy_java.so`、`libastcenc.so`、`libtexture2ddecoder.so`）是否齐全；
- `DT_NEEDED` 能否在 APK 同 ABI 目录或 NDK 系统库中解析；
- texture2ddecoder 的 20 个导出与 `JNI_OnLoad`；
- 以 STORED 方式存放的 `.so` 在 ZIP 中是否 4 KB 对齐；PT_LOAD 是否 16 KB 对齐（默认只警告，`--require-16k` 时判失败）；
- Chaquopy `stdlib-<abi>.imy`、`requirements-common.imy`、`bootstrap.imy` 必须存在、非空且可逐项读取（包括 CRC 校验）；`requirements-<abi>.imy` 也必须存在且为完整 ZIP，但单 ABI flavor 可把依赖全部放进 common，留下合法的空 ABI 占位包。common 与 ABI 归档内每个 `.so` 都必须检查 ELF 架构，不能只检查文件名带 ABI 的归档；`bootstrap-native/<abi>/` 的 `.so` 同样检查架构。

**它不能证明：** JNI 注册成功、dlopen 期链接错误不存在、指令集特性可用、Python import 成功、运行时行为正确。上述内容只能由 A 类任务的原生运行覆盖。

## 当前基线（2026-09-26）

对现有 0.2.16 release（`app/build/outputs/apk/release/`）运行静态审计：

- arm64-v8a 与 x86_64 均 **PASS**，各 7 个 `.so`；texture2ddecoder 的 20 个导出齐全。
- 警告：所有 `.so` 的 PT_LOAD 对齐都是 4 KB（没有一个满足 16 KB）；Chaquopy 的 bootstrap-native 同时含两个 ABI（已知的 T49 议题）。
- 16 KB 页兼容作为 T51 / T57 的检查项，由它们决定是否要求 `--require-16k`。
