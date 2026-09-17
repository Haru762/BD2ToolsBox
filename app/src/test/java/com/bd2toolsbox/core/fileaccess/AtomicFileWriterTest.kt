package com.bd2toolsbox.core.fileaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * [AtomicFileWriter] 的 JVM 单元测试（T07）。
 *
 * 纯 `java.io` / `java.nio.file` + [TemporaryFolder]，**不需要 mockk** ——
 * 被测对象不依赖任何 Android 对象，也不依赖 UI 包。
 *
 * ## 跑在 Windows 宿主上，这决定了实现的写法（不是断言的口味问题）
 *
 * 三条实测（原始输出见 `docs/refactor/evidence/T07.md` §6.0）：
 *
 *  1. **裸 `tmp.renameTo(target)` 覆盖已存在目标在宿主上返回 `false`**
 *     （A 行），目标保持旧内容、暂存件留下。所以实现用的是
 *     `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)`（C 行成功），
 *     而不是仓库里 `BundleBackupRepository` 那种 delete-then-rename。
 *  2. **只给 `ATOMIC_MOVE` 不给 `REPLACE_EXISTING` 在宿主上也成功**（C2 行）——
 *     那是个陷阱，实现里两个选项都显式写出来。
 *  3. **父目录不存在时 `createTempFile` 直接抛 `IOException`**（F 行），
 *     所以实现必须先 `mkdirs`。
 *
 * ## 宿主结论不许外推到设备
 *
 * 上面三条都是 **NTFS** 上的语义。目标设备是 Android，`/storage/emulated/0` 经
 * FUSE/sdcardfs 挂载，`ATOMIC_MOVE` 的支持情况、`fd.sync()` 的可支持性、
 * 权限位语义在那里**全部未测**。这一层由 `GameFileCommitTest`（instrumentation，
 * 真 FUSE）覆盖，覆盖不到的半边登记在 `T07.md` §7。
 */
class AtomicFileWriterTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val writer = AtomicFileWriter()

    // ------------------------------------------------------------------
    // 1. 计划 §7.2 逐字规格的三条（用例名照抄，不得改名）
    // ------------------------------------------------------------------

    /**
     * 写入回调中途抛 [IOException]（近似磁盘不足与源读到一半出错）——
     * 必须原样抛出去，且**目标仍是旧内容**。
     *
     * 这是本任务的核心判据：旧写法 `dest.outputStream()` 打开即截断，
     * 这一格会变成 `target == ""` 或半截内容。
     */
    @Test
    fun interruptedWritePreservesOriginal() {
        val target = newTarget("original")

        val thrown = assertThrows(IOException::class.java) {
            writer.write(target) { output ->
                output.write("partial".toByteArray(StandardCharsets.UTF_8))
                throw IOException("磁盘不足（模拟）")
            }
        }
        assertEquals("磁盘不足（模拟）", thrown.message)
        assertEquals("original", target.readText())
    }

    /**
     * 校验回调抛 [IOException] —— 同样原样抛出、目标保持旧内容。
     *
     * 与上一条的区别在于失败发生在**替换之前**的校验环节：写入本身是成功的，
     * 拦住提交的是校验。它证明「校验发生在替换之前」不是一句注释。
     */
    @Test
    fun failedValidationPreservesOriginal() {
        val target = newTarget("original")

        val thrown = assertThrows(IOException::class.java) {
            writer.write(target, verifyBeforeCommit = { throw IOException("校验不通过（模拟）") }) { output ->
                output.write("replacement".toByteArray(StandardCharsets.UTF_8))
            }
        }
        assertEquals("校验不通过（模拟）", thrown.message)
        assertEquals("original", target.readText())
    }

    /** 正常写入 —— 目标得到**完整**的新内容。 */
    @Test
    fun successfulWriteReplacesWholeContent() {
        val target = newTarget("original")

        writer.write(target) { output ->
            output.write("replacement".toByteArray(StandardCharsets.UTF_8))
        }
        assertEquals("replacement", target.readText())
    }

    // ------------------------------------------------------------------
    // 2. 「暂存文件可识别清理」—— 计划验收里 JVM 侧唯一守得住这一条的地方
    // ------------------------------------------------------------------

    /** 暂存件的名字必须能被认出来：固定前缀 + 固定后缀。 */
    @Test
    fun stagingFileNameIsRecognisable() {
        assertTrue(AtomicFileWriter.isStagingName("${AtomicFileWriter.STAGING_PREFIX}12345${AtomicFileWriter.STAGING_SUFFIX}"))
        assertFalse("普通文件不能被误判成暂存件", AtomicFileWriter.isStagingName("__data"))
        assertFalse("只有前缀没有后缀不算", AtomicFileWriter.isStagingName("${AtomicFileWriter.STAGING_PREFIX}12345"))
        assertFalse("只有后缀没有前缀不算", AtomicFileWriter.isStagingName("12345${AtomicFileWriter.STAGING_SUFFIX}"))
    }

    /**
     * 顺序边界：暂存件必须在**目标同目录**里创建（跨目录改名在某些卷上会退化成
     * 复制 + 删除，原子性就没了），且校验回调看到的确实是那个同目录暂存件。
     */
    @Test
    fun stagingFileLivesInTheTargetDirectoryAndIsVisibleToVerification() {
        val target = newTarget("original")
        var seenParent: String? = null
        var seenName: String? = null

        writer.write(target, verifyBeforeCommit = { staged ->
            seenParent = staged.parentFile?.absolutePath
            seenName = staged.name
        }) { output ->
            output.write("replacement".toByteArray(StandardCharsets.UTF_8))
        }

        assertEquals(target.parentFile?.absolutePath, seenParent)
        assertNotNull(seenName)
        assertTrue("校验回调看到的是暂存件，不是目标本身", AtomicFileWriter.isStagingName(seenName!!))
    }

    /**
     * 进程被杀（`System.exit` / 被系统回收）时 finally 不执行，暂存件会留下 ——
     * 这一格用「替身残留」直接验 [AtomicFileWriter.listStagingFiles] 能找到它，
     * 且**不会**把目标或别的文件误报成暂存件。
     */
    @Test
    fun listStagingFilesFindsOnlyStagingLeftovers() {
        val dir = temporaryFolder.newFolder("commit")
        val target = File(dir, "__data").apply { writeText("original") }
        val leftover = File(dir, "${AtomicFileWriter.STAGING_PREFIX}999${AtomicFileWriter.STAGING_SUFFIX}")
            .apply { writeText("half written") }
        File(dir, "unrelated.txt").writeText("not ours")

        val found = AtomicFileWriter.listStagingFiles(dir).map { it.name }.sorted()

        assertEquals(listOf(leftover.name), found)
        assertEquals("original", target.readText())
    }

    /**
     * 失败路径上的暂存件**不残留**（`write` 的 finally 自己收）。
     *
     * 与上一条的区别：上一条是「进程被杀」那种 finally 跑不到的形态，
     * 这一条是「异常正常抛出」的形态 —— 两者都由验收要求「暂存文件可识别清理」覆盖，
     * 但处置方式不同，不能互相替代。
     */
    @Test
    fun failedWriteLeavesNoStagingFileBehind() {
        val dir = temporaryFolder.newFolder("commit-no-residue")
        val target = File(dir, "__data").apply { writeText("original") }

        assertThrows(IOException::class.java) {
            writer.write(target) { output ->
                output.write("partial".toByteArray(StandardCharsets.UTF_8))
                throw IOException("磁盘不足（模拟）")
            }
        }

        assertEquals(emptyList<String>(), AtomicFileWriter.listStagingFiles(dir).map { it.name })
        assertEquals(listOf("__data"), dir.listFiles()!!.map { it.name }.sorted())
    }

    // ------------------------------------------------------------------
    // 3. 边界与既有行为
    // ------------------------------------------------------------------

    /**
     * 目标不存在（新建）时也要能提交 —— 这条覆盖 `copyFile` 写一个全新 `__data`
     * 的场景，此时没有「旧内容」可保，但暂存 + 替换的路径必须一样能走通。
     */
    @Test
    fun committingToANonExistentTargetCreatesIt() {
        val dir = temporaryFolder.newFolder("fresh")
        val target = File(dir, "sub/__data")

        writer.write(target) { output -> output.write("brand new".toByteArray(StandardCharsets.UTF_8)) }

        assertEquals("brand new", target.readText())
        assertEquals(emptyList<String>(), AtomicFileWriter.listStagingFiles(target.parentFile!!).map { it.name })
    }

    /**
     * 父目录不存在时必须自己建出来（实测 F 行：不建就直接抛 `IOException`）。
     */
    @Test
    fun missingParentDirectoryIsCreated() {
        val dir = temporaryFolder.newFolder("mkdirs")
        val target = File(dir, "a/b/c/__data")

        writer.write(target) { output -> output.write("deep".toByteArray(StandardCharsets.UTF_8)) }

        assertEquals("deep", target.readText())
    }

    /**
     * 校验回调**看得到**刚写进去的全部字节（而不是一个还空着的文件）。
     *
     * 这条防的是「先替换、后校验」那种实现：那种写法下校验时读到的会是**旧目标**
     * 的内容（或已经新写入的内容，取决于顺序），总之拿不到暂存件。
     */
    @Test
    fun verificationSeesTheFullyWrittenStagedContent() {
        val target = newTarget("original")
        val payload = "replacement-with-some-length".toByteArray(StandardCharsets.UTF_8)
        var stagedBytes: ByteArray? = null

        writer.write(target, verifyBeforeCommit = { staged ->
            stagedBytes = staged.readBytes()
        }) { output ->
            output.write(payload)
        }

        assertEquals(payload.size, stagedBytes?.size)
        assertEquals("replacement-with-some-length", String(stagedBytes!!, StandardCharsets.UTF_8))
    }

    /**
     * **二进制内容逐字节一致**（含 NUL 与高位字节）。
     *
     * `__data` 是二进制，任何按文本处理的实现都会在这里露馅 —— 这是「校验不许把
     * 整份文件读进内存」与「内容必须完整」两条的交汇处：用长度做校验时，
     * 一个把 0x00 吞掉的实现对长度是敏感的（这里顺带把它钉住）。
     */
    @Test
    fun binaryContentSurvivesByteForByte() {
        val target = newTarget("original")
        val payload = byteArrayOf(0x55, 0x6E, 0x69, 0x74, 0x79, 0x46, 0x53, 0x00, 0x00, 0x7F, 0x00, 0x01)

        writer.write(target, verifyBeforeCommit = { staged ->
            assertEquals(payload.size.toLong(), staged.length())
        }) { output ->
            output.write(payload)
        }

        assertTrue("落盘内容必须逐字节一致", payload.contentEquals(target.readBytes()))
    }

    /**
     * 校验**不通过**时抛出的异常必须是调用方自己那个（不被包装成自定义类型）。
     * 契约原文：「写入/校验失败向调用者抛出原始错误」。T05 的教训是静默丢弃，
     * 另一个方向的错是「包一层，把原始类型和消息都盖掉」。
     */
    @Test
    fun verifyFailurePropagatesTheOriginalThrowableType() {
        val target = newTarget("original")
        val original = IllegalStateException("业务校验失败：bundle 头魔数不对")

        val thrown = assertThrows(IllegalStateException::class.java) {
            writer.write(target, verifyBeforeCommit = { throw original }) { output ->
                output.write("replacement".toByteArray(StandardCharsets.UTF_8))
            }
        }

        assertEquals(original, thrown)
        assertEquals("original", target.readText())
    }

    /**
     * **目标在整个过程中都不被提前动过** —— 这条是「不许先截断/先删除最终文件」的
     * 可执行判据。
     *
     * 断言放在**写入回调与校验回调内部**：这两个时刻目标必须还是旧内容。
     *   - 旧写法 `dest.outputStream()` 打开即截断 → 写入回调执行时目标已经空了；
     *   - 「先删目标、写完暂存件再改名」的实现 → 写入回调执行时目标已经不存在。
     *
     * 探针实测（`T07.md` §6.0）：宿主上 `ATOMIC_MOVE + REPLACE_EXISTING` 替换后
     * **创建时间会变**（C3），而 delete + rename 反而**不变**（C4，Windows 的文件
     * 隧道效应）—— 所以「用文件身份/时间戳证明没走 delete 那条路」在两平台上都
     * **不可靠**，本用例改用上面这个不依赖平台属性的观测点。
     */
    @Test
    fun targetIsUntouchedUntilTheAtomicReplacement() {
        val target = newTarget("original")
        var duringWrite: String? = null
        var duringVerify: String? = null

        writer.write(target, verifyBeforeCommit = { duringVerify = target.readText() }) { output ->
            duringWrite = target.readText()
            output.write("replacement".toByteArray(StandardCharsets.UTF_8))
        }

        assertEquals("写入期间目标必须还是旧内容", "original", duringWrite)
        assertEquals("校验期间目标必须还是旧内容", "original", duringVerify)
        assertEquals("提交后是新内容", "replacement", target.readText())
    }

    // ------------------------------------------------------------------
    // 2.5 「新建目标继承父目录权限位」的纯逻辑（T07 二轮整改，裁定 2）
    // ------------------------------------------------------------------

    /**
     * 目录权限 → 同组普通文件权限：**保留三个 rw 位，去掉三个执行位**。
     *
     * 这条能在 JVM 上真跑，是因为 [AtomicFileWriter.maskExecutableBits] 是纯函数 ——
     * 不碰文件系统。**但它只是回退分支的翻译规则**：新建目标的真实取值常规情况下来自
     * 「同目录里已有普通文件的 mode」（设备侧由
     * `GameFileCommitTest.newTargetInheritsSiblingFilePermissionsOnRealFuse` 钉住），
     * 只有该目录里**没有普通文件**时才走这里，那条回退路径由
     * `GameFileCommitTest.newTargetFallsBackToParentDirectoryPermissionsWhenDirectoryHasNoPlainFiles` 钉住。
     *
     * 期望值用 `PosixFilePermissions.fromString` 写（而不是直接编 0b 位），
     * 这样读代码的人能一眼对上是哪个 mode。
     */
    @Test
    fun maskExecutableBitsKeepsReadWriteAndDropsExecute() {
        fun mask(mode: String): String =
            PosixFilePermissions.toString(
                AtomicFileWriter.maskExecutableBits(PosixFilePermissions.fromString(mode))
            )

        // 目录 rwxrwx---（0770 形态）→ 文件 rw-rw----（0660）。
        // 注意：真实游戏目录里存放 __data 的那一层是 drwxr-s---（**2750**，不是 2770），
        // 所以这一格在真实路径上给出的是 0640 —— 它只覆盖「回退分支」的翻译规则，
        // 真实路径走的是「照抄同目录已有文件」那条（设备用例
        // newTargetInheritsSiblingFilePermissionsOnRealFuse）。见 T07.md §5.3。
        // setgid（s 位）不在 POSIX permission 集合里，SDK 也不暴露它，所以 here 只比 rwx。
        assertEquals("rw-rw----", mask("rwxrwx---"))
        // 本次整改要消灭的那个硬编码值：目录 rwxr-x--- 不该给出 0644，而该给出 0640。
        assertEquals("rw-r-----", mask("rwxr-x---"))
        // 全开目录 → 全开文件（去掉执行位后仍是 rw-rw-rw-）
        assertEquals("rw-rw-rw-", mask("rwxrwxrwx"))
        // 只读目录 → 只读文件
        assertEquals("r--r--r--", mask("r-xr-xr-x"))
        // 空集合（mode 000 的目录）不许抛异常，也不许造出权限
        assertEquals(emptySet<PosixFilePermission>(), AtomicFileWriter.maskExecutableBits(emptySet()))
        // 已经是文件权限时幂等
        assertEquals("rw-r--r--", mask("rw-r--r--"))
    }

    // ------------------------------------------------------------------
    // 2.6 「新建目标优先抄同目录已有文件」的挑选规则（T07 三轮整改，裁定 2 改成 (b)）
    // ------------------------------------------------------------------

    /**
     * **挑选参考文件必须确定性**：按文件名升序取第一个普通文件。
     *
     * 这条守的是「新建目标的权限从哪来」这件事的**可复现性**。用
     * `listFiles()[0]` 那种写法会让权限值跟着文件系统的返回顺序飘 —— 同一份输入在
     * 不同卷、不同时刻给出不同结果，设备用例就会变成掷骰子。名字序是纯函数的顺序。
     *
     * 输入**故意打乱**（不按名字序给），这样「实现只是碰巧拿到第一个」会被抓出来。
     */
    @Test
    fun selectReferenceFilePicksLowestNameDeterministically() {
        val dir = temporaryFolder.newFolder("reference")
        val zeta = File(dir, "zeta.bin").apply { writeText("z") }
        val alpha = File(dir, "__info").apply { writeText("a") }
        val mid = File(dir, "middle.bin").apply { writeText("m") }

        // 传入顺序刻意是 zeta → alpha → mid（不是名字序）
        val picked = AtomicFileWriter.selectReferenceFile(listOf(zeta, alpha, mid))

        assertEquals("必须取名字最小的那个，与传入顺序无关", alpha.name, picked?.name)
    }

    /**
     * **暂存件不许被当成参考对象。**
     *
     * 这不是假想情形：`write` 是**先**在同一目录里 `createTempFile` 建出暂存件、
     * **再**调权限搬运的，所以 `listFiles()` 里此刻一定有我们自己那个
     * `.bd2staged-….tmp`，而且点前缀在名字序里排在 `__data` / `__info` 之前 ——
     * 不排除的话它会稳定地成为「第一个」，新文件就抄到了**暂存件自己的默认权限**，
     * 恰好是这一步要避免的形态。
     */
    @Test
    fun selectReferenceFileSkipsStagingFiles() {
        val dir = temporaryFolder.newFolder("reference-staging")
        val staging = File(dir, "${AtomicFileWriter.STAGING_PREFIX}999${AtomicFileWriter.STAGING_SUFFIX}")
            .apply { writeText("half written") }
        val real = File(dir, "__info").apply { writeText("{}") }

        // 暂存件的点前缀在名字序里确实排在 "__info" 之前 —— 前提自证
        assertTrue(
            "前提：暂存件名在升序里必须排在 __info 之前，否则这条用例测不到排除逻辑",
            staging.name < real.name
        )
        assertEquals(
            "暂存件即使在名字序最前也不许被选中",
            real.name,
            AtomicFileWriter.selectReferenceFile(listOf(staging, real))?.name
        )
        assertEquals(
            "目录里只有暂存件时没有可参考的 → 返回 null（调用方退回父目录规则）",
            null,
            AtomicFileWriter.selectReferenceFile(listOf(staging))
        )
    }

    /**
     * **目录不算参考对象**：只含子目录时返回 `null`，让调用方退回父目录规则。
     *
     * 目录带着执行位，不是「普通文件权限」的参考形态；把它抄给新文件会带进执行位。
     */
    @Test
    fun selectReferenceFileIgnoresDirectories() {
        val dir = temporaryFolder.newFolder("reference-dirs")
        val sub = File(dir, "aaa-subdir").apply { mkdirs() }
        val file = File(dir, "zzz.bin").apply { writeText("z") }

        assertEquals("子目录必须被跳过，取的是那个普通文件", file.name, AtomicFileWriter.selectReferenceFile(listOf(sub, file))?.name)
        assertEquals(
            "只有子目录 = 没有可参考的 → null",
            null,
            AtomicFileWriter.selectReferenceFile(listOf(sub))
        )
        assertEquals("空目录 → null", null, AtomicFileWriter.selectReferenceFile(emptyList()))
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun newTarget(initial: String): File {
        val dir = temporaryFolder.newFolder("target-${System.nanoTime()}")
        return File(dir, "__data").apply { writeText(initial) }
    }
}
