# T12 补充验证：native 返回值契约与两 ABI 构建来源

日期：2026-09-21。状态：**补充验证已执行，T12 仍未完成**。

本轮由 Pi 独立核查。恢复对话后发现另一 Codex 执行窗口仍在修改相同 worktree，因此未修改其生产代码、测试、共享进度或构建输出；只在 `local/pi-resume/20260921-native-contract/` 生成独立复现和产物。本文件为新增证据，不覆盖 `T12.md` 的历史记录。

## 1. 范围与归属

- 仓库 HEAD：`72420bfc602bdad089257d16dbda6f4c9fc39678`，分支 `refactor/android-architecture`，包含既有未提交变更。
- 另一窗口完成 `texture2ddecoder.py` 返回值修正和 `tests/test_texture2ddecoder_contract.py`；本轮验证这些改动，不将其记作本轮实现。
- 未执行 Gradle、设备安装、应用数据清理、游戏目录写入、提交、推送或发布；未推进 T13。
- 下文 host DLL 为 Windows 下编译的实际上游 C++ 解码器。它验证 Python/C 接口行为，**不是 Android ABI、目标 CPython 或真机运行验收**。

## 2. 返回值不匹配：已复现，当前源码独立复验通过

### 2.1 修正前

`app/src/main/python/texture2ddecoder.py` 的两个入口曾用 `if ret != 0` 判失败，而本次新编译 native 的解码函数使用 `1` 表示成功。Crunch 的布尔返回值路径不同，不应改成通用整数判据。

证据链：

1. 本地上游 `bcn.cpp:28–39` 的 `decode_bc1` 正常完成后 `return 1`；`astc.cpp:1141–1152` 相同。
2. 对实际 `jniLibs/x86_64/libtexture2ddecoder.so` 反汇编，`decode_bc1` 返回前是 `movl $0x1, %eax`；两个 ABI 的反汇编分别保存。
3. 用真实 C++ BC1 解码器解一块完整 8 字节 BC1 白色块：原生输出 64 字节 `0xff` 且返回 `1`；旧 Python 绑定却抛 `RuntimeError: decode_bc1 failed with return code 1`。
4. 用完整 ASTC LDR void-extent 红色块测试实际 ASTC 解码器，旧绑定也在成功返回 `1` 时抛异常。
5. 通用解码和 ASTC 的返回 `0` 故障注入在旧绑定下反而不抛异常，产生初始零缓冲结果。

原绑定 SHA-256：`4bcf85749dec4546e86b6694ad9344f3278c8dcbe8ff2512ac3ac2eda45cbd91`。

### 2.2 当前源码

另一窗口将两个入口改为 `if ret != 1`，即只接受明确的成功码。当前已验证绑定 SHA-256：

`1780dc075684f0cedd773c246ae95c01b14a1efb294255a46f3402d0d8187c8d`

本轮独立结果：

| 检查 | 修正前 | 当前源码 |
|---|---|---|
| BC1 原生白块、Python 白块/红块、通用/ASTC 零返回故障注入（共 5 项） | 1 通过、2 失败、2 异常，exit 1 | **5 通过，exit 0** |
| ASTC 实际原生解码：4×4 红块及裁剪至 3×2（共 2 项） | 2 异常，exit 1 | **2 通过，exit 0** |
| 仓库 `test_texture2ddecoder_contract.py`（包含 Crunch、缺库、通道交换、非成功码） | 本轮不重造该窗口的旧测试版本 | **8 通过，exit 0** |

独立检查中的 5 项实际调用 C++（BC1 3 项、ASTC 2 项），另 2 项在 C 边界注入失败返回值；未向原生函数传入截断或非法测试数据。测试前后核对源码哈希一致。

命令（相对仓库根；Python 为宿主解释器）：

```text
python -B local/pi-resume/20260921-native-contract/test_native_return_contract.py --wrapper app/src/main/python/texture2ddecoder.py
python -B local/pi-resume/20260921-native-contract/test_astc_real_contract.py --wrapper app/src/main/python/texture2ddecoder.py
python -B -m unittest discover -s tests -p test_texture2ddecoder_contract.py -v
```

真实 host DLL 的编译命令保留在 `build-host-repro.cmd` 和 `build-host-astc.cmd`，各编译退出码均为 0。

本轮早期仅在独立副本尝试过 `ret == 0` 判失败，5 项检查通过；该候选**未应用**。另一窗口随后选择更严格的 `ret != 1`，本轮以当前源码重新验证，不用早期候选替换它。旧补丁保存为 `native-return-contract.SUPERSEDED.patch`，禁止将其作为待应用补丁。

## 3. 已检查 APK：符号齐全仍不能证明功能可用

在本轮较早的 APK 静态核查时，以下两个既有 Release APK：

- x86_64：`4a1e5a89b63f9b7bab6609c5f8af7e708f6ffae7ef6ae4ee3841c6644676db81`
- arm64-v8a：`c64efd9a7a7af3096bb45f16d8bf8a3c31d121d6db7e451e9158fbd1e6b9ceda`

均包含与 `jniLibs` 相同的 native 字节，20 个 Python 绑定所需 C 导出全部存在；但 `assets/chaquopy/app.imy` 内仍是旧绑定，包含两处 `ret != 0`。

因此，历史 `_lib_present=True` 仅证明加载，不证明真实解码成功。上述结论只属于这两个明确哈希的 APK；另一窗口后续重建的新 APK 必须另行确认 Python 字节并执行真实解码探针，本文件不提前宣布其通过。

## 4. 当前 native 来源链：两 ABI 已本机逐字节重建

### 4.1 输入

- 本地 `local/native/Texture2DDecoder` 的 Git 提交：`3ebc3b758bd6b1a3108b50148f7998ee34f058c6`。本轮未刷新远端，不称其为最新上游。
- 有效本地修改：7 个头文件增加 `extern "C"` 包装，附带部分末行换行变化；补丁完整保存为 `native-source.patch`。
- NDK：`26.3.11579264` / r26d；目标 API 24，沿用已有构建方式，不修改 App 最低系统版本。
- 编译单元：`astc.cpp atc.cpp bcn.cpp crunch.cpp etc.cpp pvrtc.cpp unitycrunch.cpp`。
- 共同旗标：`-shared -fPIC -O2 -std=c++17 -static-libstdc++`。
- x86_64 原命令另含 `-fms-extensions -I. -Wl,-soname,libtexture2ddecoder.so`；arm64 原命令没有这些相同设置。不能把两者描述成完全相同命令。
- 完整源码树的逐路径 SHA-256、补丁、工具链指纹及命令均保存在本轮独占目录。构建前后源树哈希未变化。

### 4.2 重建结果

| ABI | 字节数 | 当前库与成功复建产物 SHA-256 | 比较 |
|---|---:|---|---|
| x86_64 | 563768 | `4c7c806476c6f041ba489478d4d638d8b572199bdc66d87b00e7eb17512a07c5` | **逐字节相同** |
| arm64-v8a | 603248 | `1e6fc051a1ff83835593d53c4b2ba587bd7b2ae2554c59db474297372fdb68d2` | **逐字节相同** |

首次 arm64 复建同样为 603248 字节，但 SHA-256 为 `a8dc1216fd8d446c73aba68e42a4a50574c2ed4beafa6ffe0a1669093477e632`。差异调查发现编译参数将正斜杠路径换成了反斜杠，改变了两个 `crn_decomp.h` 的嵌入文件名。按历史命令恢复正斜杠后得到上表完全相同的结果。失败比较、差异清单与第二次成功结果均保留，不覆盖第一次结果。

这证明**当前两份新 decoder**可由上述本地提交、补丁和工具链复建；不证明旧 decoder、其他 JNI、TPK 或 FMOD 的原始来源，也不证明跨目录/跨系统构建的逐字节确定性。

### 4.3 持久交付仍有缺口

- `arm64-v8a/libtexture2ddecoder.so` **已经被 Git 跟踪**，`.gitignore` 不会取消现有跟踪。
- 新增 x86_64 decoder **未跟踪且被 `.gitignore:30` 的 `*.so` 忽略**；普通提交不会自动携带它。
- 本地源码和本次构建证据位于被忽略的 `local/`。当前已有编译来源证据，但尚未形成仓库内可携带的 native 构建入口/输入协议。
- 不能据此直接将全部 `.so` 解禁或引入完整 CMake/Gradle 重构；应由当前主控确定最小纳管方式，再独立验证干净 checkout。

## 5. 独立审查及裁定

本轮通过官方 Antigravity `pro` 委派只读审查，会话 `1725b3c0-4b71-470e-9166-838ad195ff87`，报告 `independent-review.md`。

- 返回值不匹配：**接受**，本轮源码、机器码及实际 host 解码复现支持；随后另一窗口已修正，本轮重新验收通过。
- x86_64 未跟踪及可携带构建缺口：**接受**。
- 报告称两个 ABI 都未跟踪、二进制无法提交：**纠正**。arm64 已跟踪；忽略规则也不等于 Git 无法显式纳管。
- 报告称构建来源只能猜测：**已被本轮逐字节复建收窄**。当前 decoder 的本机来源链已得到验证，仓库可携带性仍未解决。
- 审查期间另一窗口修改了绑定和测试；报告的旧代码结论不能直接套到当前源码。以本文件记录的源码哈希及独立复跑为准。

## 6. 原始证据索引

目录：`local/pi-resume/20260921-native-contract/`。

- `baseline.json`、`texture2ddecoder.before.py`：旧绑定快照。
- `baseline-tests.log`、`candidate-tests.log`、`current-real-native.log`、`current-portable-tests.log`、`current-acceptance.json`：BC1/故障分支及仓库契约测试。
- `astc-baseline-tests.log`、`astc-current-tests.log`、`astc-acceptance.json`：真实 ASTC 对照。
- `binary-and-apk-audit.json`、`x86_64-bc1-disassembly.txt`、`arm64-v8a-bc1-disassembly.txt`：既有包和机器码核查。
- `native-rebuild-manifest.json`、`android-native-build.log`、`arm64-build-difference.json`、`arm64-exact-paths-result.json`：两 ABI 复建与路径差异。
- `native-source.patch`、`native-upstream-3ebc3b7.tar`、`source-toolchain-artifacts.json`：归档输入和哈希。

归档上游 tar SHA-256：`c31a31ba2464b7a5f9fe77eec76a3620db52f91233843ce2b1f0debe99edfe2a`。
补丁 SHA-256：`dc02d2b6b08ddec63080aee336b0285982493d050f3a7012f807e46064236c47`。

**以上 host 验证阶段仍未覆盖：** 修正绑定后当前 minified Android APK 的真实解码验收、arm64 真机运行、仓库可携带 native 构建，以及 T12 的其他原始来源缺口。T08/T10 门槛不因本补充验证而改变。

## 7. 接管后的后续验证（同日）

确认前执行窗口因额度错误停止后，本窗口继续构建并验证，更新上节的部分未覆盖项：

- 当前 minified x86_64 APK `ae91183912f83f52355b65bb601f0ff40309cb4339a0cf490866c73e19208562` 打入修正后的绑定；设备 base.apk 哈希一致。目标 Python 3.8.18 必需 import、全部 20 个导出及真实 BC1 精确输出分别通过对应探针，7 项受保护文件存在性/字节不变。
- 已新增 `tools/native/texture2ddecoder-source.json`、`texture2ddecoder-c-linkage.patch` 和 [受控构建说明](../native-texture-decoder.md)，并精确解除必需 x86_64 库的忽略。文件可纳管但尚未提交。
- 实际执行文档的离线固定 Git 对象模式并应用仓库补丁，两 ABI 编译及导出检查通过；没有用原先 dirty 源码目录冒充干净输入。相对路径 arm64 新输出未替换现有库，差异口径不变。
- 官方 Antigravity 对来源/纳管/文档及 T10 BOM 修正给出 PASS，报告位于 `local/pi-resume/20260921-release-verification/delivery-review.md`。

完整运行记录见 [T12.md 的接续验收章节](T12.md)，原始目录为 `local/pi-resume/20260921-release-verification/`。**arm64 真机及其他第三方来源缺口仍未闭合，T12 不标完成**。

