package com.bd2toolsbox.feature.preview

import com.bd2toolsbox.core.model.PrepackPlan
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModKind
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 一个待预解包目标（不含 Service/UI 依赖）。 */
data class PrepackTarget(
    val treeUri: String,
    val bundleName: String,
    val hashDir: String,
    val size: Long,
    val displayName: String
)

/**
 * 预解包的目标筛选与空间计划（T24）。
 *
 * 抽出的目的：原先「计划」（`preparePrepack`）与「实际目标」（`startPrepack`）
 * 各写一遍筛选条件，两者已经不一致 —— 计划只按 [PreviewCacheRepository.isValid]
 * 统计 `todo`，而实际目标还会跳过负缓存（`isKnownNoPreview`）。于是计划里
 * `todo` 把「解包成功但无预览价值」的产物也算成待办，用户看到的待办数偏大。
 * 现在两者都走 [selectTargets]，计划里的 `todo` 就是真正交给服务的目标数。
 *
 * 缓存 key 维度保持不变：`<bundleName>/<hashDir>_<size>` —— bundle 名是目录层、
 * hash 与源大小进槽名，SD/HD 与不同变体各自独立成槽（见 [PreviewCacheRepository]）。
 * 本类不读 UI、不拥有 scope、不启动 Service。
 */
@Singleton
class PrepackExecutor @Inject constructor(
    private val cache: PreviewCacheRepository
) {
    /** 已转换产物中带 hash 目录的那些。 */
    fun convertedBundles(mods: List<ModInfo>): List<ModInfo> =
        mods.filter { it.kind == ModKind.CONVERTED_BUNDLE && it.convertedHashDir != null }

    /**
     * 需要预解包的目标：有效缓存跳过；负缓存（解包成功但无 skel/atlas）也跳过 ——
     * 否则它们永远不满足 `isValid`，每轮预解包都从头重解。
     */
    fun selectTargets(mods: List<ModInfo>): List<PrepackTarget> {
        val out = ArrayList<PrepackTarget>()
        for (m in mods) {
            if (m.kind != ModKind.CONVERTED_BUNDLE) continue
            val name = m.targetHash ?: continue
            val hash = m.convertedHashDir ?: continue
            val size = m.convertedDataSize
            if (size > 0 && cache.isValid(name, hash, size)) continue
            if (size > 0 && cache.isKnownNoPreview(name, hash, size)) continue
            out.add(PrepackTarget(m.uri.toString(), name, hash, size, m.name))
        }
        return out
    }

    /**
     * 空间计划。`todo` 与 [selectTargets] 同源；`alreadyCached` 只数有效缓存，
     * 负缓存既不算缓存也不算待办（`alreadyCached + todo <= total`）。
     * 膨胀比优先用已有缓存实测值，无样本退回 1.6。
     */
    fun plan(mods: List<ModInfo>, freeBytes: Long): PrepackPlan {
        val converted = convertedBundles(mods)
        val targets = selectTargets(converted)
        var cached = 0
        for (m in converted) {
            val name = m.targetHash ?: continue
            val hash = m.convertedHashDir ?: continue
            val size = m.convertedDataSize
            if (size > 0 && cache.isValid(name, hash, size)) cached++
        }
        val measured = cache.measuredExpansionRatio()
        val ratio = measured ?: 1.6
        val todoBytes = targets.sumOf { it.size }
        return PrepackPlan(
            total = converted.size,
            alreadyCached = cached,
            todo = targets.size,
            estimatedBytes = (todoBytes * ratio).toLong(),
            freeBytes = freeBytes,
            ratioFromSamples = measured != null
        )
    }
}
