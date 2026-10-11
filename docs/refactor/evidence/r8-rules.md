# R8 规则精修（T52）

- 日期：2026-10-11。
- 结论：删掉两条**按包 keep**（`data.model.**`、`core.storage.**`），把「保留的类」从
  2559 个降到 344 个（`com.bd2toolsbox` 口径），DEX 少 104 KB、APK 少 96 KB；
  同时用 minified release 的跨进程探针**实测**快照契约仍然成立。

## 1. 改前的规则与问题

`app/proguard-rules.pro` 里除 Chaquopy 与 release-probe 两条必要规则外，还有：

```proguard
-keep class com.bd2toolsbox.data.model.** { *; }
-keep class com.bd2toolsbox.core.storage.** { *; }
```

两条的理由都写在注释里：**「Gson 按字段名读写，混淆后字段名一变，老账本/老日志就读不回来」**。

但 T35 已经把全部 JSON 迁移到 kotlinx.serialization：

- 磁盘/网络键名来自 `@SerialName` 的**字符串常量**（`StoredInstalledMod.kt:17-23` 等）；
- 读写由编译器生成的 `$$serializer` 直接引用，**不经过反射字段名**。

所以「按包 keep 住所有 DTO」这条防线的理由**已经不存在**，而代价是实打实的：
`-printseeds` 显示 2559 个 `com.bd2toolsbox` 条目里，**2491 个（97%）来自这两条**
（`data.model` 959、`core.storage` 1532）—— 正是 T52 明令禁止的「保留所有业务类」。

## 2. 逐条复核结果

| 原规则 | 处置 | 依据 |
|---|---|---|
| `-keep class com.chaquo.python.** { *; }`、`…python.runtime.**` | **删除** | Chaquopy 16.1.0 自己接入 `build/python/proguard-rules.pro`（含 `-keep class com.chaquo.python.** { * ; }`）与 chaquopy_java AAR 的 consumer rules。T49 已核对：删掉后 `configuration.txt` 里该规则仍在（section 头写着 `…\app\build\python\proguard-rules.pro`）。 |
| `-keep class …PythonRuntime { ensureStarted(Context); }` | 保留 | release-probe 跨包 `Class.forName` + `getMethod`；覆盖 `baseline-startup`。 |
| `-keep class …ShizukuFileService { *; }` | 保留（不收窄） | Shizuku 按 manifest 组件名绑定、经 AIDL 调成员。T19 的**真实 Shizuku 授权/断连至今未在真机验证**，属「未知依赖宁可记录而非猜着删」。 |
| `-keep class com.bd2toolsbox.data.model.** { *; }` | **删除** | T35 后键名是 `@SerialName` 常量；`$$serializer` 静态引用。实测：DTO 被改名（`ModCacheInfo -> S1.B`）而 DEX 里 `schemaVersion`/`savedAt`/`installedAt`/`bundle_aliases` 等键名常量仍在。 |
| `-keep class com.bd2toolsbox.core.storage.** { *; }` | **删除** | 同上。 |
| `-keepclassmembers …SnapshotV1 { <fields>; }` | **升级为 `-keep`** | `Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility'` 直接读 mapping.txt 断言「SnapshotV1 未被改名 + 4 个字段未被改名」（T10 的闸）。**实测发现**：只删包级 keep 会让该 case 报 `SnapshotV1 class is renamed in mapping.txt`，所以类名在这里是**载重**的。 |
| `-keepclassmembers …LegacyObfuscatedSnapshot { <fields>; }` | 保留 | 无闸检查其类名，只需钉字段。 |

### 新增（实测需要）

删掉包级 keep 后，`snapshot-compatibility` 先报
`ClassNotFoundException: com.bd2toolsbox.core.storage.ModSnapshotStore` ——
release-probe 是**跨包反射**，R8 看不到。因此按「只钉探针真的用到的名字与成员」补一条：

```proguard
-keep class com.bd2toolsbox.core.storage.ModSnapshotStore {
    public <init>(java.io.File);
    public *** load(java.util.List);
    public *** capture(java.util.List, java.util.List);
    public *** commit(com.bd2toolsbox.core.storage.PendingWrite, java.util.List);
}
-keep class com.bd2toolsbox.core.storage.PendingWrite { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotLoad { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotLoad$* { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotCommit { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotCommit$* { *; }
-keep class com.bd2toolsbox.core.storage.SnapshotEntry { *; }
```

**不是恢复整包 keep**：只有探针实际取用的 5 个类型名 + 4 个 Store 成员。

## 3. 前后对照

| 指标 | 改前 | 改后 |
|---|---:|---:|
| `-printseeds` 总条目 | 3723 | **1508** |
| 其中 `com.bd2toolsbox` | 2559 | **344** |
| 其中 `data.model` | 959 | **0** |
| 其中 `core.storage` | 1532 | **89**（全部是上面新加的探针契约面 + SnapshotV1） |
| DEX（arm64，ZIP 压缩字节） | 3,525,608 | **3,421,604**（−104,004 B） |
| APK（arm64） | 23,054,878 | **22,956,574**（−98,304 B） |
| APK（x86_64） | 23,226,963 | **23,128,659**（−98,304 B） |
| native / androidResources | 8,481,064 / — | 不变 |

改后 `com.bd2toolsbox` 的 344 个 seeds 全部是可解释的动态入口：
Hilt `*_GeneratedInjector`、manifest 组件（`MainActivity`/`Bd2Application`/各 Service）、
`ShizukuFileService`、`PythonRuntime`、以及上面那组快照契约面。

## 4. 运行时验证（minified release，实测）

- `Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility' -Abi 'x86_64'`
  → **ReleaseSmoke [snapshot-compatibility/x86_64] 127.0.0.1:16384 -> PASS**，exit 0。
  该 case 在真实 minified release 上做：跨进程 `ModSnapshotStore` 的
  `load`/`capture`/`commit` 契约、冻结夹具 5 条逐字段比对、mapping.txt 的
  SnapshotV1 保名闸、以及 UI 冷启动。
- 两个 APK 的 `--expected-abi` 审计仍 **exit 0 / findings 0 / abiProblems 0**。
- **应用 runtime 中无 Gson**：T35/T36 已把生产代码清零并移除依赖，本轮未重新引入；
  本轮改动只涉及 keep 规则，不改依赖。

## 5. 归档

- mapping / configuration 按 **版本 + ABI + 最终 APK 哈希** 归档到
  `local/refactor-artifacts/r8-mapping/0.2.16/{arm64-v8a,x86_64}/`（忽略、不随 clean 清理，
  不打进 APK）。
- `docs/refactor/artifact-manifest.json` 新增 `t52R8Mapping`，逐变体记录
  `apkSha256` / `mappingSha256` / `configurationSha256` 与 seeds/usage 行数。
- R8 证据开关：设置环境变量 `BD2_R8_EVIDENCE=<file.pro>` 时额外吃入该规则文件
  （里面写 `-printseeds`/`-printusage`/`-whyareyoukeeping`），默认不参与正式产物。

## 6. 未覆盖

- `Test-Bd2ReleaseSmoke -Case 'full-r8-contract'` **不存在**（`verification.ps1` 只实现
  `baseline-startup` 与 `snapshot-compatibility`）。因此计划里的
  「minified release 冷启动 / Hilt 对象图 / 旧数据 / Python 回调 / 扫描安装 /
  WebView 预览 / 许可证」**没有**一条按计划命令跑过；本轮实际覆盖的是
  `snapshot-compatibility`（含 UI 冷启动 + 快照契约 + mapping 闸）。
- `-whyareyoukeeping` 的输出没有随 AGP 转发到构建日志，本轮用的是 `-printseeds`
  与 `-printusage` 的对照。
- 未在 arm64 真机跑（离线）。
