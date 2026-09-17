package com.bd2toolsbox.core.fileaccess

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * 同目录暂存 → 校验 → 原子替换（T07）。
 *
 * 本类解决的是 `ShizukuFileService.copyStream` 的旧写法：
 *
 * ```kotlin
 * dest.outputStream().use { output -> input.copyTo(output) }   // 打开 dest 即截断最终 __data
 * ```
 *
 * 拷贝中途失败（磁盘满 / 源读到一半出错 / 服务进程被杀）会留下一个**半截的
 * `__data`**，而旧的 `copyFile` / `copyDirectory` 只把异常吞成 `false` ——
 * 「报错了」并不等于「原文件还在」。对游戏目录来说，那份半截文件会让游戏在读
 * bundle 时崩，且原版已经被截断、找不回来了。
 *
 * ## 提交顺序（三步，中间任何一步失败都不改动目标）
 *
 * 1. 在 `target` 的**同目录**建一个独占暂存件，由 [write] 把全部字节写进去。
 *    同目录是硬要求：跨目录改名在某些卷上会退化成「复制 + 删除」，原子性就没了。
 * 2. [verifyBeforeCommit] 在**替换之前**拿到暂存件（不是目标），由调用方做校验。
 * 3. 原子替换目标。
 *
 * 失败时**原样抛出**调用方自己的异常（不包成自定义类型、不吞），`target` 保持旧内容。
 * 文件系统不支持原子替换时**安全失败**（[IOException]），**不**退化成
 * 「删掉目标再改名」或「直接覆盖最终文件」—— 那正是本任务要消灭的形态。
 *
 * ## 本类不知道「批」的存在
 *
 * 一次 [write] = 一次单文件提交。目录级复制是 N 次独立提交，第 k 次失败时前 k-1 次
 * **已经提交**了。这是真实存在的「批次部分成功」状态，调用方必须按目标分别上报，
 * **不许**把它说成整批事务。
 *
 * ## 为什么校验回调拿到的是文件而不是字节
 *
 * `__data` 单个 bundle 是 26 MB 级（源码里三处独立注释记的实测值，见 `T07.md` §2.4）。
 * 把整份读进内存不是选项，所以校验接口给的是**暂存件本身**，调用方自己决定用
 * `length()` / 读头部若干字节来判。本类自己不做格式校验 —— 见 `T07.md` §3.4。
 *
 * ## 不 import 任何 `android.*`
 *
 * `core/fileaccess` 整包的约定，且因此能在 JVM 单测与设备 instrumentation 里直接跑。
 * 目标路径之所以能在这两个环境都对，是因为本类只用 `java.io` + `java.nio.file`。
 */
class AtomicFileWriter {

    /**
     * 把 [write] 产出的字节原子地提交到 [target]。
     *
     * @param target 最终文件。其父目录不存在时会被创建。
     * @param verifyBeforeCommit 替换**之前**在暂存件上执行的校验；校验不通过就抛异常，
     *   异常原样向上传播，目标保持旧内容。
     * @param write 往暂存件写字节。抛出的任何异常原样向上传播。
     * @throws IOException 暂存件建不出来、写失败、父目录建不出来、文件系统不支持原子替换。
     */
    fun write(
        target: File,
        verifyBeforeCommit: (File) -> Unit = {},
        write: (OutputStream) -> Unit
    ) {
        val dir = target.parentFile
            ?: throw IOException("目标没有父目录，无法在同目录建暂存件：${target.path}")
        // 父目录必须先建：实测 createTempFile 在父目录不存在时直接抛 IOException
        // （宿主 JDK 17 复算见 T07.md §6.0 的 F 行）。
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建目标父目录：${dir.path}")
        }

        val staged = File.createTempFile(STAGING_PREFIX, STAGING_SUFFIX, dir)
        var committed = false
        try {
            FileOutputStream(staged).use { out ->
                write(out)
                out.flush()
                syncIfSupported(out)
            }
            verifyBeforeCommit(staged)
            preservePermissions(target, staged)
            moveIntoPlace(staged, target)
            committed = true
        } finally {
            // 只清理**本次**创建的那个暂存件；不扫目录、不碰别人留下的东西。
            // 进程被杀（Shizuku 服务被系统回收、destroy() 的 System.exit）时这段不会执行，
            // 那种残留靠 [isStagingName] / [listStagingFiles] 的固定命名定位 —— 见类注释。
            if (!committed) {
                staged.delete()
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 「可支持的同步」：尽力 fsync，不支持就降级放行。
     *
     * 计划落实要求第 1 条写的是「可支持的同步」。宿主 NTFS 上 `fd.sync()` 实测成功
     * （T07.md §6.0 的 G 行），但**部分 FUSE / 容器挂载上不支持**，那里会抛
     * [SyncFailedException]。
     *
     * JDK 不为这个异常区分「平台不支持」与「同步真的失败」（都叫 `SyncFailedException`），
     * 所以这里只能整体降级。降级的后果是**有界的**：本次提交的可见性由后面的原子替换
     * 保证，fsync 只影响「掉电后新内容还在不在」—— 不会产生半截文件。
     *
     * **本分支的设备实测状态以 `T07.md` §7 为准**，不在代码里写推测的设备行为。
     */
    private fun syncIfSupported(out: FileOutputStream) {
        try {
            out.fd.sync()
        } catch (_: SyncFailedException) {
            // 平台不支持 fsync：降级放行，见上面的说明
        } catch (_: UnsupportedOperationException) {
            // 部分实现（含某些 Java 运行时桥接）直接抛未受检异常
        }
    }

    /**
     * 把 [target] 的权限照搬到暂存件上，再让它去替换。
     *
     * **为什么必须有这一步（不是锦上添花）**：`File.createTempFile` 建出来的文件
     * 带着一批**只给属主/属组**的权限。游戏目录里的 `__data` 是要被**游戏进程**读的，
     * 而这份文件是 shell UID（Shizuku）写进去的。若把那种权限的暂存件直接改名成
     * `__data`，游戏可能整份读不到 —— 那是比「拷贝失败」更糟的静默回归。
     *
     * 具体权限位是**实测**的（不是从 POSIX 常识推的，两端还不一样）：
     *   - 设备 FUSE（`/storage/emulated/0/Android/data/com.bd2toolsbox/files/`，
     *     见 `T07.md` §5.3）：新建文件是 **`rw-rw----`** —— other 位**没有**读权限；
     *     而该卷上**支持** `setPosixFilePermissions`（设为 `rw-r--r--` 后读回一致）。
     *     所以这一步在设备上是**活的防线**。
     *   - 宿主 NTFS：没有 POSIX 权限概念，`get/setPosixFilePermissions` 抛
     *     `UnsupportedOperationException`，走下面的跳过分支。
     *
     * 目标已存在时照搬它自己的权限，不猜。目标不存在（新建）时**优先照抄同目录里已有
     * 文件的权限**，该目录没有任何普通文件可参考时才退回父目录的 rw 位
     * （见 [newFilePermissions]）—— 两种都不是硬编码一个值。
     *
     * **为什么不吞掉 `IOException`**：设不了权限就提交失败、目标不变 ——
     * 让「文件装进去了但游戏读不到」变成一次可见的失败，而不是一件静默发生的坏事。
     * 只有**平台根本没有 POSIX 权限概念**（`UnsupportedOperationException`）才跳过：
     * 那里没有权限可保，跳过不等于丢东西。
     */
    private fun preservePermissions(target: File, staged: File) {
        try {
            val permissions: Set<PosixFilePermission> =
                if (target.isFile) Files.getPosixFilePermissions(target.toPath())
                else newFilePermissions(target)
            Files.setPosixFilePermissions(staged.toPath(), permissions)
        } catch (_: UnsupportedOperationException) {
            // 文件系统没有 POSIX 权限概念（宿主 Windows/NTFS）：没有权限可保，不是失败
        }
    }

    /**
     * **目标此前不存在**时新文件该拿什么权限。
     *
     * ## 规则（T07 三轮整改，编排者按用户裁定选定的 (b)）
     *
     * 1. **同目录里已经有普通文件** → 照抄**其中一个**的 mode（挑选规则见下）。
     *    这就是「与同目录邻居一致」这条要求的直接落实：新建的那份 `__data` 与同一
     *    目录里已经躺着的 `__data` / `__info` 拿到**完全相同**的权限位。
     * 2. **该目录里没有任何普通文件可参考**（目录不存在、读不出、或只含子目录）→
     *    退回**父目录**的权限位、**屏蔽执行位**（[parentDirectoryFilePermissions]）。
     *
     * ## 为什么不是「取父目录 rw 位」一条规则走到底（三轮更正了依据）
     *
     * 二轮实现的是「取父目录 rw 位、屏蔽执行位」，依据写的是「真实游戏目录是
     * `drwxrws---`（2770）⇒ 新建得到 `rw-rw----`（0660），与邻居一致」。
     * **那个依据是错的**（二轮独立复核在设备上实测，三轮复测确认）：
     *
     * - 真正存放 `__data` 的目录是 **`drwxr-s---`（2750）**，不是 2770 ——
     *   `Shared/` 下 **1830 个 hash 目录全部是 2750、0 个 2770**；
     * - 2770 只出现在**游戏根**与 `files/` 这一类**不含 `__data`** 的目录
     *   （全树 3689 个目录里 2770 只有 23 个）；
     * - 所以「取父目录 rw 位」在真实路径上得到的是 **`rw-r-----`（0640）**，
     *   而真实 `__data` 是 **`rw-rw----`（0660）** —— group 位**少了写权限**，
     *   新建的那份会成为同目录里**唯一** group 不可写的文件。**与邻居不一致。**
     * - 新规则走第 1 条（同目录优先），在真实路径上得到 **0660**，因为那个目录里
     *   本来就有 `__info` 等普通文件可抄 —— *这是「该目录有普通文件时」的结果*；
     *   目录为空时仍会退到第 2 条、得到 0640（那种场景不在真实游戏路径上）。
     *
     * 两条分支的**取舍**：第 1 条是「与邻居一致」这条意图的正解，第 2 条是它
     * 做不到时的**有界回退**（至少不比「随手一个硬编码值」差，且仍由父目录决定）。
     *
     * ## 为什么屏蔽执行位而不是直接照抄目录权限
     *
     * 目录必须带执行位才可进入，文件带执行位则是另一回事（脚本 / 可执行 ELF）。
     * `__data` 是数据文件，给它执行位语义上错、且会给人「这是程序」的错觉。
     * 屏蔽掉正好把「目录的 rw 位」（`dirMode and 0b110110110`）翻译成
     * 「同权限组的普通文件权限」—— 见 [maskExecutableBits]。
     * （第 1 条不存在这个问题：抄的是普通文件，本来就不该带执行位。）
     *
     * **父目录取不到时**（理论上不会：目标都有父目录）抛 `IOException`，按
     * 「设不了权限就失败」处理（与 [preservePermissions] 的取向一致：可见的失败
     * 好过静默的错权限）。**不再硬编码 0644** —— 那正是二轮整改要去掉的东西。
     */
    private fun newFilePermissions(target: File): Set<PosixFilePermission> {
        referenceFileForNewTarget(target.parentFile)?.let { reference ->
            // getPosixFilePermissions 在无 POSIX 概念的卷上抛 UnsupportedOperationException，
            // 由 preservePermissions 接住并跳过 —— 这里不重复判断平台能力。
            return Files.getPosixFilePermissions(reference.toPath())
        }
        return parentDirectoryFilePermissions(target)
    }

    /**
     * 在 [dir] 里挑一个**已有的普通文件**当新建目标的权限参考；没有可参考的返回 `null`。
     *
     * ## 挑选规则必须是确定的
     *
     * **按文件名升序取第一个普通文件**（`sortedBy { it.name }` 的自然序）。
     * 刻意不用 `listFiles()[0]` —— 那个顺序由文件系统给定，同一份输入在不同卷 /
     * 不同时刻可能给出不同结果，权限值会跟着飘，用例也就没法断言。名字序是纯函数的、
     * 与实现无关的顺序。
     *
     * ## 只认普通文件（`isDirectory == false`）
     *
     * 目录不是参考对象：它带着执行位，而我们要的正是**普通文件**的权限形态。
     * 一个只含子目录的目录因此算「没有可参考的」→ 调用方退回父目录规则。
     *
     * ## 必须排除暂存件自己
     *
     * [write] 是**先**在这个目录里 `createTempFile` 建出暂存件、**再**调
     * [preservePermissions] 的，所以此刻 `listFiles()` 里**一定有**我们自己那个
     * `.bd2staged-….tmp`。点前缀在文件名序里排在字母数字之前 —— 不排除的话，
     * 它会稳定地成为「第一个」，我们就把**暂存件自己的权限**抄给了新文件，
     * 那正是这一步要避免的形态（暂存件带的是兄弟目录里 `createTempFile` 的默认值）。
     *
     * 目录读不出来（权限不足 / 已被删）时 `listFiles()` 返回 `null`，同样返回 `null`。
     */
    private fun referenceFileForNewTarget(dir: File?): File? {
        if (dir == null || !dir.isDirectory) return null
        return selectReferenceFile(dir.listFiles()?.toList() ?: emptyList())
    }

    /**
     * **无父目录可用时**的最后回退：取父目录的权限位、屏蔽执行位。
     *
     * 只在 [newFilePermissions] 的第 1 条够不着时走到这里（同目录里一个普通文件都没有）。
     * 在真实游戏路径上不会走到 —— 每个 hash 目录里都有 `__data` / `__info`。
     */
    private fun parentDirectoryFilePermissions(target: File): Set<PosixFilePermission> {
        val dir = target.parentFile
            ?: throw IOException("目标没有父目录，无法继承权限位：${target.path}")
        val dirPermissions = Files.getPosixFilePermissions(dir.toPath())
        return maskExecutableBits(dirPermissions)
    }

    /**
     * 原子替换。
     *
     * 两个选项都**显式**写出来。只给 `ATOMIC_MOVE` 不给 `REPLACE_EXISTING` 在宿主上
     * 也能成功替换（JDK 在 Windows 上实现 `ATOMIC_MOVE` 时隐含了 replace 语义，
     * 实测见 T07.md §6.0 的 C2 行）—— 那是个陷阱：POSIX `rename(2)` 本来就替换，
     * 两个平台上的意图差异会咬人。显式写出来，行为不依赖实现细节。
     */
    private fun moveIntoPlace(staged: File, target: File) {
        try {
            Files.move(
                staged.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // 计划落实要求第 1 条：文件系统不支持所需提交语义时**安全失败**。
            // 这里刻意不退化成「删掉目标再改名」或「直接覆盖最终文件」：
            // 前者会短暂地让目标不存在，后者正是本任务要消灭的半途复制形态。
            // 此刻目标仍是旧内容，暂存件由 write 的 finally 清掉。
            throw IOException(
                "文件系统不支持原子替换（ATOMIC_MOVE），已安全失败且未改动目标：${target.path}",
                e
            )
        }
    }

    companion object {
        /**
         * 暂存件前缀。以固定标记开头，任何进程扫一眼目录就能认出「这是本写入器留下的
         * 半成品」，从而安全清理 —— 这是验收「暂存文件可识别清理」要的那一半。
         *
         * 点前缀在 POSIX 上是隐藏文件，在 Windows 上**不是**（Windows 看文件属性位）。
         * 所以这里的作用是**可识别**，不是隐藏 —— 别把两者混为一谈。
         */
        const val STAGING_PREFIX = ".bd2staged-"

        /** 暂存件后缀。 */
        const val STAGING_SUFFIX = ".tmp"

        /**
         * 「目录权限 → 同组普通文件权限」：三个 rw 位保留（`0b110110110`），执行位去掉。
         *
         * 只在 [newFilePermissions] 的第 2 条回退路径上用得到（同目录里一个普通文件
         * 都没有时）。真实游戏路径**不走这里** —— `Shared/<bundle>/<hash>/` 里总是
         * 已有 `__data` / `__info`，走的是「照抄同目录文件」的第 1 条。
         *
         * 真实游戏目录里存放 `__data` 的那一层是 `drwxr-s---`（**2750**），
         * 不是 `drwxrws---`（2770）—— 所以这条回退在真实路径上会给出 `rw-r-----`
         * （0640），**与邻居 `__data`（`rw-rw----` / 0660）的 group 写位不同**。
         * 二轮曾把这个值写成 0660，那是拿游戏根（2770）当目标的父目录测出来的，
         * 见 `T07.md` §5.3 的实测更正。
         */
        private val EXECUTE_BITS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE
        )

        /**
         * 去掉 [permissions] 里的执行位 —— 把目录权限翻译成同组普通文件权限。
         *
         * 目录必须带执行位才可进入，文件带执行位则是另一回事，所以这一步是
         * 「继承父目录权限」这件事里唯一需要做的翻译。
         *
         * 用 `filterNot` 而不是 `EnumSet.copyOf`：后者在「空集合且不是 `EnumSet`」时
         * 抛 `IllegalArgumentException`，而权限位全空的目录（mode 000）正好会给出
         * 一个空集合 —— 那不该变成一次提交失败。
         */
        fun maskExecutableBits(permissions: Set<PosixFilePermission>): Set<PosixFilePermission> =
            permissions.filterNot { it in EXECUTE_BITS }.toSet()

        /**
         * 从一列候选中挑出**权限参考文件**：按文件名升序取第一个普通文件，
         * 跳过暂存件；没有可参考的返回 `null`。
         *
         * 抽成伴生对象里的纯函数（只看 `name` / `isFile`，不碰文件系统）是为了让
         * **挑选规则本身**能在 JVM 单测里被独立钉住 —— 它决定新建目标抄谁的权限，
         * 一个「看着在守、实际随文件系统顺序飘」的挑选会让权限值不可复现。
         *
         * 挑选规则（**确定性**，与文件系统返回顺序无关）：
         *   1. 只保留 `isFile`（目录不是参考对象：它带执行位）；
         *   2. 去掉暂存件（[isStagingName]）—— [AtomicFileWriter.write] 会先建暂存件，
         *      此刻它就在同一目录里，且点前缀会排在名字序最前，不排除就会抄到它；
         *   3. 按 `name` 升序取第一个。
         */
        fun selectReferenceFile(candidates: List<File>): File? =
            candidates
                .filter { it.isFile && !isStagingName(it.name) }
                .minByOrNull { it.name }

        /** [name] 是不是本写入器留下的暂存件名。 */
        fun isStagingName(name: String): Boolean =
            name.startsWith(STAGING_PREFIX) && name.endsWith(STAGING_SUFFIX)

        /**
         * [dir] 里所有可识别的暂存件残留。
         *
         * 用途是「进程被杀之后清理」：正常失败路径由 [write] 的 finally 自己删掉，
         * 只有服务进程被系统回收 / `destroy()` 直接 `System.exit(0)` 时才留得下来。
         */
        fun listStagingFiles(dir: File): List<File> =
            dir.listFiles()?.filter { isStagingName(it.name) } ?: emptyList()
    }
}
