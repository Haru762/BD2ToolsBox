# 数据契约（T60）

- 日期：2026-10-12。
- 目的：把**磁盘格式**与**迁移规则**写成一份可核对的清单。
  这些是「用户升级 App 之后老数据还读不读得回来」的全部依据。

## 0. 总原则

1. **磁盘键名来自 `@SerialName` 的字符串常量**（T35 起全部改用 kotlinx.serialization）。
   类名、字段名会被 R8 改名，**字符串常量不会** —— 所以混淆包里的读写与明文包一致。
2. **不用反射字段名做磁盘格式**（T09 的教训：`BundleBackupRepository$BackupMeta` 曾按字段名
   读写，R8 把字段改成 `a/b/c` 后老 sidecar 静默失效）。需要显式键的地方就显式写常量。
3. **旧格式只读、不猜**：读不出来就**明确报损坏**（`Corrupt`），不静默补默认值、
   不清账本、不把「读不到」当成「用户没装过」。
4. **单写者**：同一目录只有一个写者（账本由 `LedgerSubmissionCoordinator.forDir` 的
   进程级锁保证），Hilt `@Singleton` 再消除「多实例各持一份缓存」的分叉。

## 1. 文件清单

| 文件 | 位置 | 所有者 | 作用 |
|---|---|---|---|
| `installed_mods.json` | `filesDir` | `InstalledLedgerStore` / `InstalledModRepository` | 装入账本 |
| `install_journal.json` | `filesDir` | `OperationJournalStore` | 操作日志（崩溃恢复依据） |
| `mod_list_snapshot.json` | `filesDir` | `ModSnapshotStore` | 冷启动快照（列表首屏） |
| `mod_cache.json` | `filesDir` | `ModRepository` | 扫描元数据缓存 |
| `local_bundle_index.json` | `filesDir` | `BundleNameResolver` | 本地 bundle 索引 |
| `bundle_aliases.json` | `filesDir` | `BundleNameResolver` | bundle 别名表 |
| `characters.json` | `filesDir` | `BundleNameResolver` | 角色表 |
| `redeem_history.json` | `filesDir` | `Bd2RedeemRepository` | 兑换成功记录 |
| `auto_redeem_done.json` | `filesDir` | `Bd2RedeemRepository` | 已自动兑换过的码 |
| `app_settings.xml` | `shared_prefs` | `SettingsStore` | 设置 |
| `guides/` | `externalCacheDir`（退回 `cacheDir`） | `GuideCacheStore` | 攻略缓存（树/正文/缩略图/阵容/表情表） |
| `backups/<bundle>/<hash>/__data.game` | `filesDir` | `BundleBackupRepository` | 原版备份本体 |
| `backups/<bundle>/<hash>/__data.game.meta.json` | 同上 | 同上 | 备份 sidecar（来源与可信判定） |

> 游戏目录里的 `Shared/<bundle>/<hash>/__data` **不是**本 App 的数据，是本 App 的**写入目标**；
> 它的提交契约见 §4。

## 2. 账本 `installed_mods.json`

- **v1（本类写出的）**：`{"schemaVersion":1,"records":{<modUri>:{...}}}`
  - 顶层键：`schemaVersion` / `records`（常量 `SCHEMA_VERSION_KEY` / `RECORDS_KEY`）；
  - 每条记录的键名逐字来自 `StoredInstalledMod` 的 `@SerialName`：
    `modUri` / `modName` / `familyKey` / `snapshotTargetHash` / `quality` /
    `usedAstc` / `installedAt`；
  - **主键必须等于记录里的 `modUri`**，不一致即整次判损坏。
- **v0（历史格式）**：裸 `{<modUri>:{...}}`，**没有** `schemaVersion`。仍可读。
- **读法**：逐条解码（不是整表一次）—— 这样错误信息能指出**是哪一条**坏了。
  缺必需字段/类型不符 → `Corrupt`；未知字段忽略；可选字段缺失用默认；空文件 → `Empty`。
- **写保护**：默认值字段也写出（与旧 Gson 行为一致：`usedAstc=false` / `installedAt=0` 不省略）。

## 3. 操作日志 `install_journal.json`

- 阶段顺序即语义：
  `PLANNED → STAGED → TARGET_REPLACING → TARGET_REPLACED → LEDGER_COMMITTED`
  （磁盘值是 `planned` / `staged` / `target_replacing` / `target_replaced` / `ledger_committed`）。
- **恢复判定**（`OperationRecovery`，只读、不重放）：
  | 落到的阶段 | 处置 |
  |---|---|
  | 只到 `target_replacing` | `Interrupted` —— 游戏文件可能没换、也可能换了一半 |
  | 到 `target_replaced` 但没有 `ledger_committed` | `NeedsReconciliation` —— 文件已换、账本可能没记 |
  | 到 `ledger_committed` | `Committed` —— 事实完整，只是日志没清 |
  | 读不出来/自相矛盾 | `Unknown` —— 不做任何自动动作 |
- **清理路径必须经 `isCleanupPathAllowed` 校验归属**，不用日志里的任意字符串路径去删东西。
- 界面把四态翻成用户可读结论（`RecoveryBanner`），**`Committed` 不打扰用户**。

## 4. 快照 `mod_list_snapshot.json`

- `SnapshotV1`：`version` / `savedAt` / `mods` / `sourceDirs`，每个字段带
  `@SerialName("<与字段名相同的字面量>")`。
- **`SnapshotV1` 的类名与这四个字段名是硬契约**：`Test-Bd2ReleaseSmoke -Case
  'snapshot-compatibility'` 直接读 `mapping.txt` 断言它们**未被改名**。
  因此 `proguard-rules.pro` 里对它是 `-keep`（不是 `-keepclassmembers`）。
- 条目字段：`name` / `character` / `costume` / `type` / `isEnabled` / `uri` /
  `targetHashedName` / `isDirectory` / `resolutionState` / `targetHash` /
  `resolvedFamilyKey` / `errorReason` / `installState` / `kind` / `convertedHashDir` /
  `convertedDataSize` / `defect` / `outdatedCurrentHash`。
- `LegacyObfuscatedSnapshot` 专门读 0.2.16 那种**被 R8 改名成 `a/b/c`** 的老快照。
- **损坏/未知版本 → 明确重新加载**，不制造「用户没有安装过 MOD」这个事实。

## 5. 备份 sidecar `__data.game.meta.json`

- **两侧都用显式 JSON 键**（`KEY_SCHEMA_VERSION` 那组常量），**不依赖反射字段名**。
- `catalogVerified` 由 `BackupProvenance` 推导 —— 「凭什么说这份字节可信」；
  逐来源规则见 `BackupProvenance` 的注释。
- 删除备份时**连 sidecar 一起清**（不留孤儿元数据，否则后续判定会误认）。
- 清空备份（T44）：**逐 bundle 删除 + 重新统计**，删失败不把用量报成 0；
  确认后按**指纹复核目标集合**，确认期间新产生的备份不删。

## 6. 设置 `shared_prefs/app_settings.xml`

键名（`SettingsRepository` 的常量，全部是字面量，与 R8 无关）：

```
use_astc  backup_originals  dynamic_color  selected_quality  app_theme
theme_mode  onboarding_done  wallpaper_scrim  wallpaper_uri
mod_source_dirs  mod_source_dir_uri（旧单目录键，迁移用）
```

- `theme_mode`：`LIGHT` / `DARK` / `SYSTEM`（按**枚举名**存；未知/缺失回退 `LIGHT`）。
- `app_theme`：配色预设名（未知/空回退默认预设）。
- `wallpaper_scrim`：`0f..1f`，损坏值回退默认。
- `mod_source_dirs`：换行分隔（URI 规范要求换行必须转义，所以它绝不会出现在值里）；
  `mod_source_dir_uri` 是旧版单目录键，迁移时读一次。

## 7. 攻略缓存 `guides/`

| 子路径 | 内容 |
|---|---|
| `tree.json` | 分类树 + 最近更新 + 映射 dict + 图标表 |
| `articles/<id>.html` | 正文（含渲染器版本戳与内容版本戳） |
| `thumbs/<id>.img` | 缩略图 |
| `battles/<key>.html` | 阵容卡（`<key>` = `v<版本>_<id>`） |
| `emoji.json` | 表情表（TTL 24h） |

- 写入一律 `.part` 原子写（先写 `<name>.part` 再改名）；**`.part` 不计入占用统计**。
- 正文缓存的复用条件是**渲染器版本戳 + 内容版本戳都对上**；改渲染逻辑时同步递增版本戳，
  老缓存自动失效重拉。
- 缓存目录在 `externalCacheDir`（占空间大、不占内部配额），退回 `cacheDir`。

## 8. 游戏文件的提交契约（`__data`）

- **同目录暂存 + 校验 + 原子替换**：`AtomicFileWriter` 在目标**同目录**建 `.part`，
  写完校验（字节数、空源判定）再 `rename` 进目标。
- **权限**：已存在的目标保持原权限；新建目标先照抄**同目录兄弟文件**的 mode，
  没有兄弟才退回父目录的 rw 位（屏蔽执行位）。**不是**硬编码一个值。
  - 实测限制：**API 29 的外部卷不兑现 `setPosixFilePermissions`**（设 0750 读回 0775），
    因此该契约在 API 29 上无法保证 —— 见 `evidence/device-api29.md`。
- **不是事务**：目录树拷贝时每个文件各自是一次独立提交，第 k 个失败时前 k-1 个已经落盘；
  调用方必须按目标分别上报成功与失败。

## 9. 迁移规则

| 从 | 到 | 规则 |
|---|---|---|
| 账本 v0（裸表） | v1 | 读得出就迁，读不出就**明确报损坏**，不猜 |
| 0.2.16 被改名的快照（`a/b/c`） | `SnapshotV1` | `LegacyObfuscatedSnapshot` 读；无歧义才迁，否则**显式失效可重建** |
| 旧单目录键 `mod_source_dir_uri` | `mod_source_dirs` | 读一次并合并；列表变化只往「多」的方向改 |
| Gson 时代的 JSON | kotlinx | 键名逐字保留（`@SerialName`），旧文件直接可读 |

**不做的**：不静默补默认值、不清账本或配置来「让读取得成功」、
不因为字段缺失就把用户数据当成不存在。

## 10. 复验

```powershell
$Root = 'D:/bd2 mod/worktrees/bdroid-android-refactor'
. (Join-Path $Root 'tools/verification.ps1') -ProjectRoot $Root
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.JsonMigrationTest'
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.InstalledLedgerStoreTest'
Test-Bd2Unit -TestClass 'com.bd2toolsbox.core.storage.SettingsMigrationTest'
Test-Bd2ReleaseSmoke -Case 'json-migration' -Abi 'x86_64'
Test-Bd2ReleaseSmoke -Case 'snapshot-compatibility' -Abi 'x86_64'
```
