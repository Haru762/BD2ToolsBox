package com.bd2toolsbox.service

import com.bd2toolsbox.data.repository.PreviewCacheRepository

/**
 * 预解包产物落盘的三选一判定：合并进缓存（[Decision.COMMIT]）、记负缓存
 * （[Decision.MARK_NO_PREVIEW]）、丢弃（[Decision.DISCARD]）。
 *
 * **为什么单独抽出来**：这段判定原先内联在 [PrepackService.run] 里，而
 * `PrepackService` 是 Android `Service` —— JVM 单测没法实例化它，于是「预解包完成后
 * 缓存里到底留下什么」这条最关键的链路一条真实断言都没有（原来的
 * `PrepackCacheIntegrationTest` 断的是 180 < 800 这种写死常量的大小关系）。
 * 抽成纯 Kotlin 对象后，判定与落盘都能用**真实** [PreviewCacheRepository] +
 * 真实文件覆盖，Service 只负责把产物目录喂进来。
 *
 * 判定必须与 [PreviewCacheRepository.isValid] 完全一致（skel/json + atlas + size），
 * 否则会出现「commit 了但永远 isValid=false」的产物：每轮预解包都重解一遍，
 * 用户看到的就是进度条卡在列表开头那二十几个包上不动。历史缺陷是这里只查了
 * skel —— 无 atlas 的产物被当成成功合并，却永远不算有效命中。
 *
 * 反方向也不能错：解包**成功但没有预览载荷**（立绘/UI 类）必须记负缓存，
 * 否则它们同样每轮重解；解包**失败**则绝不能记负缓存 —— 失败可能是磁盘满或
 * 进程被杀这类临时原因，记了就没重试机会了。
 */
object PrepackCommitPolicy {

    enum class Decision {
        /** 产物完整，合并进缓存。 */
        COMMIT,

        /** 解包成功但产物没有预览价值，记负缓存让下一轮直接跳过。 */
        MARK_NO_PREVIEW,

        /** 解包失败或中途出错：清掉半成品，不记负缓存，留重试机会。 */
        DISCARD
    }

    private val SKEL_SUFFIXES = listOf(".skel", ".json")

    private fun String.hasSuffixIn(suffixes: List<String>): Boolean =
        suffixes.any { endsWith(it, ignoreCase = true) }

    /** 是否有骨架（`.skel` 或 `.json`，两都是 Unity 文本/二进制骨架）。 */
    fun hasSkeleton(fileNames: Collection<String>): Boolean =
        fileNames.any { it.hasSuffixIn(SKEL_SUFFIXES) }

    /** 是否有图集。少了它只有骨架也渲染不出任何东西。 */
    fun hasAtlas(fileNames: Collection<String>): Boolean =
        fileNames.any { it.endsWith(".atlas", ignoreCase = true) }

    /** 产物能否直接用于预览 —— 与 `PreviewCacheRepository.isValid` 的载荷条件一致。 */
    fun isPreviewable(fileNames: Collection<String>): Boolean =
        hasSkeleton(fileNames) && hasAtlas(fileNames)

    /**
     * 判定该把这一项怎么处理。
     *
     * [srcSize] 是源产物的字节数，**参与缓存路径**（`<hashDir>_<size>`）：同一个 bundle
     * 常有多个不同内容的变体拷贝，size 是区分它们的键。拿不到 size（<=0）时无法分槽，
     * 各变体会挤进同一个目录互相覆盖，所以一律不合并、改记负缓存 —— 负缓存同样带
     * `.srcsize` 校验，等真拿到 size 后槽名变了，旧标记自动失效，不会误伤。
     */
    fun decide(unpackOk: Boolean, srcSize: Long, fileNames: Collection<String>): Decision = when {
        !unpackOk -> Decision.DISCARD
        srcSize > 0 && isPreviewable(fileNames) -> Decision.COMMIT
        else -> Decision.MARK_NO_PREVIEW
    }

    /**
     * 判定落地。解包目录已经由 `prepareDir` 清空过，这里只补上/撤掉缓存标记。
     * 记负缓存前先 `delete`，免得半成品文件残留下来被后续统计（体积/膨胀比）算进去。
     */
    fun apply(
        cache: PreviewCacheRepository,
        bundleName: String,
        hashDir: String,
        srcSize: Long,
        decision: Decision
    ) {
        when (decision) {
            Decision.COMMIT -> cache.commit(bundleName, hashDir, srcSize)
            Decision.MARK_NO_PREVIEW -> {
                cache.delete(bundleName, hashDir, srcSize)
                cache.markNoPreview(bundleName, hashDir, srcSize)
            }
            Decision.DISCARD -> cache.delete(bundleName, hashDir, srcSize)
        }
    }
}
