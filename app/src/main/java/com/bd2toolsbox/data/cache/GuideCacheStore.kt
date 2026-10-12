package com.bd2toolsbox.data.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 攻略磁盘缓存的位置与读写（T30）。
 *
 * 从 1415 行的 `GamekeeRepository` 里拆出来：那里同时装着 HTTP、缓存、解析、渲染与
 * 编排。拆出之后「缓存放哪、怎么写才不留半份、怎么统计与清空」只有一处定义，
 * 编排侧不再散落 `File(cacheDir, …)`。
 *
 * **为什么放在 `data/cache` 而不是计划写的 `feature/guides/data`**：调用方
 * `GamekeeRepository` 在 `data/repository`，分层规则（R2）禁止 `data → feature`。
 * 计划的那两个路径写于重构前，与实际分层不一致，这里按规则落位。
 *
 * 行为与拆出前**逐行一致**：同样的目录名（`guides/`、`articles/`、`thumbs/`、
 * `battles/`）、同样的 `.part` 原子写、同样的「`.part` 不计入占用」判据。
 */
@Singleton
class GuideCacheStore @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {

    /** 攻略缓存的根。优先外部缓存目录（占用大、不占内部存储配额）。 */
    val root: File
        get() = File(appContext.externalCacheDir ?: appContext.cacheDir, DIR_GUIDES)

    val treeFile: File get() = File(root, "tree.json")
    val articleDir: File get() = File(root, DIR_ARTICLES)
    val thumbDir: File get() = File(root, DIR_THUMBS)
    val battleDir: File get() = File(root, DIR_BATTLES)
    val emojiFile: File get() = File(root, "emoji.json")

    fun articleFile(id: Long): File = File(articleDir, "$id.html")
    fun battleFile(key: String): File = File(battleDir, "$key.html")
    fun thumbFile(name: String): File = File(thumbDir, name)

    /**
     * 原子写：先写同目录的 `<name>.part` 再改名。
     *
     * 失败（写不进/改名失败）由调用方决定怎么办 —— 拆出前各处都是「缓存写不进去
     * 不影响本次使用」，所以这里把异常吞掉并返回 false，不改变既有行为。
     */
    fun writeAtomic(target: File, body: String): Boolean = try {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + PART_SUFFIX)
        tmp.writeText(body)
        tmp.renameTo(target)
    } catch (_: Exception) {
        false
    }

    /**
     * `guides/` 的占用（字节）与文件数，给攻略页的清空入口显示。
     *
     * `.part` 是写到一半的残片，不算进占用 —— 与拆出前一致。
     */
    fun usage(): Pair<Long, Int> {
        val files = root.walkTopDown().filter { it.isFile }
            .filterNot { it.name.endsWith(PART_SUFFIX) }.toList()
        return files.sumOf { it.length() } to files.size
    }

    /** 清空整个攻略缓存目录。调用方负责一并清掉各自的内存缓存。 */
    fun clear() {
        root.deleteRecursively()
    }

    private companion object {
        const val DIR_GUIDES = "guides"
        const val DIR_ARTICLES = "articles"
        const val DIR_THUMBS = "thumbs"
        const val DIR_BATTLES = "battles"
        const val PART_SUFFIX = ".part"
    }
}
