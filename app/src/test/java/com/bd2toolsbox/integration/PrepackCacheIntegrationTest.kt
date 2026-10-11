package com.bd2toolsbox.integration

import android.content.Context
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.feature.preview.PrepackCommitPolicy
import com.bd2toolsbox.feature.preview.PrepackCommitPolicy.Decision
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「预解包 → 缓存」落盘链路的集成测试。
 *
 * **覆盖的是什么**：`PrepackService.run()` 在解包返回后做的三选一判定
 * （合并 / 记负缓存 / 丢弃）以及它的落盘结果。判定已抽到 [PrepackCommitPolicy]，
 * 这里跑的是**生产实现**，断言的是**真实产生的缓存文件**：`<root>/<bundle>/<hash>_<size>/`
 * 下的 `gen`、`.srcsize`、`.nopreview`，以及仓储自己据此给出的
 * [PreviewCacheRepository.isValid] / `isKnownNoPreview` / `count()`。
 *
 * **fake 只在边界上**：
 *  - 文件访问边界 —— `Context` 用 mockk 桩掉 `getExternalFilesDir(null)` 指向
 *    `TemporaryFolder` 临时目录，喂给**真实** `PreviewCacheRepository`（JVM 单测没有
 *    Robolectric，`android.*` 只有 stub；`ContextWrapper` 是 Android 类，在 JVM 侧构造会撞
 *    `Stub!`，所以这里用 mockk 而不是照抄 androidTest 里的写法）；
 *  - Python 解包边界 —— 解包由 [prepack] 直接把产物文件名写进目录代替，不启动解释器。
 * 没有时间依赖，所以不需要注入时钟。
 *
 * **不覆盖**：SAF/`contentResolver` 拷贝、Chaquopy 调用、通知与进度上报，这些仍在
 * Android Service 里，本轮没有设备外可跑的接缝。`PreviewCacheSizeSlotTest` /
 * `PreviewCacheNegativeTest`（androidTest）已覆盖仓储自身的分槽与负缓存语义，
 * 此处不重复。
 *
 * 原文件是 6 条与生产代码无关的假断言（断言 180 < 800 这类写死常量之间的关系），
 * 永远通过、不构成任何覆盖，已整体替换。
 */
class PrepackCacheIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cache: PreviewCacheRepository

    @Before
    fun setUp() {
        val externalFilesDir = temp.newFolder("external")
        val context = mockk<Context>()
        every { context.getExternalFilesDir(isNull()) } returns externalFilesDir
        cache = PreviewCacheRepository(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ----------------------------------------------------------------- 测试夹具

    private companion object {
        /**
         * bundleName 是**单个路径段**（生产里就是 `targetHash`，见
         * `MainViewModel.startPrepack()`），不能写成带 `/` 的路径 ——
         * `PreviewCacheRepository.count()` 按 `root/<bundle>/<slot>` 两层统计，
         * 多段名会让它多数一层。
         */
        const val BUNDLE = "bundle_illust_a"
        const val HASH = "7a3f9c21"
        const val SRC_SIZE = 4096L

        /** 仓储内部的负缓存标记文件名。断言它就是在断言落盘契约本身。 */
        const val NO_PREVIEW_FILE = ".nopreview"
    }

    /**
     * 按 `PrepackService.run()` 里与缓存有关的**同一步序**走一遍：
     * `prepareDir` → 产物落进该目录 → `decide` → `apply`。
     */
    private fun prepack(
        bundleName: String = BUNDLE,
        hashDir: String = HASH,
        srcSize: Long = SRC_SIZE,
        unpackOk: Boolean = true,
        payload: List<String>,
        afterUnpack: (File) -> Unit = {}
    ): Decision {
        val outDir = cache.prepareDir(bundleName, hashDir, srcSize)
        if (unpackOk) {
            payload.forEach { File(outDir, it).writeText("payload:$it") }
        }
        afterUnpack(outDir)
        val fileNames = outDir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        val decision = PrepackCommitPolicy.decide(unpackOk, srcSize, fileNames)
        PrepackCommitPolicy.apply(cache, bundleName, hashDir, srcSize, decision)
        return decision
    }

    private fun slot(bundleName: String = BUNDLE, hashDir: String = HASH, srcSize: Long = SRC_SIZE) =
        cache.entryDir(bundleName, hashDir, srcSize)

    // ----------------------------------------------------------------- 用例

    @Test
    fun `骨架与图集齐全时合并进缓存并写下真实戳文件`() {
        val decision = prepack(payload = listOf("char_a.skel", "char_a.atlas"))

        assertEquals(Decision.COMMIT, decision)
        assertTrue(cache.isValid(BUNDLE, HASH, SRC_SIZE), "合并后的槽必须自证有效")

        val dir = slot()
        assertTrue(File(dir, "gen").isFile, "换代戳必须落盘，否则升级后旧缓存会被当成有效")
        assertEquals(SRC_SIZE.toString(), File(dir, ".srcsize").readText().trim())
        assertFalse(File(dir, NO_PREVIEW_FILE).exists(), "有效缓存不应带负缓存标记")
        assertFalse(cache.isKnownNoPreview(BUNDLE, HASH, SRC_SIZE))
        assertEquals(1, cache.count())
    }

    @Test
    fun `只有骨架没有图集时不合并但记下负缓存`() {
        // 历史缺陷回归：提交条件曾经只看 skel。那样这类产物会被 commit，
        // 但 isValid 要求 skel+atlas，于是「已合并」却永远判无效 —— 每轮预解包
        // 都从头重解一遍（真机表现为进度卡在列表开头那二十几个包）。
        val decision = prepack(payload = listOf("char_a.skel"))

        assertEquals(Decision.MARK_NO_PREVIEW, decision)
        assertFalse(cache.isValid(BUNDLE, HASH, SRC_SIZE), "缺图集就不该被当成有效预览")
        assertTrue(
            cache.isKnownNoPreview(BUNDLE, HASH, SRC_SIZE),
            "必须记负缓存，否则下一轮预解包还会重复解它"
        )
        assertTrue(File(slot(), NO_PREVIEW_FILE).isFile)
        assertFalse(File(slot(), "gen").exists(), "负缓存不是有效缓存，不能留换代戳")
    }

    @Test
    fun `产物完全没有预览载荷时也记负缓存`() {
        val decision = prepack(payload = listOf("char_a.png", "char_a.resS"))

        assertEquals(Decision.MARK_NO_PREVIEW, decision)
        assertFalse(cache.isValid(BUNDLE, HASH, SRC_SIZE))
        assertTrue(cache.isKnownNoPreview(BUNDLE, HASH, SRC_SIZE))
    }

    @Test
    fun `解包失败时清空目录且不留负缓存`() {
        val decision = prepack(unpackOk = false, payload = emptyList())

        assertEquals(Decision.DISCARD, decision)
        assertFalse(cache.isValid(BUNDLE, HASH, SRC_SIZE))
        assertFalse(
            cache.isKnownNoPreview(BUNDLE, HASH, SRC_SIZE),
            "失败可能只是磁盘满或进程被杀，记了负缓存就再没有重试机会"
        )
        assertFalse(File(slot(), NO_PREVIEW_FILE).exists())
        assertEquals(0, cache.count(), "失败项不应在缓存里留下目录")
    }

    @Test
    fun `解包失败会清掉同一槽里上一轮的残留`() {
        prepack(payload = listOf("char_a.skel", "char_a.atlas"))
        assertTrue(cache.isValid(BUNDLE, HASH, SRC_SIZE))

        val decision = prepack(unpackOk = false, payload = emptyList())

        assertEquals(Decision.DISCARD, decision)
        assertFalse(cache.isValid(BUNDLE, HASH, SRC_SIZE), "半成品不能冒充上一轮的有效缓存")
        assertFalse(File(slot(), "char_a.atlas").exists())
    }

    @Test
    fun `拿不到源大小时不合并，按不可分槽处理`() {
        // size 是缓存路径的一部分（<hashDir>_<size>）：拿不到就没法把各变体分开，
        // 合并会让它们互相覆盖，所以宁可不缓存。
        val decision = prepack(srcSize = 0L, payload = listOf("char_a.skel", "char_a.atlas"))

        assertEquals(Decision.MARK_NO_PREVIEW, decision)
        // 无 size 时不带 `_<size>` 后缀，直接落在 hashDir 那一层
        assertTrue(File(cache.entryDir(BUNDLE, HASH, 0L), NO_PREVIEW_FILE).isFile)
        assertFalse(cache.isValid(BUNDLE, HASH, 0L))
    }

    @Test
    fun `同一产物再次预解包不叠加目录`() {
        prepack(payload = listOf("char_a.skel", "char_a.atlas"))
        val again = prepack(payload = listOf("char_a.skel", "char_a.atlas"))

        assertEquals(Decision.COMMIT, again)
        assertEquals(1, cache.count(), "prepareDir 必须先清空再写，不能越攒越多")
        assertTrue(cache.isValid(BUNDLE, HASH, SRC_SIZE))
    }

    @Test
    fun `换过内容的同槽产物不把上一轮文件留在新结果里`() {
        // 覆盖竞态回归（变异实验 M3 发现）：`prepareDir` 若不清空旧槽，上一轮的
        // `.atlas` 会留在目录里。而 [PreviewCacheRepository.isValid] 只要求「目录里
        // 存在**某个** skel 与**某个** atlas」，于是「新一轮的 skel + 上一轮的 atlas」
        // 照样被判有效 —— 用户看到的是两份产物拼起来的错位预览，且文件名对不上，
        // 排查时根本看不出是哪一轮的残留。
        //
        // 断言的是**槽里文件名的全集**，不是「存在/不存在某一个」：只有全等才能
        // 保证新产物独占这个槽。
        prepack(payload = listOf("char_old.skel", "char_old.atlas"))
        assertEquals(1, cache.count())

        prepack(payload = listOf("char_new.skel", "char_new.atlas"))

        val names = slot().listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()
        assertEquals(
            listOf(".srcsize", "char_new.atlas", "char_new.skel", "gen"),
            names,
            "新产物必须独占这个槽：上一轮的文件不能留下"
        )
    }

    @Test
    fun `缓存键里的源大小被改动后立即失效`() {
        prepack(payload = listOf("char_a.skel", "char_a.atlas"))
        val dir = slot()
        assertTrue(cache.isValid(BUNDLE, HASH, SRC_SIZE))

        // 人为破坏缓存键：把 .srcsize 改成另一个值（等价于槽与戳对不上）
        File(dir, ".srcsize").writeText((SRC_SIZE + 1).toString())

        assertFalse(
            cache.isValid(BUNDLE, HASH, SRC_SIZE),
            "戳与槽不一致时必须判无效，否则换过内容的产物会命中旧缓存"
        )
        assertFalse(
            cache.isValid(BUNDLE, HASH, SRC_SIZE + 1),
            "改后的键指向另一个槽，原槽里那份产物不能被认领"
        )
    }

    @Test
    fun `多产物连续预解包后缓存内容与判定一一对应`() {
        val first = prepack(
            bundleName = "bundle_keep", hashDir = "aaaa1111", srcSize = 1024L,
            payload = listOf("a.skel", "a.atlas")
        )
        val second = prepack(
            bundleName = "bundle_plain", hashDir = "bbbb2222", srcSize = 2048L,
            payload = listOf("b.json")
        )
        val third = prepack(
            bundleName = "bundle_fail", hashDir = "cccc3333", srcSize = 3072L,
            unpackOk = false, payload = emptyList()
        )

        assertEquals(Decision.COMMIT, first)
        assertEquals(Decision.MARK_NO_PREVIEW, second)
        assertEquals(Decision.DISCARD, third)

        assertTrue(cache.isValid("bundle_keep", "aaaa1111", 1024L))
        assertTrue(cache.isKnownNoPreview("bundle_plain", "bbbb2222", 2048L))
        assertFalse(cache.isKnownNoPreview("bundle_fail", "cccc3333", 3072L))
        assertEquals(2, cache.count(), "成功项与负缓存各占一个槽，失败项不留目录")
        assertTrue(cache.totalBytes() > 0L, "缓存里应当有真实落盘的字节")
    }
}
