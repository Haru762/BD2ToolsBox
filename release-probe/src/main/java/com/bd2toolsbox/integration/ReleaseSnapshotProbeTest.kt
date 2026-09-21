package com.bd2toolsbox.integration

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest

/**
 * 真实 minified Store 的跨进程快照契约探针（T10）。
 *
 * 本类自足运行在 :release-probe 里，不依赖 :app。通过 targetContext.classLoader
 * 反射调用真实 release 包的 ModSnapshotStore(File)/load/capture/commit。分三次独立
 * am instrument 调用：prepareSnapshot / restoreSnapshot / cleanupSnapshot，runner 依次
 * prepare -> force-stop -> restore -> finally cleanup（prepare 前也 force-stop）。
 *
 * 这只证明真实 minified Store 跨进程读写快照契约成立，**不等价**于 UI 冷启动列表完整验收。
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSnapshotProbeTest {

    private companion object {
        const val TAG = "BD2_SNAPSHOT_PROBE"
        const val STORE_CLASS = "com.bd2toolsbox.core.storage.ModSnapshotStore"
        const val SNAPSHOT_FILENAME = "mod_list_snapshot.json"
        const val EXPECTED_ENTRIES = 5

        // 冻结 fixture 的两个源目录（脱敏合成值），load/commit 都要按它收口。
        val SOURCES = listOf(
            "content://com.example.documents/tree/primary%3Asample-mods-alpha",
            "content://com.example.documents/tree/primary%3Asample-mods-beta"
        )

        // 目标真实 filesDir 下的持久化文件：仅做哈希/存在性保护，探针绝不写它们。
        // 既含通常存在的文件，也含约定但可能缺失的文件（缺失也须监控）。
        val PROTECTED_FILES = listOf(
            "installed_mods.json",       // InstalledModRepository FILENAME（账本）
            "install_journal.json",      // InstalledModRepository JOURNAL_FILENAME（日志）
            "mod_cache.json",            // ModRepository / CharacterRepository（元数据缓存）
            "local_bundle_index.json",   // BundleNameResolver / ModRepository（bundle 索引）
            "bundle_aliases.json",       // BundleNameResolver（别名表）
            "characters.json",           // CharacterRepository / BundleNameResolver（角色表）
            "redeem_history.json",       // Bd2RedeemRepository
            "auto_redeem_done.json",     // Bd2RedeemRepository
            "mod_list_snapshot.json"     // 真实 filesDir 的快照：探针只在 cacheDir 干活，绝不碰它
        )

        // 真实配置：dataDir/shared_prefs/app_settings.xml（MainViewModel 使用），单独只读监控。
        const val PREFS_REL = "shared_prefs/app_settings.xml"

        // 严格字母数字连字符校验：runId 来自 am instrument 的 bd2RunId 参数（runner 允许至 80）。
        val RUN_ID_RE = Regex("^[A-Za-z0-9-]{1,80}$")
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** 从 instrumentation 参数取并校验 bd2RunId。 */
    private fun runId(): String {
        val raw = InstrumentationRegistry.getArguments().getString("bd2RunId").orEmpty()
        assertTrue("bd2RunId 必须是严格字母数字连字符（实际=\"$raw\"）", RUN_ID_RE.matches(raw))
        return raw
    }

    /**
     * 唯一工作目录 targetContext.cacheDir/bd2-release-snapshot-<id>。
     * canonical 校验：解析后必须仍在 cacheDir 下、名字必须是约定前缀，
     * 否则宁可硬失败也不写/删任何别的目录。
     */
    private fun runDir(context: Context, id: String): File {
        val cache = context.cacheDir.canonicalFile
        val name = "bd2-release-snapshot-$id"
        val dir = File(cache, name).canonicalFile
        assertEquals("run 目录必须直接位于 cacheDir 下", cache, dir.parentFile?.canonicalFile)
        assertEquals("run 目录名必须是约定前缀", name, dir.name)
        return dir
    }

    // ---------------------------------------------------------------- 反射桥

    /** 拿目标真实 minified 包的 ModSnapshotStore Class（只用 targetContext.classLoader）。 */
    private fun storeClass(context: Context): Class<*> =
        Class.forName(STORE_CLASS, false, context.classLoader)

    /** new ModSnapshotStore(File)。 */
    private fun newStore(context: Context, file: File): Any {
        val ctor = storeClass(context).getConstructor(File::class.java)
        return ctor.newInstance(file)
    }

    private fun method(target: Any, name: String, vararg types: Class<*>): Method =
        target.javaClass.getMethod(name, *types)

    /** 真实 Store.load(List)，返回 SnapshotLoad（sealed，反射态）。 */
    private fun invokeLoad(store: Any, sources: List<String>): Any =
        requireNotNull(method(store, "load", List::class.java).invoke(store, sources))

    /** 真实 Store.capture(List, List) -> PendingWrite。 */
    private fun invokeCapture(store: Any, entries: Any, sources: List<String>): Any =
        requireNotNull(method(store, "capture", List::class.java, List::class.java).invoke(store, entries, sources))

    /** 真实 Store.commit(PendingWrite, List) -> SnapshotCommit。 */
    private fun invokeCommit(store: Any, pending: Any, sources: List<String>): Any {
        val pendingType = Class.forName(
            "com.bd2toolsbox.core.storage.PendingWrite", false, store.javaClass.classLoader
        )
        return requireNotNull(method(store, "commit", pendingType, List::class.java).invoke(store, pending, sources))
    }

    /** 读任意对象的 Kotlin getter（getXxx）。 */
    private fun getter(obj: Any, prop: String): Any? {
        val g = if (prop == "isEnabled" || prop == "isDirectory") prop
            else "get" + prop.replaceFirstChar { it.uppercase() }
        return obj.javaClass.getMethod(g).invoke(obj)
    }

    private fun simpleClassName(obj: Any): String = obj.javaClass.simpleName

    /**
     * 锁定 PID 含义：标准 instrumentation（targetPackage=com.bd2toolsbox）跑在目标进程里，
     * 不是探针自己的进程。每个 phase 都断言当前 host 就是目标包：
     *   * Application.getProcessName()（API 28+）== com.bd2toolsbox；
     *   * Process.myPid() 就是执行反射 Store 的当前 host。
     * 不在 phase 结束后用 pidof 查验：am instrument 可能已退出目标进程。
     */
    private fun assertHostIsTarget() {
        val proc = android.app.Application.getProcessName()
        assertEquals("探针反射必须跑在目标包进程里（instrumentation host）", "com.bd2toolsbox", proc)
        assertTrue("myPid 必须是正数", Process.myPid() > 0)
        val app = instrumentation.targetContext.applicationInfo
        assertEquals("目标 applicationId", "com.bd2toolsbox", app.packageName)
        assertEquals("目标不得是 debuggable 构建", 0,
            app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = sha256(file.readBytes())

    /**
     * 目标 filesDir 下每个受保护文件的指纹：存在则 sha256，缺失则 "ABSENT"。
     * 只读不写。缺失文件也登记，用于 restore 监控“本不存在的文件被凭空写出”。
     */
    private fun fileFingerprint(file: File): String {
        if (!file.exists()) return "ABSENT"
        assertTrue("受保护路径存在但不是普通文件：${file.path}", file.isFile)
        return sha256(file)
    }

    private fun fingerprint(context: Context): Map<String, String> {
        val files = context.filesDir
        val out = LinkedHashMap<String, String>()
        for (n in PROTECTED_FILES) {
            val f = File(files, n)
            out[n] = fileFingerprint(f)
        }
        // 配置：dataDir/shared_prefs/app_settings.xml，同样只读哈希/存在性。
        val prefs = File(context.dataDir, PREFS_REL)
        out[PREFS_REL] = fileFingerprint(prefs)
        return out
    }

    private fun marker(phase: String, id: String, entries: Int) {
        // marker 必须回到 am instrument 的原始输出：AndroidJUnitRunner **不**把 stdout
        // （println）带回 am instrument（实测：marker 只进 logcat，instrumentation 原始
        // 输出里一个标记都没有，runner 报 Missing snapshot prepare marker）。
        // 改走 sendStatus 的 REPORT_KEY_STREAMRESULT，status 码用 **2**（非终态
        // progress）而不是 0：0 是 JUnit 单个测试通过的码，拿它发 marker 会
        // 把一次探针 phase 冒充成额外的 pass。标记文本与原 println 完全一致，只加换行。
        val bundle = Bundle()
        bundle.putString(
            Instrumentation.REPORT_KEY_STREAMRESULT,
            "$TAG phase=$phase runId=$id pid=${Process.myPid()} entries=$entries\n"
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    // ---------------------------------------------------------------- 期望值

    // 期望值由平台 JSON 直接读取冻结原件，不经生产 Store 或 Gson 生成。
    // 明确列出 DTO 的全部 18 个字段；缺失字段按契约的 null/false/0 默认值核对。
    private val stringFields = listOf(
        "name", "character", "costume", "type", "uri", "targetHashedName",
        "resolutionState", "targetHash", "resolvedFamilyKey", "errorReason",
        "installState", "kind", "convertedHashDir", "defect", "outdatedCurrentHash"
    )

    private fun verifyLoadedFive(load: Any): List<*> {
        assertEquals("load 结果必须是 Loaded", "Loaded", simpleClassName(load))
        val entries = getter(load, "entries") as List<*>
        val expected = org.json.JSONObject(String(fixtureBytes(), Charsets.UTF_8)).getJSONArray("b")
        assertEquals("冻结夹具条数", EXPECTED_ENTRIES, expected.length())
        assertEquals("还原条数", EXPECTED_ENTRIES, entries.size)
        assertEquals("不得静默丢条目", 0L, (getter(load, "droppedEntries") as Number).toLong())
        entries.forEachIndexed { i, entry ->
            val actual = requireNotNull(entry)
            val source = expected.getJSONObject(i)
            for (field in stringFields) {
                val value = if (source.isNull(field)) null else source.getString(field)
                assertEquals("第 $i 条 $field", value, getter(actual, field))
            }
            for (field in listOf("isEnabled", "isDirectory")) {
                assertEquals("第 $i 条 $field", source.optBoolean(field, false), getter(actual, field))
            }
            assertEquals("第 $i 条 convertedDataSize", source.optLong("convertedDataSize", 0L),
                (getter(actual, "convertedDataSize") as Number).toLong())
        }
        return entries
    }

    // ---------------------------------------------------------------- 跨进程状态

    // 跨进程状态写在 run 目录的 probe-state.json：记 prepare 的 PID、写后 sha、指纹。
    // 用平台 JSONObject 只为传递探针自己的状态；生产快照解析一定由 target loader 的 Store 执行。
    private fun stateFile(dir: File): File = File(dir, "probe-state.json")

    private fun writeState(
        dir: File, pid: Int, canonicalSha: String, fp: Map<String, String>
    ) {
        val root = org.json.JSONObject()
        root.put("pid", pid)
        root.put("canonicalSha", canonicalSha)
        val fpObj = org.json.JSONObject()
        for ((k, v) in fp) fpObj.put(k, v)
        root.put("fingerprint", fpObj)
        stateFile(dir).writeText(root.toString())
    }

    private fun readState(dir: File): org.json.JSONObject {
        val f = stateFile(dir)
        assertTrue("restore 必须能读到 prepare 写下的状态：${f.path}", f.isFile)
        return org.json.JSONObject(f.readText())
    }

    private fun fingerprintFromState(state: org.json.JSONObject): Map<String, String> {
        val obj = state.getJSONObject("fingerprint")
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys()) out[k] = obj.getString(k)
        return out
    }

    private fun fixtureBytes(): ByteArray {
        val bytes = instrumentation.context.assets.open("legacy/mod-snapshot-release.json")
            .use { it.readBytes() }
        assertEquals("探针必须使用冻结原件",
            "cd468d7b1f0ba92c801df3408d64a9e4a3271e8c00d23d09bbb5bb450c1b57be", sha256(bytes))
        return bytes
    }

    // ---------------------------------------------------------------- phase 1: prepare

    /**
     * prepare：读冻结 fixture -> 真实 Store.load 得 5 条并校字段 -> 真实 capture+commit
     * 写 canonicalJSON -> 记 PID、写后 sha、核心业务文件指纹。全程只写 run 目录。
     */
    @Test
    fun prepareSnapshot() {
        assertHostIsTarget()
        val context = instrumentation.targetContext
        val id = runId()
        val dir = runDir(context, id)
        assertTrue("新的 runId 不得覆盖已有目录", !dir.exists())
        assertTrue("run 目录必须独占创建", dir.mkdir())

        // 0. 任何 Store 调用前先采真实 filesDir 业务指纹并早期写下，防止漏掉 prepare 阶段的误改。
        val baseline = fingerprint(context)
        writeState(dir, Process.myPid(), "PREPARE_INCOMPLETE", baseline)

        // 1. 写冻结 fixture（旧 release 格式 a/b/c）到 run 目录的快照文件。
        val snapshot = File(dir, SNAPSHOT_FILENAME)
        snapshot.writeBytes(fixtureBytes())

        // 2. 真实 minified Store.load -> 5 条，逐条校字段。
        val store = newStore(context, snapshot)
        val entries = verifyLoadedFive(invokeLoad(store, SOURCES))

        // 3. 真实 capture + commit，写 canonicalJSON（新格式）。
        val pending = invokeCapture(store, entries, SOURCES)
        val commit = invokeCommit(store, pending, SOURCES)
        assertEquals("commit 必须 Written，实际=${simpleClassName(commit)}", "Written", simpleClassName(commit))

        // canonicalJSON 结构校验（平台 JSONObject 仅供检查，不取代生产解析）。
        val text = snapshot.readText()
        assertTrue("新文件必须带 version", text.contains("\"version\""))
        assertTrue("新文件顶层不许再出现旧键 \"a\":", !text.contains("\"a\":"))
        val obj = org.json.JSONObject(text)
        assertEquals("canonicalJSON version 必须为 1", 1, obj.getInt("version"))
        assertEquals("canonicalJSON mods 必须 5 条", EXPECTED_ENTRIES, obj.getJSONArray("mods").length())

        // 4. commit 后真实业务指纹必须与调用前一致：Store 全程只动 cacheDir 的快照。
        val afterCommit = fingerprint(context)
        assertEquals("受保护文件集必须一致", baseline.keys, afterCommit.keys)
        for (k in baseline.keys) {
            assertEquals("prepare 期间受保护文件 $k 指纹不得改变（缺失也须仍缺失）", baseline[k], afterCommit[k])
        }

        // 5. 记 PID、写后 sha、核心业务文件指纹（用调用前的 baseline 保留原态）。
        writeState(dir, Process.myPid(), sha256(snapshot), baseline)

        marker("prepare", id, EXPECTED_ENTRIES)
    }

    // ---------------------------------------------------------------- phase 2: restore

    /**
     * restore（新进程）：核 PID 与 prepare 不同 -> 真实 Store 重新 load canonicalJSON
     * 验证列表全部字段、sources、总数 -> 比对指纹（缺失文件也须监控）。
     */
    @Test
    fun restoreSnapshot() {
        assertHostIsTarget()
        val context = instrumentation.targetContext
        val id = runId()
        val dir = runDir(context, id)
        assertTrue("restore 必须能看到 prepare 的 run 目录", dir.isDirectory)

        val state = readState(dir)

        // 1. 跨进程：PID 必须与 prepare 不同（runner 已 force-stop 过目标进程）。
        val preparePid = state.getInt("pid")
        assertTrue("restore 必须在新进程（PID 必须与 prepare 不同，prepare=$preparePid）", Process.myPid() != preparePid)

        // 2. 真实 Store 重新 load canonicalJSON，验证全部字段/总数。
        val snapshot = File(dir, SNAPSHOT_FILENAME)
        assertTrue("canonicalJSON 必须已由 prepare 写入", snapshot.isFile)
        // canonicalJSON 文件本身跨进程未被改动：state.canonicalSha 必须等于当前文件 sha。
        assertEquals("canonicalJSON 文件 sha 必须与 prepare 写后记录一致",
            state.getString("canonicalSha"), sha256(snapshot))
        val store = newStore(context, snapshot)
        val entries = verifyLoadedFive(invokeLoad(store, SOURCES))

        // 3. sources 校对：canonicalJSON 写回的 sourceDirs 必须就是两个源。
        assertEquals("load 不得改写 canonicalJSON", state.getString("canonicalSha"), sha256(snapshot))
        val obj = org.json.JSONObject(snapshot.readText())
        val srcArr = obj.getJSONArray("sourceDirs")
        assertEquals("canonicalJSON sourceDirs 必须 2 个", SOURCES.size, srcArr.length())
        for (i in SOURCES.indices) assertEquals("sourceDirs[$i]", SOURCES[i], srcArr.getString(i))
        assertEquals("还原总数必须仍为 5", EXPECTED_ENTRIES, entries.size)

        // 4. 比对指纹：真实 filesDir 下核心业务文件不得被探针改动（缺失也须仍缺失）。
        val before = fingerprintFromState(state)
        val after = fingerprint(context)
        assertEquals("受保护文件集必须一致", before.keys, after.keys)
        for (k in before.keys) {
            assertEquals("受保护文件 $k 的指纹必须跨进程不变（缺失也须仍缺失）", before[k], after[k])
        }

        marker("restore", id, entries.size)
    }

    // ---------------------------------------------------------------- phase 3: cleanup

    /**
     * cleanup：只删自己的 run 目录。安全可重入：prepare 尚未创建目录时也不报错，
     * entries 可为 0。cleanup 前如状态文件存在，顺便确认真实 filesDir 核心文件指纹未被动。
     */
    @Test
    fun cleanupSnapshot() {
        assertHostIsTarget()
        val context = instrumentation.targetContext
        val id = runId()
        val dir = runDir(context, id)

        try {
            if (dir.isDirectory) {
                val sf = stateFile(dir)
                if (sf.isFile) {
                    // 状态存在：确认受保护文件指纹仍与 prepare 一致（只断言，绝不自动修复业务文件）。
                    val before = fingerprintFromState(org.json.JSONObject(sf.readText()))
                    val after = fingerprint(context)
                    for (k in before.keys) {
                        assertEquals("cleanup 前受保护文件 $k 指纹必须未被动", before[k], after[k])
                    }
                }
            }
        } finally {
            // 目录中只应有快照、状态和原子写暂存件；不递归，不跟随链接。
            // 先验证全部直接子路径，再删除，任何异常子目录/越界链接都硬失败。
            if (dir.isDirectory) {
                val children = requireNotNull(dir.listFiles()) { "无法枚举探针工作目录" }
                for (child in children) {
                    assertEquals("清理路径必须仍在本次 run 目录内", dir, child.canonicalFile.parentFile)
                    assertTrue("探针目录不得含子目录或非普通文件", child.isFile)
                }
                for (child in children) assertTrue("无法清理探针文件：${child.name}", child.delete())
                assertTrue("无法清理探针目录", dir.delete())
            }
        }
        assertTrue("cleanup 后 run 目录必须不存在", !dir.exists())

        marker("cleanup", id, 0)
    }
}
