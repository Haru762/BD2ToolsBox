# T08：把持久化事实绑定到实际提交的同一暂存源

> 执行方式：沿用 executing-plans / TDD；Pi 固定 worky / claude-opus-4-8，实现和审查写集分离，Codex 集成并实跑。

**目标：** converted 安装不再“对 SAF A 校验/摘要、再从可变真实路径 B 拷贝”；每个目标只消费本次独占暂存副本，写前冻结内容 SHA 与全部账本字段。此 slice 不代替实际目标恢复补账和故障重启验收。

## 已查证的现状

- MainViewModel.installOneConverted 优先 resolveRealPath 直接复制，失败后才复制 SAF 到同名 converted_staging/bundle；与先前校验读取不是同一份冻结内容。
- recordConvertedInstalls 在文件写完后才读当前 quality / usedAstc / installedAt；不能用这些事后值重建先前 journal 事实。
- copyTreeFromSaf 对缺失 name 或 null InputStream 可跳过但返回 true；需要显式失败，不能把残缺树当完整暂存。
- ShizukuManager.installConvertedBundle(sourceDir, bundleName) 原样复制本地目录，shell 读不到 app 私有 cacheDir；必须以可用 externalCacheDir 为前提，不用不可读的 fallback 假成功。
- 产物布局是 bundle/hash/__data；JournalRecoveryFacts 支持内容 SHA + 完整 ledger upserts，仍须生产接线。

## 设计边界

1. 在 externalCacheDir 下为每次准备生成随机独占目录，不拿用户 bundle/opId 拼工作区所有权路径，不清理别的尝试或输入目录。bundle/hash 先按单路径段检查。
2. 先把 SAF 源完整复制到独占目录，确认期望 hash/__data 是可读普通文件，再流式求该**实际暂存文件**的字节数与 SHA。捕获 ledger 列表的防御性副本，全部字段在写前冻结。
3. Prepared 对象持有 sourceDir、JournalTarget（带 facts）和明确 cleanup 所有权。close 只清本对象创建的目录；并行同 bundle 互不覆盖，重复 close 安全。符号链接/规范化路径逃逸不得扩展复制或清理边界。
4. prepare 或读流失败没有游戏写入/账本写入；失败也不能删除外部 sentinel/其他准备目录。复制回调 false/null stream/异常一律失败。
5. Python 完整校验如运行，应校验同一暂存 __data；既有 T07 备份门禁仍在实际覆盖前执行。缺 Python 时的兼容策略不在此任务随意改变，但暂存读失败必须阻断。
6. submitter 的写前 JournalTarget 与 commitLedger 使用同一份冻结 records；禁止提交后重新读取 UI 选项、时间或 URI 推断字段。
7. replacement 只调用 Shizuku 对本次暂存 sourceDir 复制，不再回退到另一源路径。提交前若暂存内容变化则拒绝副作用并保留合适的失败/待核对状态。
8. 不保证任意阶段续跑，不重放转换、不叠加安装；实际目标内容审计与原子补账仍是后续必要工作。

## 任务 1：纯 JVM 暂存所有权与内容绑定

建议新增 core/storage/ConvertedInstallStaging.kt，API 封装 prepare / Prepared.verifyUnchanged / close；只依赖 File、流、LedgerRecord 和 JournalTarget，不依赖 Context/Shizuku。

- [x] 先写真实 TemporaryFolder 用例：已复制内容的独立 SHA 与大小、完整 facts 字段；源随后变化不改变暂存；同 bundle 两次准备互不覆盖；一份 close 不动另一份或 sentinel。
- [x] 先写失败用例：非法路径/空或错目标 ledger 在 copy 前拒绝；copy false/抛错/null stream、缺失或目录型 __data、不可写 staging root；原件/sentinel 保全。
- [x] 覆盖暂存同大小篡改/缺失、closed 后调用、ledger 输入列表后续修改。若测试平台不支持 symlink，明确未实跑，不用 skip 冒充覆盖。
- [x] 实跑编译成功的行为 RED，留新 XML 与源码指纹。
- [ ] Pi 只实现新 helper 文件，Codex 不重复写相同范围；集成后定向 GREEN。

## 任务 2：MainViewModel 的实际调用接线

- [x] 创建 operationId 与冻结 ledger records，准备独占暂存；失败汇总为“目标未改动”，跳过此目标。（2026-09-21：`installOneConverted` 经 `ConvertedInstallStaging` + `ConvertedInstallSubmitter`；缺 hashDir/仓储/externalCacheDir 则拒绝覆盖。）
- [x] 在 try/finally 中复用同一准备对象完成备份门禁（仍在 submit 前）、submitter 调用，finally `prepared.close()` 只清自己暂存。
- [x] replaceTarget 从 prepared.sourceDir 复制（先 `verifyUnchanged`）；commitLedger 只 putAll 冻结 records。已去掉 resolveRealPath 直接装入与事后 `recordConvertedInstalls`。
- [x] SAF / 真实路径均走 `StrictDirectoryCopy`（`DocumentFileCopySource` / `FileDirectoryCopySource`）。无名条目或 null stream 整树失败，不再 `copyTreeFromSaf`。
- [x] 批次统计/备份门禁未削弱；T08 仍 IN_PROGRESS（无真实游戏覆盖中断验收）。

## 任务 3：验证与交接

- [x] 定向：BoundConvertedInstall / Submitter / Durability / Staging / StrictDirectoryCopy / FileDirectoryCopySource 已在本机 JVM 实跑（见 T08.md 2026-09-21）。
- [x] 全量 JVM 2026-09-21 `./gradlew :app:testDebugUnitTest` 与 `compileDebugKotlin` 通过。校验已改到暂存 `__data`。
- [x] T08 证据已更新。**T08 仍 IN_PROGRESS**（无真实覆盖中断/杀进程补账）。
- [x] 本计划不新增游戏目录写入授权。

## 2026-09-20 本轮增量记录（尚未记整体完成）

- 追加纯 JVM DirectoryCopySource / StrictDirectoryCopy 边界：null listing 与空目录分开、null stream/读异常/非法名/重名/循环明确失败，现有目标不覆盖。MainViewModel 仅负责 DocumentsContract 适配。
- r12 准备器 RED：16/12/0/0；r13 严格复制 RED：7/4/0/0；r14 严格复制 GREEN：7/0/0/0。
- r15 BoundConvertedInstallTest 集成 RED：4/3/0/0，真实宿主目标文件、journal 与 ledger；Context/Log 是桩。new Repository 明确仅同进程磁盘重读，不冒充进程重启。
- r16 准备器新增目录链接后的 RED：19/14/0/0；Windows 用 NTFS junction，不是 POSIX symlink/Android 设备验收。
- 上述实跑均编译成功且 sourceStable=true；新准备器仍待 Pi 完成交付，不能将上一批302绿灯用于本轮源树。


## 实跑安全回归补记（修复进行中）

- r19：36/1/0/0，compile 完成、sourceStable=true。r18 的 junction 和空目录问题已不失败，但同路径替换目录仍被误删。
- r20：23/2/0/0，新增 copy 回调内替换目录的行为 RED；无测试跳过。
- 最小 Java 探针：当前 D:/java17 Windows 的 BasicFileAttributes.fileKey 在原目录/新目录/retained 都是 null，null == null 不是身份。
- Windows fsutil 只读 File ID 实测：owned 0x0000000000000000000d0000000ae824，retained 0x0000000000000000000d0000000ae81d。
- 不接受用路径、时间戳、相同字节或可被整目录复制的 sentinel 当成 acquired inode。
- 生产默认 NIO identity 不可用时 fail-closed，无 fallback；Windows JVM 的真实复制/链接/替换测试仅使用 test 源集 File ID provider，并单独验证默认 provider 在本机拒绝未知身份。
- Android externalCache 上 identity 是否可用仍未实机验证，不据宿主 provider 冒称可安装。
- 无设备/游戏目录操作，失败中的外部目录均是 JUnit TemporaryFolder 内的隔离 sentinel fixtures。
