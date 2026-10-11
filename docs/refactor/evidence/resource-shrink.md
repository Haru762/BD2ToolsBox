# 资源收缩（T53）

- 日期：2026-10-11。
- 结论：release 开启 `isShrinkResources = true` 后 `androidResources` 从 52 项 /
  460,080 B 降到 **38 项 / 166,002 B**（−294,078 B），APK 两 ABI 各 **−295,428 B**；
  许可证数据、FileProvider XML、图标与 `app_name` 全部保留（机器可判）。

## 1. 开关与管线

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
}
```

- **AGP 8.11.1 没有 8.12 起的「新资源优化管线」**（`android.enableResourceOptimizations`
  之类的开关在 8.12+ 才有意义），所以**不添加**这类属性 —— 对不支持的版本盲加开关
  只会制造「看起来配了」的假配置。`gradle.properties` 未改。
- 资源收缩依赖代码收缩（`isMinifyEnabled = true`），T52 的 R8 配置保持 optimize。

## 2. 实测前后对照（arm64，ZIP 压缩载荷）

| 类别 | 改前（T52） | 改后 | 变化 |
|---|---:|---:|---:|
| `androidResources` 项数 | 52 | **38** | −14 |
| `androidResources` 字节 | 460,080 | **166,002** | **−294,078** |
| `dex` | 3,421,604 | 3,421,604 | 0 |
| `native` | 8,481,064 | 8,481,064 | 0 |
| `chaquopy` | 10,204,939 | 10,204,939 | 0 |
| `other` | 189,723 | 189,723 | 0 |
| **APK** | 22,956,574 | **22,661,146** | **−295,428** |

x86_64 同幅度（23,128,659 → **22,833,231**，−295,428）。

资源文件路径在开启收缩后被混淆（`res/M7.json`、`res/0K.xml` …），所以核对必须按
**资源名/ID** 做，不能按路径。

## 3. 动态资源访问追踪（本项的关键）

AGP 的收缩报告 `app/build/outputs/mapping/arm64Release/resources.txt` 里有一行：

```
android.content.res.Resources#getIdentifier present: true
```

即构建里存在**按名字查表**的调用，收缩器无法静态解析，于是进入「安全模式」
（保守保留）。用 `dexdump -d` 反查两处调用点（类名经 mapping.txt 还原）：

| 调用点 | 查的什么 | 是否我们的资源 |
|---|---|---|
| `androidx.compose.ui.platform.BoundsHelperApi28Impl` | `getIdentifier("navigation_bar_height", "dimen", "android")` | 否（**framework** 资源，不参与 app 资源收缩） |
| **AboutLibraries 运行时** | `getIdentifier("aboutlibraries", "raw", <package>)` | **是** |

**应用自身代码零处 `getIdentifier`**（`rg getIdentifier app/src/main/java/` 无命中）。

### 由此加的精确保留（`app/src/main/res/raw/keep.xml`）

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@raw/aboutlibraries" />
```

- **不是通配符**：只点名一条资源。
- **不用 `tools:shrinkMode="strict"`**：strict 会连带删掉其它只能按名字到达的资源
  （Compose/Material 的若干 id 与 string 走的就是这条路），风险远大于收益。
- 现状说明：**即使不加这条，`raw/aboutlibraries` 今天也不会被删** —— 因为收缩器检测到了
  `getIdentifier` 而停在安全模式。加它是为了不依赖那个启发式（AGP 8.12+ 的新管线会改这套判定）。
  实测加与不加的 APK 字节数相同（22,661,146），差别是**显式**而非**凑巧**。
- AGP 会把 `keep.xml` 自身从 APK 排除。

## 4. 许可证与其它必需资源（机器可判）

`tools/audit_apk_size.py` 新增 `--require-resource-name`（可重复）：在
`resources.arsc` 的字符串池里查找资源名。资源路径虽然被混淆，**资源名必须保留**
（否则 `getIdentifier` 无法工作），所以这个检查是可靠的。

```
python tools/audit_apk_size.py --apk app/build/outputs/apk/arm64/release/app-arm64-release.apk \
  --expected-abi arm64-v8a \
  --require-resource-name raw/aboutlibraries --require-resource-name xml/file_paths \
  --require-resource-name mipmap/ic_launcher --require-resource-name string/app_name
# exit 0
```

`aapt2 dump resources` 交叉确认：

```
resource 0x7f0b0000 raw/aboutlibraries
resource 0x7f0f0000 xml/file_paths
resource 0x7f0a0000 mipmap/ic_launcher
resource 0x7f0a0001 mipmap/ic_launcher_foreground
resource 0x7f0a0002 mipmap/ic_launcher_round
resource 0x7f0c0001 string/app_name
```

### 许可证全文**确实在 APK 里**（更正一条旧结论）

把收缩后 APK 里那份（被混淆成 `res/M7.json` 的）`aboutlibraries.json` 解出来：

```
top keys: ['libraries', 'licenses']
libraries: 138
licenses:  Apache-2.0 (10,279 字符) / BSD-2-Clause (1,266) / BSD-3-Clause (1,459)
           HPND (1,186) / MIT (1,077) / MPL-2.0 (16,726)
```

即 SPDX 全文**内嵌在 raw 资源里**，许可页面不依赖联网。

> **更正**：T37/T38 曾写「生成 JSON 不内嵌许可全文（实测 `licenseContent` 不存在），
> 全文由库内置 SPDX 映射运行时解析」。实测字段名是 `licenses[<spdxId>].content`
> （不是 `licenseContent`），内容存在且完整。已同步更正
> `docs/refactor/license-inventory.md` §3 与 `docs/refactor/evidence/T38.md`。
> 这条更正也让 `keep.xml` 的必要性变得具体：**全文就在这份资源里**，删掉它许可页就空了。

自定义条目（`FMOD-LICENSE` / `SPINE-LICENSE`）在这份 JSON 里**没有** `content`，
属 `license-inventory.md` §4 已登记的缺口（本地无全文）。

## 5. 未使用资源 / 图标

- 项目自身的 `res/` 只有 7 个手写文件（`mipmap-anydpi-v26/ic_launcher*.xml`、
  `mipmap-hdpi/ic_launcher_foreground.xml`、`values/{colors,strings,themes}.xml`、
  `xml/file_paths.xml`），全部有引用。
- 被删的 14 项来自依赖库（Material/Compose 的未用 drawable/string/style），
  以收缩报告为准，**不手工删依赖库资源**。
- `material-icons-extended` 的**声明体积不等于 APK 体积**：R8 只保留实际引用到的
  `Icons.*` 生成类，未被引用的图标类连同其矢量资源一起被收缩掉。本轮未对图标做
  额外处理，也没有为了让报告好看而删依赖。
- Python（`app.imy`）与 JS（`assets/spine-viewer/`）是 **assets**，AGP 的资源收缩
  **不负责**它们（Python 的裁剪是 T50，单独做的）。

## 6. 未覆盖

- `Test-Bd2ReleaseSmoke -Case 'resources-and-licenses'` **不存在**
  （`verification.ps1` 只支持 `baseline-startup` 与 `snapshot-compatibility`）。
  因此「release 上许可证页面真的能打开、深色主题/空状态/图标正常、无
  `Resources.NotFoundException`」**没有在 minified release 上跑过**；
  本轮做的是 APK 级资源存在性 + 内容可解析 + `--expected-abi` 审计。
- 资源收缩只作用于 release；debug 的设备用例（如 `LicensesScreenTest`）**不能**
  证明收缩后的行为，未把它当作本项证据。
- 未在 arm64 真机跑。
