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
import java.nio.charset.StandardCharsets

/**
 * [SafeArchivePaths] 的 JVM 单元测试（T06）。
 *
 * 纯 `java.io` / `java.nio` + [TemporaryFolder]，**不需要 mockk** —— 被测对象不依赖
 * Android 对象，也不依赖 UI 包。
 *
 * ## 跑在 Windows 宿主上，这决定了断言的写法
 *
 * 全部结论来自实测（见 `docs/refactor/evidence/T06.md` §3），三条最要紧的：
 *
 *  1. `C:/outside.txt` 在宿主上 `getCanonicalPath()` 抛的是 `java.io.IOException`。
 *     所以每条用例都用 `assertThrows(IllegalArgumentException::class.java, …)` ——
 *     它要求实际抛出物是 `IllegalArgumentException` 的实例，`IOException` **不会**让它通过。
 *     这一条正是「必须在碰 `File` 之前先做语法判定」的可执行判据。
 *  2. `/outside.txt` 在宿主上 `new File(root, name)` 其实**落在 root 内**（无盘符 →
 *     `isAbsolute()==false`），在 Android 上才是绝对路径。所以这条用例在宿主上
 *     只能验「闸门按语法拒绝了它」，不能验「宿主上它本来会逃逸」。
 *  3. 「符号链接逃逸」在宿主上**建不了** symlink（实测 `FileSystemException:
 *     客户端没有所需的特权`），只能退而用 **NTFS junction** 覆盖：被测的是同一条
 *     包含性判定（`toRealPath()` 对两者的解析路径相同），但 junction ≠ symlink，
 *     折扣写在 `traversalThroughJunctionIsRejected` 的注释里。
 */
class SafeArchivePathsTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    // ------------------------------------------------------------------
    // 1. 拒绝面：越界条目名一律 IllegalArgumentException
    // ------------------------------------------------------------------

    @Test
    fun parentTraversalIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "../outside.txt")
        }
        assertFalse("root 之外不能出现文件", File(root.parentFile, "outside.txt").exists())
    }

    @Test
    fun nestedTraversalIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "nested/../../outside.txt")
        }
    }

    @Test
    fun bareParentSegmentIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "..")
        }
    }

    @Test
    fun parentSegmentAnywhereInThePathIsRejected() {
        val root = newRoot()
        // 这条在「规范化后仍留在 root 内」和「直接拒绝」两种口径下都能挡住，
        // 本实现选后者（见 `innerParentSegmentIsRejectedNotSilentlyNormalized`）。
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "a/../../b.txt")
        }
    }

    @Test
    fun innerParentSegmentIsRejectedNotSilentlyNormalized() {
        val root = newRoot()
        // 契约内部有冲突：`task-T06-implementer-brief.md` §3.1 的「必须覆盖的拒绝面」
        // 写的是「任何 `..` 段」，而同 §3.1 的用例清单把 `sub/../inside.txt` 归进
        // 「正常通过（解析后仍在 root 内）」。本实现取**更严**的那一读：任何 `..` 段
        // 都在语法闸门被拒（理由与代价写在 T06.md 的「与本契约不符之处」）。
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "sub/../inside.txt")
        }
        // 无论走哪一读，**边界外不能出现东西**才是验收要求：
        assertFalse(File(root.parentFile, "inside.txt").exists())
        assertFalse(File(root.parentFile, "sub").exists())
    }

    @Test
    fun leadingSlashIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "/outside.txt")
        }
    }

    @Test
    fun leadingBackslashIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "\\outside.txt")
        }
    }

    @Test
    fun backslashTraversalIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "..\\outside.txt")
        }
    }

    @Test
    fun mixedSeparatorTraversalIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "sub/..\\..\\outside.txt")
        }
    }

    @Test
    fun windowsDriveWithForwardSlashIsRejected() {
        val root = newRoot()
        // 关键点：宿主上 getCanonicalPath() 对这类名字抛的是 IOException。
        // 断言类型是 IllegalArgumentException，所以本用例能判出「闸门是否在碰 File 之前生效」。
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "C:/outside.txt")
        }
    }

    @Test
    fun windowsDriveWithBackslashIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "C:\\outside.txt")
        }
    }

    @Test
    fun lowerCaseDriveIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "c:\\outside.txt")
        }
    }

    @Test
    fun uncStyleNamesAreRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "//server/share/e.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "\\\\server\\share\\e.txt")
        }
    }

    @Test
    fun emptyNameIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "")
        }
    }

    @Test
    fun nulCharacterIsRejected() {
        val root = newRoot()
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "data\u0000.txt")
        }
    }

    // ------------------------------------------------------------------
    // 2. 正常通过：嵌套目录 + 中文名 + 普通英文名
    // ------------------------------------------------------------------

    @Test
    fun nestedChineseNameIsAcceptedAndStaysInsideRoot() {
        val root = newRoot()
        // 实测基线：旧写法在这条上本来就正确（探针 t06-out.txt:10），改完必须仍然正确。
        val resolved = SafeArchivePaths.resolveInside(root, "角色/皮肤/data.txt")

        assertTrue(
            "解析结果必须在 root 内：${resolved.canonicalPath}",
            resolved.canonicalPath.startsWith(root.canonicalPath + File.separator)
        )
        assertEquals(
            File(File(root, "角色"), "皮肤").path + File.separator + "data.txt",
            resolved.path
        )
    }

    @Test
    fun plainEnglishNameIsAccepted() {
        val root = newRoot()
        val resolved = SafeArchivePaths.resolveInside(root, "atlas.png")
        assertEquals(File(root, "atlas.png").path, resolved.path)
    }

    @Test
    fun nestedDirectoriesKeepTheArchiveLayout() {
        val root = newRoot()
        val resolved = SafeArchivePaths.resolveInside(root, "a/b/c/d.bin")
        assertEquals(
            listOf("a", "b", "c", "d.bin"),
            root.toPath().relativize(resolved.toPath()).map { it.toString() }
        )
    }

    @Test
    fun fileWithDotsInTheNameIsNotTreatedAsTraversal() {
        val root = newRoot()
        // `..x` / `x..y` 不是 `..` 段，不能误伤。
        for (name in listOf("..x", "x..y", "..hidden")) {
            val resolved = SafeArchivePaths.resolveInside(root, name)
            assertEquals(File(root, name).path, resolved.path)
        }
    }

    // ------------------------------------------------------------------
    // 3. 符号链接逃逸 —— 宿主上用 junction 近似
    // ------------------------------------------------------------------

    @Test
    fun traversalThroughJunctionIsRejected() {
        val root = newRoot()
        val outside = temporaryFolder.newFolder("outside")
        val link = File(root, "link")
        createJunctionOrFail(link, outside)

        // (a) 目标还不存在：只能对「最近的存在祖先」取真实路径才抓得到
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "link/escaped.txt")
        }
        assertFalse(
            "junction 目标目录里不能出现文件",
            File(outside, "escaped.txt").exists()
        )

        // (b) 目标已存在：整条路径都能 toRealPath()，同样必须被拒
        val already = File(outside, "already.txt")
        already.writeText("原文件", StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "link/already.txt")
        }
        assertEquals("原文件", already.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun junctionPointingAtASamePrefixSiblingIsRejected() {
        val root = newRoot()
        // 同前缀兄弟目录：字符串上 `output-evil` 以 `output` 开头。
        // 这正是「不能用裸字符串前缀判定」的场景，而且链接目标就是它。
        val sibling = temporaryFolder.newFolder("output-evil")
        val link = File(root, "link")
        createJunctionOrFail(link, sibling)

        val lexical = File(root, "link/x.txt").path
        assertTrue(
            "前提：裸字符串前缀判定确实会被同前缀兄弟目录骗过（实测 t06-out.txt）",
            lexical.startsWith(root.path)
        )
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "link/x.txt")
        }
    }

    @Test
    fun containmentDoesNotUseRawStringPrefixComparison() {
        val root = newRoot()
        val sibling = temporaryFolder.newFolder("output-evil")
        assertTrue(
            "前提：`output-evil/x.txt` 与 `output` 是字符串前缀关系",
            File(sibling, "x.txt").path.startsWith(root.path)
        )
        assertFalse(
            "Path.startsWith 按路径段比较，不会把 output-evil 当成 output 的子路径",
            File(sibling, "x.txt").toPath().normalize().startsWith(root.toPath().normalize())
        )
        // 从 root 到兄弟目录的唯一入口就是 `..`，语法闸门先挡住。
        assertThrows(IllegalArgumentException::class.java) {
            SafeArchivePaths.resolveInside(root, "../output-evil/x.txt")
        }
    }

    // ------------------------------------------------------------------
    // 4. 语法闸门本身（入口 4 只读扫描用的就是它）
    // ------------------------------------------------------------------

    @Test
    fun syntaxGateAcceptsEverydayEntryNames() {
        val accepted = listOf(
            "atlas.png",
            "角色/皮肤/data.txt",
            "a/b/c/d.bin",
            "char_004091.skel",
            "#prefab_1",
            "data.txt.old"
        )
        for (name in accepted) {
            assertTrue("$name 应当被接受", SafeArchivePaths.isSafeEntryName(name))
        }
    }

    @Test
    fun syntaxGateRejectsEveryEscapingFormWithAReason() {
        val rejected = listOf(
            "../outside.txt",
            "nested/../../outside.txt",
            "..",
            "a/../../b.txt",
            "/outside.txt",
            "\\outside.txt",
            "..\\outside.txt",
            "a\\b.txt",
            "C:/outside.txt",
            "C:\\outside.txt",
            "c:outside.txt",
            "//server/share/e.txt",
            "\\\\server\\share\\e.txt",
            "",
            "a\u0000b"
        )
        for (name in rejected) {
            val reason = SafeArchivePaths.entryNameRejectionReason(name)
            assertNotNull(
                "必须给出拒绝原因：${SafeArchivePaths.describeForLog(name)}",
                reason
            )
            assertTrue(
                "原因不能是空白：${SafeArchivePaths.describeForLog(name)}",
                reason!!.isNotBlank()
            )
            assertFalse(
                "isSafeEntryName 必须与原因一致：${SafeArchivePaths.describeForLog(name)}",
                SafeArchivePaths.isSafeEntryName(name)
            )
        }
    }

    @Test
    fun rejectionReasonNamesTheOffendingConstruct() {
        // 日志里要能看出「为什么被拒」，不能只有一句「不安全」。
        assertEquals(
            "条目名含父目录回退段 '..'",
            SafeArchivePaths.entryNameRejectionReason("../outside.txt")
        )
        assertTrue(
            SafeArchivePaths.entryNameRejectionReason("C:/outside.txt")!!.contains("盘符")
        )
        assertTrue(
            SafeArchivePaths.entryNameRejectionReason("a\\b.txt")!!.contains("反斜杠")
        )
        assertTrue(
            SafeArchivePaths.entryNameRejectionReason("")!!.contains("为空")
        )
    }

    @Test
    fun describeForLogNeutralisesControlCharacters() {
        // 条目名是外部输入：带换行的名字不能伪造日志行。
        assertFalse(SafeArchivePaths.describeForLog("a\nb").contains('\n'))
        assertFalse(SafeArchivePaths.describeForLog("a\rb").contains('\r'))
        assertFalse(SafeArchivePaths.describeForLog("a\u0000b").contains('\u0000'))
        // 正常名字原样回显
        assertEquals("'atlas.png'", SafeArchivePaths.describeForLog("atlas.png"))
    }

    // ------------------------------------------------------------------
    // 5. 端到端：被拒的名字不会在 root 之外留下任何东西
    // ------------------------------------------------------------------

    @Test
    fun noRejectedNameEverCreatesAnythingOutsideRoot() {
        val root = newRoot()
        val before = root.parentFile.listFiles()!!.map { it.name }.toSortedSet()

        val malicious = listOf(
            "../outside.txt",
            "nested/../../outside.txt",
            "..",
            "a/../../b.txt",
            "/outside.txt",
            "\\outside.txt",
            "..\\outside.txt",
            "C:/outside.txt",
            "C:\\outside.txt",
            "//server/share/e.txt",
            "\\\\server\\share\\e.txt"
        )
        for (name in malicious) {
            try {
                // 旧写法等价物：resolveInside 若放行，就照 extractModInto 的方式写下去。
                val out = SafeArchivePaths.resolveInside(root, name)
                out.parentFile?.mkdirs()
                out.writeText("should never happen", StandardCharsets.UTF_8)
            } catch (expected: IllegalArgumentException) {
                // 期望路径
            }
        }

        val after = root.parentFile.listFiles()!!.map { it.name }.toSortedSet()
        assertEquals("解压根之外不得多出任何东西", before, after)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun newRoot(): File = temporaryFolder.newFolder("output")

    /**
     * 在 [link] 处建一个指向 [target] 的 NTFS junction。
     *
     * **不能用 `Files.createSymbolicLink`**：实测宿主上它抛
     * `FileSystemException: 客户端没有所需的特权`（需要开发者模式/管理员），
     * 而 junction（`mklink /J`）实测无需管理员。
     *
     * 建不出来就**直接失败**（不是 skip）—— 「skip 不算通过」是本轮的硬规则，
     * 而且这里建不出来意味着这条验收根本没被执行。
     */
    private fun createJunctionOrFail(link: File, target: File) {
        val process = ProcessBuilder(
            "cmd", "/c", "mklink", "/J", link.absolutePath, target.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0 || !link.exists()) {
            throw AssertionError(
                "无法创建 junction（exit=$exit）：$output —— " +
                    "这条用例覆盖「符号链接逃逸」，建不出链接就等于没测，故判失败而不是 skip"
            )
        }
    }
}
