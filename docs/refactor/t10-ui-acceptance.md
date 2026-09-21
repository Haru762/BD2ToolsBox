# T10 完整业务列表冷启动验收（x86_64）

本流程验证真实 minified App 的“扫描 → 保存 → 杀进程 → 恢复列表”，不是 Store 夹具往返或主界面 chrome 检查。2026-09-21 已按用户授权执行；arm64 由用户明确留到后续统一测试。

## 测试前提与保全

- 使用 `environment.local.json` 的白名单设备和基线测试签名，核对实际 native ABI、设备身份和 APK SHA-256。
- 主机保全 `installed_mods.json`、`install_journal.json` 的存在性/字节；保全原快照、mod_cache、角色表、索引、app_settings.xml 及权限元数据。不得 `pm clear` 或卸载目标 App。
- App 不在安装/转换中；游戏停止。只创建本次独占测试源，不借用或修改已有 MOD 素材。
- 使用真实 Android 目录选择器授予测试目录的持久 URI 权限，不通过改系统权限数据库伪造授权。

## 本次实际输入

测试目录为 `Documents/T10_E2E_20260921`，有两个子目录 `T10_Alpha` 和 `T10_Beta`，各含一张有效 2×2 PNG。它们由生产扫描器识别为 PC_SOURCE，因为文件名不对应游戏资源而显示在“未识别 (2)”区域。

这是刻意无害的**列表持久化**夹具，不代表可安装 MOD，不执行转换/装入。验收要求两条完整预期名称各出现一次，不能用“找到任意一个名称”或空列表替代。

## 正向与反向对照

1. 在当前 Release 中，通过“mod 文件夹 → 打开 mod 文件夹”及 DocumentsUI 选择独占目录，确认授权。
2. 等待生产扫描完成；切换“全部”，展开“未识别 (2)”，保存两个名称同屏的截图/XML。
3. 读取 App 实际 `files/mod_list_snapshot.json`，断言规范顶层键 `version`/`savedAt`/`mods`/`sourceDirs`、`version=1`、两条完整条目及真实授权的 sourceDirs。保存原字节和 SHA。
4. 停止 App，确认旧 PID 消失；**将测试源目录在同一个 Documents 父目录下改名保留**，使原 SAF documentId 无法读到内容，但持久权限仍在。
5. 通过 `am start -W -n com.bd2toolsbox/.MainActivity` 启动，要求 COLD、新 PID。不要从 instrumentation 内启动 Compose Activity。
6. 若启动出现游戏索引确认框，只点击“跳过”，绝不启动全游戏扫描。切换“全部”、展开“未识别 (2)”；必须同时显示两条预期名称。
7. 核对新 PID 的日志存在生产 `IncompleteScanException` 对应的“扫描未完成，保留上次列表”；确认源仍不可读且快照字节未变。由此排除重新扫描伪造列表。
8. **阴性对照**：再次停止 App，把当前快照改名隔离，源保持不可读。再次 COLD 启动并进入“全部”，应显示“还没有任何 mod”，两个预期名称均不能出现；失败扫描也不能重建快照。
9. 把同一快照原样改名恢复，第三次 COLD 启动。源仍不可读时，两条记录必须再次出现，快照字节相同。
10. 全程核对账本/日志不变。本次原账本和日志都不存在，结束时也没有凭空创建；非空账本合并/保全另由 T08 与 Store 测试覆盖。

## 恢复现场

- 把测试源恢复原目录名。
- 通过生产“管理文件夹”只移除本次测试目录；这会释放该 URI 的持久授权，不删除其素材。
- 核对前后 URI grant 集合只少了本次测试 URI；既有授权不能减少。
- 停止 App 后按备份恢复原快照、解析缓存和配置等字节与权限。对原先不存在的账本/日志，确认仍不存在；不写空 JSON 来冒充恢复。
- 测试目录及原始证据保留，不递归删除共享存储。恢复检查失败时不提交“验收成功”结论。

## 本次结果

- APK SHA-256：`1b3627c34fbe3c5e12d2c31160c6934a00dec15fcc4f3da989f11291e7dc5955`，真实 minified x86_64 Release。
- 保存 PID `21570` → 正向恢复 PID `22371` → 无快照对照 PID `22619` → 放回快照 PID `22796`，均为独立冷启动。
- 正向两轮均完整显示 `T10_Alpha` / `T10_Beta`；阴性对照为空；各轮源目录不可读、重扫失败，快照不是由重扫补出的。
- canonical 快照 SHA-256：`97be0cf2b83e9f80e137219a0c34ce867c0b169ee0372ecc31c94fa5a2524045`。
- 原账本/日志存在性、原配置/缓存/快照字节及既有 URI 权限当时收尾未做好，现已在事后补上核对并确认已恢复；真实游戏文件未被 T10 修改。

原始证据在 `local/pi-resume/authorized-e2e-20260921-190937/`：`t10-ui-acceptance.json`、`t10-production-snapshot.json`、`t10-cold-start.json`、三轮 UI XML/PNG/对应 PID 日志、`t10-negative-start.json`、`t10-rollback.json` 及授权集合前后记录。

**口径：** 仅此完整流程可以记录 x86_64 `uiColdStartVerified=true`；既有 `snapshot-compatibility` case 的 Store/chrome 结果仍保持 false。arm64 未执行，不得据此勾选其验收完成。
