package com.bd2toolsbox.core.fileaccess

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 归档条目名的语法闸门与落盘路径的包含性验证（T06）。
 *
 * 背景：`MainViewModel.extractModInto` 旧写法是
 * `val outFile = File(dest, entry.name)` —— 条目名直接当相对路径用。
 * 实测（Windows 宿主 / JDK 17，见 `docs/refactor/evidence/T06.md` §3）：
 * `../outside.txt` 与 `..\outside.txt` 都真的在解压根**之外**创建了文件。
 *
 * 本对象只做两件事，且**不 import 任何 `android.*`**（`core/fileaccess` 整包的约定）：
 *
 *  1. [entryNameRejectionReason] / [isSafeEntryName]：**纯语法**判定，不碰文件系统。
 *  2. [resolveInside]：语法判定 → 词法包含性 → 真实路径包含性，任一步不过就抛
 *     [IllegalArgumentException]。
 *
 * ## 为什么「先语法、后包含性」不能省（三条都是实测，不是推断）
 *
 * | 条目名 | 宿主 Windows 上 `new File(root, name)` | 说明 |
 * |---|---|---|
 * | `/outside.txt` | **落在 root 内**（`isAbsolute()==false`，因为无盘符） | Android(POSIX) 上它是绝对路径、会逃逸 —— 同一个 `File` 在两平台行为相反 |
 * | `C:/outside.txt` | `getCanonicalPath()` 抛 `java.io.IOException` | 契约要求的是 `IllegalArgumentException`，所以盘符必须在碰 `File` **之前**判掉 |
 * | `..\outside.txt` | **逃逸**（宿主上 `\` 是分隔符） | Android 上 `\` 只是普通字符。为不让同一函数在两端分叉，本实现**直接拒绝**含 `\` 的名字 |
 * | `output` vs `output-evil` | 裸字符串 `startsWith` 判定**误判为在内部** | 所以包含性用 `Path.startsWith`（按路径段比较），不用字符串前缀 |
 *
 * ## 为什么不能用 `File.getCanonicalFile()` 了事（实测）
 *
 * 宿主上 `getCanonicalPath()` **穿过 junction 不解析**（原样返回 `…\output\link\x.txt`），
 * 而 `Path.toRealPath()` 会解析（`…\output\link` → `…\outside`）。只靠 canonical 的实现
 * 在宿主 JVM 测试里会**假绿**。因此这里用 NIO，并且因为**待写入的目标通常还不存在**，
 * 只能对「最近的存在祖先」做 `toRealPath()`，再把不存在的尾巴按字面拼回去。
 *
 * ## 有意不做的事
 *
 * - **不引入任何资源限额／解压总量阈值**。`unpacker.py` 的 `MAX_BUNDLE_BYTES` 是
 *   另一层（单个 bundle 的解码门槛），与本层无关、本轮未动。
 * - **不做条目名改写**（不把 `\` 转成 `/`、不把 `..` 段消掉）。判定只有「接受」与
 *   「拒绝」两种结果，落盘路径与归档声明的名字一一对应，日志与文件能对上。
 */
object SafeArchivePaths {

    private const val NUL = '\u0000'

    /** 日志/异常里回显条目名时的截断长度。条目名来自归档，是外部输入。 */
    private const val MAX_ECHO_LENGTH = 200

    /**
     * 条目名的**语法**闸门。返回 `null` 表示可以接受，否则返回一句可直接进日志的中文原因。
     *
     * 判定与平台无关（不看 `File.separator`、不碰文件系统），所以宿主 JVM 测试与
     * Android 设备上跑的是同一套规则 —— 这条是刻意的：写「Android only」分支会让
     * 宿主测试与设备行为分叉，而 T06 的多数证据只能从宿主测试拿到。
     *
     * 拒绝面（逐条对应 `task-T06-implementer-brief.md` §3.1）：
     * 空名、NUL、前导 `/` 或 `\`、含 `\`、Windows 盘符前缀、任何 `..` 段。
     *
     * **含 `\` 一律拒绝，代价写在明处**：Android(POSIX) 上 `\` 是合法文件名字符，
     * 所以一个「文件名里真的带反斜杠」的归档条目会被这里挡掉。选拒绝而不是把 `\`
     * 当分隔符改写，是因为改写会让**归档声明的名字与实际落盘的名字不一致**
     * （`a\b.dat` 变成 `a/b.dat`，多出一个目录），而拒绝只会让这一条（或整个 mod，
     * 取决于调用方）失败、不会悄悄换位置。真实 MOD 归档的兼容性风险登记在 T06.md。
     */
    fun entryNameRejectionReason(entryName: String): String? {
        if (entryName.isEmpty()) return "条目名为空"
        if (entryName.indexOf(NUL) >= 0) return "条目名含 NUL 字符"
        if (entryName[0] == '/' || entryName[0] == '\\') {
            return "条目名以前导路径分隔符开头：在 Android(POSIX) 上这是绝对路径，会直接逃逸"
        }
        if (entryName.indexOf('\\') >= 0) {
            return "条目名含反斜杠：宿主 Windows 上它是路径分隔符（..\\..\\x 会逃逸），" +
                "Android 上只是普通字符；两端统一拒绝以免同一个函数行为分叉"
        }
        if (entryName.length >= 2 && entryName[1] == ':' && isAsciiLetter(entryName[0])) {
            return "条目名以 Windows 盘符开头（C:、c:\\ 等）"
        }
        if (entryName.split('/').any { it == ".." }) {
            return "条目名含父目录回退段 '..'"
        }
        return null
    }

    /** [entryNameRejectionReason] 的布尔形态。入口 4（只读扫描）用它，没有解压根可判。 */
    fun isSafeEntryName(entryName: String): Boolean =
        entryNameRejectionReason(entryName) == null

    /**
     * 把条目名解析成 [root] 内的一条落盘路径；任何越界（含经由符号链接/junction 的越界）
     * 都抛 [IllegalArgumentException]。
     *
     * 返回的是**词法规范化后**的路径（`sub/../inside.txt` 这类会被规范化，尽管本闸门
     * 实际会先拒绝任何含 `..` 的名字），它一定是 `root` 的直接或间接子路径。
     */
    fun resolveInside(root: File, entryName: String): File {
        val rejection = entryNameRejectionReason(entryName)
        if (rejection != null) {
            throw IllegalArgumentException(
                "拒绝归档条目 ${describeForLog(entryName)}：$rejection"
            )
        }

        val rootPath = root.absoluteFile.toPath().normalize()
        val candidate = try {
            rootPath.resolve(entryName).normalize()
        } catch (e: InvalidPathException) {
            // 例如 Windows 上以空格/点结尾的名字：NIO 直接说「无法表示」。
            // InvalidPathException 本身也是 IllegalArgumentException，这里再包一层
            // 只是为了给出中文原因、不改变契约要求的异常类型。
            throw IllegalArgumentException(
                "拒绝归档条目 ${describeForLog(entryName)}：本地文件系统无法表示该名字", e
            )
        }

        if (candidate == rootPath || !candidate.startsWith(rootPath)) {
            throw IllegalArgumentException(
                "拒绝归档条目 ${describeForLog(entryName)}：规范化后落在解压根 $rootPath 之外"
            )
        }

        val realRoot: Path
        val realCandidate: Path
        try {
            realRoot = realPathOf(rootPath)
            realCandidate = realPathOf(candidate)
        } catch (e: IOException) {
            // 断链 / 权限不足 / 环：解析不出真实路径就不放行。
            throw IllegalArgumentException(
                "拒绝归档条目 ${describeForLog(entryName)}：无法解析真实路径" +
                    "（可能是断链、链接环或权限不足）", e
            )
        }

        if (realCandidate == realRoot || !realCandidate.startsWith(realRoot)) {
            throw IllegalArgumentException(
                "拒绝归档条目 ${describeForLog(entryName)}：解析真实路径后落在解压根 " +
                    "$realRoot 之外（符号链接 / junction 逃逸）"
            )
        }

        return candidate.toFile()
    }

    /**
     * 回显外部输入（条目名）时的安全化：截断 + 把控制字符换成 `?`。
     * 目的是不让一个名字里带换行的归档条目伪造日志行。
     */
    fun describeForLog(entryName: String): String {
        val clipped = if (entryName.length > MAX_ECHO_LENGTH) {
            entryName.take(MAX_ECHO_LENGTH) + "…(已截断)"
        } else {
            entryName
        }
        val safe = StringBuilder(clipped.length + 2)
        safe.append('\'')
        for (c in clipped) {
            safe.append(if (c.code < 0x20 || c.code == 0x7F) '?' else c)
        }
        safe.append('\'')
        return safe.toString()
    }

    /**
     * [path] 的真实路径：对**最近的存在祖先**取 `toRealPath()`（它会解析 POSIX symlink
     * 与 Windows junction），再把尚不存在的尾巴按字面拼回去。
     *
     * 存在性用 `NOFOLLOW_LINKS` 判：这样断链本身也会被判为「存在」，随后 `toRealPath()`
     * 抛 `NoSuchFileException` → 调用方拒绝。若改用跟随语义，断链会被当成「不存在」而
     * 走上「祖先 + 字面尾巴」这条路，等于放行一条指向 root 之外的断链。
     */
    private fun realPathOf(path: Path): Path {
        var current: Path? = path
        val suffix = mutableListOf<String>()
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            val name = current.fileName ?: return path
            suffix.add(0, name.toString())
            current = current.parent
        }
        if (current == null) return path

        var real = current.toRealPath()
        for (name in suffix) real = real.resolve(name)
        return real
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'
}
