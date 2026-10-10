package com.bd2toolsbox.feature.mods

import com.bd2toolsbox.data.model.ModCategory
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.data.model.ResolutionState
import com.bd2toolsbox.data.model.categoryOf
import com.bd2toolsbox.data.model.isUnknownCharacter

/**
 * 二级：一个目标 bundle 下的所有 mod（同 bundle 组内互斥）。
 *
 * [totalInBundle] 是这个 bundle 在整份列表里被多少个 mod 指向（含其他角色）。
 * 大于 [mods] 的个数时说明是共用 bundle，界面上要说清。
 */
data class BundleGroup(
    val hash: String,
    val category: ModCategory,
    /** 二级标题显示的皮肤名。 */
    val costume: String,
    val mods: List<ModInfo>,
    val totalInBundle: Int
)

/** 一级：一个角色名下的所有 bundle 组。 */
data class CharacterGroup(
    val character: String,
    val isUnknown: Boolean,
    val groups: List<BundleGroup>
) {
    val total: Int get() = groups.sumOf { it.mods.size }
}

/**
 * MOD 列表的四区划分 + 角色分组（T28）。
 *
 * 四区口径（异常 / 待更新 / 未识别 / 正常）互斥且完备，基于**同一份**筛选后的列表，
 * 搜索命中某条异常 mod 时它留在顶部区而不是漏进正常分组。
 */
data class ModListSections(
    val abnormal: List<ModInfo>,
    val outdated: List<ModInfo>,
    val unknown: List<ModInfo>,
    val normal: List<ModInfo>,
    /** 未筛选列表里的正常条目，供状态 chip 计数。 */
    val normalAll: List<ModInfo>,
    val characterGroups: List<CharacterGroup>
)

/**
 * MOD 列表的纯投影（T28）：搜索、状态筛选、四区划分、角色分组、可勾选集合。
 *
 * 无 Compose、无 Android 依赖，可在 JVM 单测里直接验证；稳定 ID 用 `mod.uri.toString()`。
 */
object ModListProjection {

    /** 搜索（多关键词全命中）+ 安装状态筛选。 */
    fun filter(mods: List<ModInfo>, query: String, stateFilter: ModInstallState?): List<ModInfo> {
        val byState = if (stateFilter == null) mods else mods.filter { it.installState == stateFilter }
        if (query.isBlank()) return byState
        val keywords = query.split(" ").filter { it.isNotBlank() }
        return byState.filter { modInfo ->
            keywords.all { keyword ->
                modInfo.name.contains(keyword, ignoreCase = true) ||
                    modInfo.character.contains(keyword, ignoreCase = true) ||
                    modInfo.costume.contains(keyword, ignoreCase = true) ||
                    modInfo.type.contains(keyword, ignoreCase = true)
            }
        }
    }

    /** 四区划分；`filtered` 是搜索/状态筛选后的列表，`all` 是未筛选列表（仅用于 chip 计数）。 */
    fun sections(filtered: List<ModInfo>, all: List<ModInfo>): ModListSections {
        val outdated = filtered.filter { it.defect == null && it.outdatedCurrentHash != null }
        val abnormal = filtered.filter { it.defect != null }
        val unknown = filtered.filter {
            it.defect == null && it.outdatedCurrentHash == null && isUnknownCharacter(it.character)
        }
        val normal = filtered.filter {
            it.defect == null && it.outdatedCurrentHash == null && !isUnknownCharacter(it.character)
        }
        val normalAll = all.filter {
            it.defect == null && it.outdatedCurrentHash == null && !isUnknownCharacter(it.character)
        }
        return ModListSections(
            abnormal = abnormal,
            outdated = outdated,
            unknown = unknown,
            normal = normal,
            normalAll = normalAll,
            characterGroups = buildCharacterGroups(normal)
        )
    }

    /** 批量选择可用的条目：已知、无缺陷、不需更新、角色已识别。 */
    fun selectableUris(mods: List<ModInfo>): Set<String> = mods.filter {
        it.resolutionState == ResolutionState.KNOWN &&
            it.defect == null &&
            it.outdatedCurrentHash == null &&
            !isUnknownCharacter(it.character)
    }.map { it.uri.toString() }.toSet()

    /**
     * 把平铺的 mod 列表整理成「角色 → 目标 bundle」两级。
     *
     * 排序：角色按名字，未识别的一律垫底；角色内先按类别再按皮肤名。
     */
    fun buildCharacterGroups(mods: List<ModInfo>): List<CharacterGroup> {
        if (mods.isEmpty()) return emptyList()

        fun hashOf(m: ModInfo): String = when (m.resolutionState) {
            ResolutionState.KNOWN, ResolutionState.MISC -> m.targetHash ?: "未知"
            ResolutionState.UNKNOWN -> "未知"
            ResolutionState.INVALID -> "无效"
        }

        fun charOf(m: ModInfo): String =
            if (isUnknownCharacter(m.character)) "未识别" else m.character.trim()

        val bundleTotals = mods.groupingBy { hashOf(it) }.eachCount()

        val perCharacter = LinkedHashMap<String, MutableList<BundleGroup>>()
        for ((key, list) in mods.groupBy { charOf(it) to hashOf(it) }) {
            val (charName, hash) = key
            val rep = list.first()
            perCharacter.getOrPut(charName) { mutableListOf() }.add(
                BundleGroup(
                    hash = hash,
                    category = categoryOf(rep.type, rep.costume),
                    costume = rep.costume?.trim().orEmpty().ifBlank { hash },
                    mods = list,
                    totalInBundle = bundleTotals[hash] ?: list.size
                )
            )
        }

        return perCharacter.map { (name, groups) ->
            CharacterGroup(
                character = name,
                isUnknown = name == "未识别",
                groups = groups.sortedWith(
                    compareBy({ it.category.ordinal }, { it.costume.lowercase() })
                )
            )
        }.sortedWith(
            compareBy({ if (it.isUnknown) 1 else 0 }, { it.character.lowercase() })
        )
    }
}
