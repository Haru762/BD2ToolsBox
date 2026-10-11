# APK 优化前后对照（T54）

- 日期：2026-10-11。
- 口径：**ZIP `compress_size`**（每类在 APK 里真正占的字节）；MiB = 1024×1024；
  分类字节 + `containerOverhead` == APK 文件大小（`tools/audit_apk_size.py` 自校验，
  本节两张表的合计都逐字节对得上）。
- 基线：T48 冻结的「本轮重构完成、尚未专项裁剪」的 release
  （`docs/refactor/evidence/apk-before-{arm64,x64}.json`，APK 存
  `local/refactor-artifacts/pre-shrink/`）。

## 1. 总计

| 包 | 优化前 | 优化后 | 变化 |
|---|---:|---:|---:|
| arm64-v8a | 35,146,618 B（33.5184 MiB） | **22,661,146 B（21.6114 MiB）** | **−12,485,472 B（−11.9071 MiB，−35.52%）** |
| x86_64 | 35,048,281 B（33.4246 MiB） | **22,833,231 B（21.7755 MiB）** | **−12,215,050 B（−11.6491 MiB，−34.85%）** |

## 2. 逐项 A/B（**每项单独报告，不合并宣称**）

不同优化作用在**不同类别**上，且彼此基本不重叠（下面每项的差额可相加复算）：

| 项 | 任务 | arm64 差额 | x86_64 差额 | 作用类别 | 依据 |
|---|---|---:|---:|---|---|
| 单 ABI flavor（去掉另一套 Chaquopy 归档） | T49 | −4,914,320 B | −4,744,994 B | chaquopy | [T49.md](evidence/T49.md) |
| 剔除桌面平台 FMOD 二进制 | T50 | −5,703,542 B | −5,707,542 B | chaquopy | [T50.md](evidence/T50.md) |
| native 调试信息剥离（固定 NDK 版本） | T51 | −1,356,120 B | −1,259,984 B | native | [T51.md](evidence/T51.md) |
| R8 规则收窄（删两条按包 keep） | T52 | −104,448 B | −104,448 B | dex | [T52.md](evidence/T52.md) |
| 资源收缩 | T53 | −294,051 B | −294,051 B | androidResources | [T53.md](evidence/T53.md) |
| 容器开销（局部头/中央目录随之变小） | 派生 | −112,976 B | −104,016 B | overhead | — |
| 其它 | — | −15 B | −15 B | other | — |
| **合计** | | **−12,485,472 B** | **−12,215,050 B** | | 与 §1 逐字节相符 |

### 分类明细

| 类别 | arm64 前 | arm64 后 | arm64 Δ | x86_64 前 | x86_64 后 | x86_64 Δ |
|---|---:|---:|---:|---:|---:|---:|
| `chaquopy`（Chaquopy + `app.imy`） | 20,822,801 | 10,204,939 | **−10,617,862** | 20,822,801 | 10,370,265 | **−10,452,536** |
| `native`（`lib/<abi>/*.so`） | 9,837,184 | 8,481,064 | **−1,356,120** | 9,725,760 | 8,465,776 | **−1,259,984** |
| `dex` | 3,526,052 | 3,421,604 | **−104,448** | 3,526,052 | 3,421,604 | **−104,448** |
| `androidResources` | 460,053 | 166,002 | **−294,051** | 460,053 | 166,002 | **−294,051** |
| `other` | 189,738 | 189,723 | −15 | 189,738 | 189,723 | −15 |
| `containerOverhead` | 310,790 | 197,814 | −112,976 | 323,877 | 219,861 | −104,016 |
| **APK** | 35,146,618 | 22,661,146 | −12,485,472 | 35,048,281 | 22,833,231 | −12,215,050 |

## 3. 新依赖成本与冗余清理分开

- **冗余清理收益**：上表五项（T49–T53）。合计 **−11.9071 MiB（arm64）**。
- **新依赖成本**：本轮**没有**为减包引入任何新依赖；`kotlinx-serialization`
  （T35）与 `AboutLibraries`（T37）是本轮之前就引入的架构/许可改动，其成本
  **不计入**减包收益，也没有拿来抵消。它们在 APK 里的存在体现在 `dex` 与
  `androidResources` 的**绝对值**里，不参与上面的差额计算。

## 4. 历史「约 11.809 MiB 候选内容池」不是净节省

计划里提到的「历史约 11.809 MiB」只是**候选内容池**（当时看到的可疑大项之和）。
本轮实测的净节省 **11.9071 MiB（arm64）** 与之数值接近，但**不是同一件事**：
候选池里各项彼此有重叠（例如「另一套 ABI 的 Chaquopy 归档」与「桌面 FMOD 二进制」
都落在 `chaquopy` 类别里，`lib/` 与 IMY 里的 `.so` 也会重复计数），
而本节的每一项都是**实际构建出的 A/B 差额**，并且能逐字节加总回 APK 总差额。
所以：**不把候选池当净节省**，只报实测。

另外两项明确**没有**当作收益：
- **pyc 编译**：本项目目标解释器是 3.8、构建机是 3.11，minor 不一致 → pyc 不生成
  （见 `docs/refactor/python-dependencies.md`）。没有收益可报。
- **压缩/对齐**：16 KB ZIP 对齐与 `extractNativeLibs=false` 是**兼容性**要求
  （T15/T51），不是减包手段；`libastcenc.so` 的 4 KB 链接对齐仍是登记在案的例外。

## 5. 安装占用与基本启动/内存（x86_64，MuMu API 32，实测）

| 指标 | 优化前 | 优化后 |
|---|---:|---:|
| 设备上 `base.apk` 字节 | 35,048,281 | **22,833,231**（−12,215,050） |
| `du -sk`（块占用） | 34,228 KB | **22,300 KB** |
| 冷启动 `am start -W` TotalTime | 367 ms（1 次） | **346 / 347 / 349 ms**（3 次） |
| `TOTAL PSS` | 205,151 KB | **201,918 KB** |
| `TOTAL RSS` | 308,928 KB | **305,676 KB** |
| `logcat -b crash` | — | 空 |

`extractNativeLibs=false`（minSdk 29+ 默认）：native 库不从 APK 解出，所以
安装占用 ≈ APK 大小；实测 `stat` 与 APK 字节一致，`du` 的差额与 APK 差额同量级。
启动/内存是**单机单次抽样**，只用于确认「没有明显退化」，不作为性能结论。

## 6. 功能零损失边界（本轮确认的不变项）

- **支持的 ABI 不变**：仍然只有 `arm64-v8a` 与 `x86_64` 两个包；
  `--expected-abi` 对两包 exit 0、`expectedAbiProblems` 为空。
- **没有删功能**：`app.imy` 只剔除了**桌面平台**的 FMOD 二进制
  （Android 上无加载路径，`pyfmodex` 本就不在 pip 清单）；设备用例确认保留模块
  全部可 import、引擎 smoke 9/9 通过（[T50.md](evidence/T50.md)）。
- **离线能力不变**：许可全文仍在 `res/raw/aboutlibraries.json`
  （Apache-2.0 10,279 字符等，[T53.md](evidence/T53.md)）；攻略正文与 Spine 预览
  的离线策略未改。
- **运行时获取策略不变**：pixi-spine 仍按原策略在首次预览时取回私有目录
  （T47 只改了主题，未改获取）；没有为了减包把核心组件改成首次下载。
- **没有用有明显体验代价的手段**：未做 native 重压缩（如 UPX 之类）、未删 JNI 符号、
  未关代码收缩。

## 7. 可复算

```powershell
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk app/build/outputs/apk/arm64/release/app-arm64-release.apk --expected-abi arm64-v8a --output docs/refactor/evidence/apk-after-arm64.json
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --apk app/build/outputs/apk/x64/release/app-x64-release.apk --expected-abi x86_64 --output docs/refactor/evidence/apk-after-x64.json
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --compare docs/refactor/evidence/apk-before-arm64.json docs/refactor/evidence/apk-after-arm64.json
Invoke-Bd2Python (Join-Path $Root 'tools/audit_apk_size.py') --compare docs/refactor/evidence/apk-before-x64.json docs/refactor/evidence/apk-after-x64.json
```

## 8. 未覆盖

- `Test-Bd2ReleaseSmoke -Case 'all-core-features'` **不存在**（`verification.ps1` 只实现
  `baseline-startup` 与 `snapshot-compatibility`），所以「最终两个包通过**同一套**
  功能/协议 smoke」没有按计划命令跑过；本轮 x86_64 侧跑过 `snapshot-compatibility`
  与 `AndroidPythonStagingTest`/`NativeEngineSmokeTest`/`PlatformBehaviorTest`（debug flavor），
  arm64 侧**未跑**（真机离线）。
- 安装/启动/内存数据只有 x86_64 一台模拟器、单次抽样。
