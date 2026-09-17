package com.bd2toolsbox.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.fileaccess.AtomicFileWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 游戏文件提交语义在**真设备**上的验收（T07）。
 *
 * ## 为什么这些用例能跑、且不需要 Shizuku 授权
 *
 * `ShizukuFileService` 对 Shizuku 与 `Context` **零运行时依赖** —— 构造参数
 * `context` 在整个类体里一次都没被使用（`T07.md` §2.2 的复算）。所以这里直接
 * `ShizukuFileService().copyFile(...)` / `.copyDirectory(...)` 驱动**真实实现**，
 * 跳过 Binder 与授权。
 *
 * ## 为什么落在 `getExternalFilesDir(null)` 而不是游戏目录
 *
 * `/storage/emulated/0/Android/data/com.bd2toolsbox/files/` 与游戏目录
 * （`…/com.neowizgames.game.browndust2/…`）在**同一个 `/storage/emulated/0`
 * FUSE 卷上**，拿到的是同一套 rename / fsync / 权限语义；但它属于 app 自己，
 * **不需要 shell UID 就能写**。每个用例再开一个 `UUID.randomUUID()` 隔离子目录，
 * 用完删掉。
 *
 * **绝不碰** `ShizukuManager` 里那三个生产常量路径
 * （`GAME_UNITY_CACHE_PATH` / `DOWNLOAD_SHARED_PATH` / `GAME_SHARED_PATH`）——
 * 真实游戏目录是用户的资产，不是实验场。
 *
 * ## 覆盖不到的那一半（如实登记，别读成全覆盖）
 *
 * 这里证明的是 **FUSE 上的提交语义**。**shell UID 的权限行为覆盖不到**：
 * app UID ≠ shell UID，游戏目录 app 根本无权写，所以「Shizuku 服务以 shell 身份
 * 往游戏目录写」这条链路本轮**没有真机验证**。详见 `T07.md` §7.2。
 */
@RunWith(AndroidJUnit4::class)
class GameFileCommitTest {

    private lateinit var root: File

    private val service = ShizukuFileService()

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val external = ctx.getExternalFilesDir(null)
            ?: throw AssertionError("getExternalFilesDir(null) 为 null —— 外部存储不可用，本用例的前提不成立")
        root = File(external, "t07-commit-${UUID.randomUUID()}").apply { mkdirs() }
        assertTrue("隔离目录必须建得出来：${root.absolutePath}", root.isDirectory)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 1. 成功提交：得到完整新内容
    // ------------------------------------------------------------------

    /**
     * 真实 FUSE 上的一次提交：目标从旧内容变成**完整**的新内容。
     *
     * 目标路径是 `__data` 这个名字 —— 与游戏目录里的真实文件名一致，
     * 顺带覆盖「目标已存在且要被替换」这条路径。
     */
    @Test
    fun copyFileCommitsWholeNewContentOnRealFuse() {
        val src = writeSource("source.bin", PAYLOAD)
        val dst = File(root, "__data").apply { writeText("original") }

        assertTrue("copyFile 应当成功", service.copyFile(src.absolutePath, dst.absolutePath))

        assertTrue("落盘内容必须逐字节一致", PAYLOAD.contentEquals(dst.readBytes()))
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(root))
    }

    /** 目录复制：每个文件各自一次提交，全部成功时目标得到完整的树。 */
    @Test
    fun copyDirectoryCommitsEveryFileOnRealFuse() {
        val srcDir = File(root, "src").apply { mkdirs() }
        writeSource("src/__data", PAYLOAD)
        writeSource("src/extra.txt", "extra".toByteArray(StandardCharsets.UTF_8))
        val dstDir = File(root, "dst")

        assertTrue("copyDirectory 应当成功", service.copyDirectory(srcDir.absolutePath, dstDir.absolutePath))

        assertTrue(PAYLOAD.contentEquals(File(dstDir, "__data").readBytes()))
        assertEquals("extra", File(dstDir, "extra.txt").readText())
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(dstDir))
    }

    // ------------------------------------------------------------------
    // 2. 失败时目标保持旧内容（真 FUSE 上的失败路径）
    // ------------------------------------------------------------------

    /**
     * 源读不出来（这里用「源是一个目录」触发 `FileNotFoundException`）时，
     * **目标保持旧内容**，且不留下暂存件。
     *
     * 旧写法 `dest.outputStream()` 在这一格会先把目标截断成 0 字节。
     *
     * **诚实标注这个注入点的强度**：失败发生在写回调**开头的源打开**处，
     * 不是「写到一半」。真正写到一半的注入在真 FUSE 上做不到（详见
     * `interruptedWriteMidStreamPreservesOriginalOnRealFuse` 用的是写入器本身）。
     */
    @Test
    fun copyFailureLeavesTargetUntouchedOnRealFuse() {
        val dirAsSource = File(root, "a-directory").apply { mkdirs() }
        val dst = File(root, "__data").apply { writeText("original") }

        assertFalse("源不是一个文件，copyFile 必须报失败", service.copyFile(dirAsSource.absolutePath, dst.absolutePath))
        assertEquals("失败后目标必须保持旧内容", "original", dst.readText())
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(root))
    }

    /**
     * **写到一半失败**：直接在真 FUSE 上驱动 [AtomicFileWriter]，写入回调里抛
     * `IOException`（近似磁盘不足 / 源读到一半出错）。目标必须保持旧内容。
     *
     * 这一条对应 JVM 侧的 `interruptedWritePreservesOriginal`，但跑在**设备文件系统**
     * 上 —— 宿主 NTFS 的结论不能外推到 FUSE，所以这条不是重复。
     */
    @Test
    fun interruptedWriteMidStreamPreservesOriginalOnRealFuse() {
        val dst = File(root, "__data").apply { writeText("original") }

        val thrown = try {
            AtomicFileWriter().write(dst) { output ->
                output.write(PAYLOAD, 0, PAYLOAD.size / 4)
                throw IOException("磁盘不足（模拟）")
            }
            null
        } catch (e: IOException) {
            e
        }

        assertEquals("磁盘不足（模拟）", thrown?.message)
        assertEquals("失败后目标必须保持旧内容", "original", dst.readText())
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(root))
    }

    /**
     * **空源不得清空目标** —— 这条是设备侧唯一能确定性区分新旧实现的用例。
     *
     * 为什么必须补这一条（2026-09-17 证伪实验测出来的）：上面那条
     * `copyFailureLeavesTargetUntouchedOnRealFuse` 用「源是一个目录」触发失败，
     * 而**旧写法里 `source.inputStream()` 在 `dest.outputStream()` 之前就抛了** ——
     * 目标压根没被打开过。实测把 `copyStream` 打回旧的截断写法，设备套件
     * **9/9 依旧全绿**，即那条用例对「是否截断目标」**零区分度**。
     *
     * 这一条不同：源是一个**合法但为空**的文件。
     *   - 旧写法：`dest.outputStream()` 打开即把目标截成 0 字节，然后拷 0 字节，
     *     返回 `true` —— 用户的 `__data` 被一份空文件顶掉，且报告成功。
     *   - 新写法：暂存件写完是 0 字节，`verifyBeforeCommit` 拒绝提交
     *     （「暂存文件为空」），`copyFile` 返回 `false`，**目标保持旧内容**。
     *
     * 破坏实验证明这条用例真的会红（`T07.md` §6.4 证伪 C）。
     */
    @Test
    fun emptySourceMustNotTruncateTheTargetOnRealFuse() {
        val emptySource = writeSource("empty.bin", ByteArray(0))
        val dst = File(root, "__data").apply { writeText("original") }

        val ok = service.copyFile(emptySource.absolutePath, dst.absolutePath)

        assertFalse("空源必须判失败（否则目标会被清空却报成功）", ok)
        assertEquals("目标必须保持旧内容", "original", dst.readText())
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(root))
    }

    /**
     * **校验失败**（替换之前）：目标保持旧内容 —— 证明校验确实发生在替换之前。
     *
     * 若实现把校验挪到替换之后，这一格会变成新内容（或抛异常但目标已被改）。
     */
    @Test
    fun failedValidationPreservesOriginalOnRealFuse() {
        val dst = File(root, "__data").apply { writeText("original") }

        var thrown: IOException? = null
        try {
            AtomicFileWriter().write(dst, verifyBeforeCommit = { throw IOException("校验不通过（模拟）") }) { output ->
                output.write(PAYLOAD)
            }
        } catch (e: IOException) {
            thrown = e
        }

        assertEquals("校验不通过（模拟）", thrown?.message)
        assertEquals("校验失败后目标必须保持旧内容", "original", dst.readText())
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(root))
    }

    // ------------------------------------------------------------------
    // 3. 批次部分成功是真实状态（不许宣称为整批事务）
    // ------------------------------------------------------------------

    /**
     * **两次独立的单文件提交：第一次成功、第二次失败。**
     *
     * 断言的是那个真实存在的中间状态：文件 1 已经**提交**（新内容在盘上），
     * 文件 2 保持旧内容。所以「目录级复制」不是事务，第 k 个失败时前 k-1 个
     * 已经落进去了 —— 调用方必须按目标分别上报。
     *
     * **为什么用两次 `copyFile` 而不是一次 `copyDirectory`**：需要一个**确定性**的
     * 「第二个文件失败」。`copyDirectory` 的遍历顺序不保证，用一个不可靠的顺序
     * 去断言「谁先成功」会让这条用例变成掷骰子。两次显式调用把同一件事
     * （N 次独立提交、部分成功）钉得死死的，代价是它不是从 `copyDirectory`
     * 入口进去的 —— 这个折扣如实登记。
     */
    @Test
    fun commitsArePerFileSoPartialSuccessIsARealState() {
        val firstSource = writeSource("one.bin", PAYLOAD)
        val secondSource = File(root, "not-a-file").apply { mkdirs() }
        val firstTarget = File(root, "one/__data").apply { parentFile!!.mkdirs(); writeText("old-one") }
        val secondTarget = File(root, "two/__data").apply { parentFile!!.mkdirs(); writeText("old-two") }

        val firstOk = service.copyFile(firstSource.absolutePath, firstTarget.absolutePath)
        val secondOk = service.copyFile(secondSource.absolutePath, secondTarget.absolutePath)

        assertTrue("第一个文件应当已提交", firstOk)
        assertTrue("第一个目标是完整新内容", PAYLOAD.contentEquals(firstTarget.readBytes()))
        assertFalse("第二个文件应当失败", secondOk)
        assertEquals("第二个目标保持旧内容", "old-two", secondTarget.readText())
        assertEquals("两个目录都不许残留暂存件", emptyList<String>(), stagingNames(File(root, "one")))
        assertEquals("两个目录都不许残留暂存件", emptyList<String>(), stagingNames(File(root, "two")))
    }

    // ------------------------------------------------------------------
    // 4. 取消 / 线程中断：不许留下半个文件
    // ------------------------------------------------------------------

    /**
     * 提交过程中中断执行线程：目标**只可能**是「旧内容」或「完整的新内容」，
     * **绝不会**是半截内容。
     *
     * **这份保证来自哪里（必须说清，否则这条用例会被读成比实际更强）**：
     * 不是来自「取消点」，而是来自**提交的结构** —— 字节全部写进同目录暂存件，
     * 目标只在最后一步被原子替换一次。所以无论取消落在这个过程的哪一刻，
     * 目标都不可能处于中间态。
     *
     * **同样要说清它的弱处**：Java 的线程中断**不会**中止对普通文件的阻塞读
     * （`FileInputStream` 不响应 `interrupt()`），所以这次拷贝多半会照常跑完。
     * 断言因此写成「二者之一」，而不是「一定停在旧内容」—— 后者会是一条
     * 依赖线程调度的假断言。
     */
    @Test
    fun interruptedCommitNeverLeavesAPartialTargetOnRealFuse() {
        val big = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val src = writeSource("big.bin", big)
        val dst = File(root, "__data").apply { writeText("original") }
        val started = CountDownLatch(1)

        val worker = Thread {
            started.countDown()
            AtomicFileWriter().write(dst) { output ->
                src.inputStream().use { it.copyTo(output) }
            }
        }
        worker.start()
        assertTrue("工作线程应当启动", started.await(5, TimeUnit.SECONDS))
        worker.interrupt()
        worker.join(30_000)
        assertFalse("提交必须已经结束（不许挂住）", worker.isAlive)

        val bytes = dst.readBytes()
        val isOriginal = bytes.contentEquals("original".toByteArray(StandardCharsets.UTF_8))
        val isComplete = bytes.contentEquals(big)
        assertTrue(
            "目标只可能是旧内容或完整新内容，实际 ${bytes.size} 字节（半截 = 缺陷）",
            isOriginal || isComplete
        )
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(root))
    }

    // ------------------------------------------------------------------
    // 5. 权限：提交后的文件必须是「读得回来」的
    // ------------------------------------------------------------------

    /**
     * 提交后的目标**能被重新打开并完整读回**。
     *
     * 这条守的是一个真实的坑：`File.createTempFile` 在设备上建出来的是
     * **`rw-rw----`**（实测，见下一条用例）—— other 位没有读权限。若直接拿它去
     * 替换 `__data`，那份文件可能只有写入者自己读得到，而 `__data` 是要被
     * **游戏进程**读的（真实链路上写入者还是 shell UID）。所以实现里有一步
     * 「照搬目标已有权限；目标不存在时优先抄同目录已有文件、没有可抄的才退回
     * 父目录 rw 位」（见 `AtomicFileWriter.newFilePermissions`）。
     *
     * 这条用例顺带证明那一步在 FUSE 上**不会抛异常**把提交整个弄失败
     * （宿主 Windows 上 `Files.setPosixFilePermissions` 直接抛
     * `UnsupportedOperationException`，走的是跳过分支）。
     */
    @Test
    fun committedTargetIsReadableBackAfterReplacement() {
        val src = writeSource("perm.bin", PAYLOAD)
        val dst = File(root, "__data").apply { writeText("original") }

        assertTrue(service.copyFile(src.absolutePath, dst.absolutePath))

        assertTrue("目标必须仍是可读的普通文件", dst.isFile)
        assertTrue("提交后必须能完整读回", PAYLOAD.contentEquals(dst.readBytes()))
        assertTrue("目标必须非空", dst.length() > 0)
    }

    /**
     * **提交不许把目标原有的权限降级** —— 这条是权限那一步的直接判据。
     *
     * 实测背景（`T07.md` §5.3，探针 `T07PermProbeTest`）：
     *   - 该卷上**新建**文件拿到的是 **`rw-rw----`**（other 无读权限）——
     *     而 `File.createTempFile` 建的暂存件正是同一个形态；
     *   - 该卷**支持** `setPosixFilePermissions`（设成 `rw-r--r--` 后读回一致）。
     *
     * 所以：**若不把目标原有权限搬到暂存件上**，一次提交就会把原本 other 可读的
     * `__data` 悄悄降级成 `rw-rw----`。`__data` 是要被**游戏进程**读的，
     * 那是一次静默的功能回归 —— 比拷贝失败更糟，因为它报告成功。
     *
     * ## 这条用例的断言为什么是「保持」而不是「other 可读」
     *
     * 初版我断言的是「提交后必须 other 可读」，拿一个 `writeText` 建出来的
     * （即 `rw-rw----`）目标去跑，**真的红了**。那次红是对的、但断言写错了对象：
     * 「目标本来是什么权限就保持什么权限」才是这一步的契约，**不是**「一律放宽到
     * other 可读」。所以这里显式先把目标设成 `rw-r--r--`，再验它**没被降级** ——
     * 这才是「照搬权限」这条防线要防的那件事。
     *
     * 新建目标那一半（同目录有文件时抄兄弟、没有时才退回父目录）由
     * `newTargetInheritsSiblingFilePermissionsOnRealFuse` 与
     * `newTargetFallsBackToParentDirectoryPermissionsWhenDirectoryHasNoPlainFiles` 两条覆盖。
     *
     * **诚实标注它证明不了什么**：它证明的是「我们的实现产出的权限位与目标原权限
     * 一致」，**不**证明游戏进程真的读得到 —— 那需要以游戏 UID 去读，而游戏目录
     * app 根本无权碰（见 `T07.md` §7.2 未覆盖项）。
     */
    @Test
    fun commitDoesNotDowngradeTargetPermissions() {
        val src = writeSource("perm-other.bin", PAYLOAD)
        val dst = File(root, "__data").apply { writeText("original") }
        val wanted = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
        try {
            java.nio.file.Files.setPosixFilePermissions(dst.toPath(), wanted)
        } catch (e: UnsupportedOperationException) {
            throw AssertionError("这个卷不支持 POSIX 权限视图 —— 本用例的前提不成立，不许静默跳过", e)
        }

        assertTrue(service.copyFile(src.absolutePath, dst.absolutePath))

        val perms = java.nio.file.Files.getPosixFilePermissions(dst.toPath())
        assertEquals("提交后必须保持目标原有权限，不许被暂存件的默认权限降级", wanted, perms)
    }

    /**
     * **同目录里已有普通文件时，新建目标照抄它的权限**（T07 三轮整改，裁定 2 改成 (b)）。
     *
     * 这是「与同目录邻居一致」那条意图的**直接判据**：真实游戏路径上，存放 `__data`
     * 的 `Shared/<bundle>/<hash>/` 里总是已经有 `__data` / `__info`，新建的那份应当
     * 与它们拿到**同一个 mode** —— 而不是由父目录推出来的值。
     *
     * ## 为什么兄弟文件用 `rw-rw-r--`（0664）
     *
     * 这个值**刻意与四个候选都不同**，一条用例同时钉住四种破坏形态：
     *
     * | 形态 | 会得到的值 | 本断言 |
     * |---|---|---|
     * | 正确：照抄兄弟文件 | `rw-rw-r--` 0664 | **绿** |
     * | 改坏：永远走父目录回退（三轮证伪 J） | `rw-r-----` 0640 | **红** |
     * | 改坏：硬编码 `rw-r--r--`（二轮证伪 E2 的形态） | 0644 | **红** |
     * | 改坏：硬编码 `rw-------`（复核者 R2 的形态） | 0600 | **红** |
     * | 改坏：整个权限搬运那步去掉 | 0660（该卷 `createTempFile` 的默认值） | **红** |
     *
     * 父目录由用例显式设成 `rwxr-x---`（0750）—— 它屏蔽执行位后是 `rw-r-----`
     * （0640），与兄弟文件的 0664 不同，于是「抄兄弟」与「抄父目录」这两条分支
     * 在这条断言下**可区分**。真实游戏目录里兄弟文件的 mode 是 `rw-rw----`（0660），
     * 两者只是取值不同，判据（抄兄弟）完全一样。
     *
     * 破坏实验：把 `newFilePermissions` 改回「永远走父目录回退」这条真的会红 ——
     * 见 `T07.md` §6.7。
     */
    @Test
    fun newTargetInheritsSiblingFilePermissionsOnRealFuse() {
        val src = writeSource("sibling-perm.bin", PAYLOAD)
        val parent = File(root, "with-sibling").apply { mkdirs() }
        // 父目录设成一个「与兄弟文件不同」的 mode —— 否则两条分支不可区分
        val parentMode = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---")
        // 兄弟文件（真实路径上就是同目录的 __info）设成 0664，见上面的判别表
        val siblingMode = java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--")
        val sibling = File(parent, "__info").apply { writeText("{}") }
        try {
            Files.setPosixFilePermissions(parent.toPath(), parentMode)
            Files.setPosixFilePermissions(sibling.toPath(), siblingMode)
        } catch (e: UnsupportedOperationException) {
            throw AssertionError("这个卷不支持 POSIX 权限视图 —— 本用例的前提不成立，不许静默跳过", e)
        }
        assertEquals(
            "前提：父目录权限必须真的是我们设的那个",
            parentMode,
            Files.getPosixFilePermissions(parent.toPath())
        )
        assertEquals(
            "前提：兄弟文件权限必须真的是我们设的那个",
            siblingMode,
            Files.getPosixFilePermissions(sibling.toPath())
        )

        val dst = File(parent, "__data")
        assertTrue(service.copyFile(src.absolutePath, dst.absolutePath))

        assertTrue("落盘内容必须逐字节一致", PAYLOAD.contentEquals(dst.readBytes()))
        assertEquals(
            "新建目标必须照抄同目录已有文件的权限（兄弟 $siblingMode），不是父目录推出来的值",
            siblingMode,
            Files.getPosixFilePermissions(dst.toPath())
        )
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(parent))
    }

    /**
     * **同目录里没有任何普通文件可参考**时，退回「父目录 rw 位、屏蔽执行位」。
     *
     * 这是 [AtomicFileWriter.newFilePermissions] 的第 2 条（有界回退），真实游戏路径
     * **不会**走到它（`Shared/<bundle>/<hash>/` 里总有 `__data`/`__info`）—— 所以
     * 这条用例覆盖的是**不在生产路径上**的分支，但它必须存在：它是「目录为空时行为
     * 仍然确定」这条承诺的唯一判据，也是三轮整改前那条规则的等价保留。
     *
     * 父目录**显式**设成 `rwxr-x---`（0750），屏蔽执行位后是 `rw-r-----`（0640）——
     * 与 0644 / 0660 / 0600 都不同，任何硬编码常量都会在这里露馅（这正是二轮真缺陷 #1
     * 的判据，三轮保留）。为什么不能靠「目录默认是什么」：那要看该卷的 umask，
     * 测出来可能是 0755，而 0755 屏蔽执行位正好是 0644，与初版硬编码值撞上，
     * 这条用例就没有区分度了。
     *
     * 期望值是**写死的字面量**，刻意不调用 `AtomicFileWriter.maskExecutableBits`
     * 求值 —— 用被测代码自己算期望，翻译规则被改坏时输入与期望会一起变、断言恒真。
     * 翻译规则本身由 JVM 的 `maskExecutableBitsKeepsReadWriteAndDropsExecute` 钉住。
     */
    @Test
    fun newTargetFallsBackToParentDirectoryPermissionsWhenDirectoryHasNoPlainFiles() {
        val src = writeSource("fresh-perm.bin", PAYLOAD)
        val parent = File(root, "brand-new").apply { mkdirs() }
        val distinctive = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-x---")
        try {
            Files.setPosixFilePermissions(parent.toPath(), distinctive)
        } catch (e: UnsupportedOperationException) {
            throw AssertionError("这个卷不支持 POSIX 权限视图 —— 本用例的前提不成立，不许静默跳过", e)
        }
        val parentPermissions = Files.getPosixFilePermissions(parent.toPath())
        assertEquals(
            "前提：本用例必须能控制父目录的权限位，否则判据无从谈起（实测 $parentPermissions）",
            distinctive,
            parentPermissions
        )
        assertEquals("前提：这个目录里必须真的没有普通文件", emptyList<String>(), parent.listFiles()!!.map { it.name })

        val dst = File(parent, "__data")
        assertTrue(service.copyFile(src.absolutePath, dst.absolutePath))

        assertTrue("落盘内容必须逐字节一致", PAYLOAD.contentEquals(dst.readBytes()))
        val expected = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----")
        assertEquals(
            "目录里没有可参考的文件时，新建目标的权限必须是「父目录 rw 位」（父目录 $parentPermissions ⇒ $expected）",
            expected,
            Files.getPosixFilePermissions(dst.toPath())
        )
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(parent))
    }

    /**
     * **目录树里含合法 0 字节文件时，整棵树照样拷成功**（T07 二轮整改，真缺陷 #2）。
     *
     * 初版把「空源拒绝」无条件写在 `copyStream` 里，而 `copyTree` 对树里每个文件
     * 都调它 —— 于是任何一个 0 字节文件（Android 上惯例的 `.nomedia` 就是 0 字节）
     * 都会让整个 `copyDirectory` 失败，**连非空的那个文件也不落地**。
     * 复核者的对照实验（`T07.md` §8.9）：同设备同目录树，旧写法 `ok=true`（两个都
     * 落地）、新写法 `ok=false`。可达路径真实：`moveDownloadToGame` 的源是
     * 用户可写的 `Download/Shared`。
     *
     * 这条用例钉的是**收窄后的语义**：目录树拷贝必须允许 0 字节文件，且它要真的
     * 以 0 字节落地（不是被跳过、也不是被写成别的东西）。
     *
     * 破坏实验：把 `copyTree` 的 `allowEmptySource` 去掉（回到全局拒绝），
     * 这条会红 —— 见 `T07.md` §11.2。
     */
    @Test
    fun copyDirectoryWithEmptyFileCommitsWholeTreeOnRealFuse() {
        val srcDir = File(root, "src-with-empty").apply { mkdirs() }
        val payload = writeSource("src-with-empty/__data", PAYLOAD)
        val empty = writeSource("src-with-empty/.nomedia", ByteArray(0))
        val dstDir = File(root, "dst-with-empty")

        val ok = service.copyDirectory(srcDir.absolutePath, dstDir.absolutePath)

        assertTrue("树里含合法的 0 字节文件，整个目录复制必须成功", ok)
        assertTrue(
            "非空的 __data 必须落地且逐字节一致",
            PAYLOAD.contentEquals(File(dstDir, "__data").readBytes())
        )
        val copiedEmpty = File(dstDir, ".nomedia")
        assertTrue("0 字节文件必须真的被拷贝过去", copiedEmpty.isFile)
        assertEquals("拷贝过去的空文件必须是 0 字节", 0L, copiedEmpty.length())
        assertEquals("暂存件不许残留", emptyList<String>(), stagingNames(dstDir))
        // 前置自证：源里那个文件确实是 0 字节（否则这条用例测的不是它以为的东西）
        assertEquals("前提：源文件必须是 0 字节", 0L, empty.length())
        assertEquals("前提：源 __data 必须非空", PAYLOAD.size.toLong(), payload.length())
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun writeSource(relative: String, payload: ByteArray): File {
        val f = File(root, relative)
        f.parentFile?.mkdirs()
        f.writeBytes(payload)
        return f
    }

    private fun stagingNames(dir: File): List<String> =
        AtomicFileWriter.listStagingFiles(dir).map { it.name }.sorted()

    private companion object {
        /** 含 NUL 与高位字节：`__data` 是二进制，任何按文本处理的实现都会在这里露馅。 */
        val PAYLOAD: ByteArray = byteArrayOf(
            0x55, 0x6E, 0x69, 0x74, 0x79, 0x46, 0x53, 0x00, 0x00, 0x7F, 0x00, 0x01
        ) + ByteArray(4096) { (it % 256).toByte() }
    }
}
