# T08 持久化恢复事实实现计划

> **面向 AI 代理的工作者：** 使用 subagent-driven-development 或 executing-plans，逐任务实跑红绿测试。Pi 固定 worky / claude-opus-4-8；Codex 集成、验证。

**目标：** 在副作用发生前，日志能完整保留并严格验证恢复所需的目标内容摘要与账本 upsert；损坏日志不被追加操作覆盖。

**架构：** 为 JournalTarget 添加可空 recoveryFacts；null/缺失仅表示旧格式，不代表可自动恢复。新事实含 formatVersion=1、expectedContentSha256 与完整 ledgerUpserts，严格绑定 bundle/hash/modUris。校验放在纯 JVM helper；Store 在读和写两端复用，所有失败原件不变。本批不接游戏读写、不解除 pending，不代替总计划 T08 的端到端恢复验收。

**技术栈：** Kotlin、Gson、JUnit4、TemporaryFolder、既有 AtomicFileWriter。

## 文件边界

- 新建 app/src/main/java/com/bd2toolsbox/core/storage/JournalRecoveryFacts.kt：DTO 与原始 JSON 严格校验，无 I/O。
- 修改 app/src/main/java/com/bd2toolsbox/core/storage/OperationJournalStore.kt：可选字段、读写调用校验、Unreadable 时拒绝 append。
- 新建 app/src/test/java/com/bd2toolsbox/core/storage/JournalRecoveryFactsTest.kt：真实磁盘 JSON roundtrip、错误输入拒绝与原件保全。
- 修改 app/src/test/java/com/bd2toolsbox/core/storage/OperationRecoveryTest.kt：废弃“坏日志丢弃重开”的旧断言，改为原件不变。

## 数据契约

~~~kotlin
data class JournalRecoveryFacts(
    val formatVersion: Int,
    val expectedContentSha256: String,
    val ledgerUpserts: List<LedgerRecord>
)
// JournalTarget 新字段：val recoveryFacts: JournalRecoveryFacts? = null
internal object JournalRecoveryFactsCodec {
    fun validate(target: com.google.gson.JsonObject): String?
}
~~~

无 recoveryFacts/null 是旧记录；对象存在时：
- formatVersion 必须是 JSON 整数1；SHA为64位小写hex。
- bundleName/hashDir 为非空单路径段，非 . / ..，不含 slash、backslash、冒号或控制字符；hashDir 不能null。
- dataBytes 是非负JSON整数Long；modUris 是非空、无重复、非空白字符串数组。
- ledgerUpserts 为非空数组；modUri、modName、familyKey、snapshotTargetHash、quality 必须为非空白JSON字符串，usedAstc 必须为JSON布尔，installedAt 为非负JSON整数Long。
- upsert URI唯一且与modUris精确同集合；snapshotTargetHash全部等于bundleName。
- 不支持删除delta；当前 converted 记账只upsert，不从URI猜缺失记录。后续若接删除路径须扩展显式事实。

### 任务1：先证伪

- [x] 写磁盘测试：完整新JSON读入，追加复制的stage，再直接检查持久化JSON中的摘要和全部账本字段仍在。
- [x] 用相同有效输入逐一移除字段/改类型/制造重复、错bundle、不匹配URI、非法hash或SHA，断言 read()==Unreadable 且文件字节不变。
- [x] 坏JSON后 append 必须返回错误且原件逐字节不变；合法已有日志的失败append同样不抹证据。

~~~kotlin
val old = file.readBytes()
assertNotNull(store.append(entry))
assertArrayEquals(old, file.readBytes())
~~~

- [x] 实跑 JournalRecoveryFactsTest + OperationRecoveryTest，归档新XML与源树哈希。预期行为红灯；编译失败不计。

**RED 证据：** recovery-facts-red-r6 实跑编译成功，39 tests / 12 failures / 0 errors / 0 skipped，源码指纹一致。补充证据完整性用例：已有空白文件不是 Absent；分数或溢出的顶层版本不能截断为1。新增有效多记录、0/Long.MAX_VALUE/false 和写端非法 DTO 边界测试。

### 任务2：最少生产实现

- [x] Pi 只写 JournalRecoveryFacts.kt；Codex 只写 Store 接线，写集分离。
- [x] Store parse 在 Gson DTO转换前按原始JsonObject校验，防止默认false/0和Gson数字转字符串掩盖缺失。
- [x] Store write 先验证生成payload，再调用AtomicFileWriter；错误不触盘。
- [x] append 对 Unreadable 返回原错误，不把坏日志降级emptyList。
- [x] 对新增事实执行真实 roundtrip 与错误拒绝回归；独立静态复核已返回，未发现本批范围内 P1/P2。全量 r9 先行实跑，复核未提出代码改动。

### 任务3：集成验证与证据

- [x] 用 tools/verification.ps1 中 Invoke-Bd2GradleRaw 实跑 :app:testDebugUnitTest --rerun --console=plain。
- [x] 统计新XML总测试/失败/错误/跳过，核对测试前后源码指纹。
- [x] 构建Debug；核对 core.storage 包级R8保留已覆盖新增DTO（当前不是新增minified运行验收）。
- [x] 更新总计划T08当前结果与剩余：同一暂存源摘要、实际目标观察、原子补账+commit marker、旧路径、故障重启均不因本批成功自动完成。

保留其他既有脏文件；本批不自行提交混杂改动、不发布、不安装、不写游戏目录。


**本批验证（2026-09-20）：** r7 定向45/2/0/0，r8定向45/0/0/0，r9全量302/0/0/0；r11 Debug三包exit0，构建后75份源码与r9一致。r10因调用参数错误未构建，不能计成功。独立事实审查已返回：Pi（worky / claude-opus-4-8）对四文件静态范围未发现 P1/P2；未审生产调用/旧恢复 gate、未执行设备验收，不扩展为整个 T08 通过。详见 T08.md 本批章节。
