package com.bd2toolsbox.feature.mods

import android.net.Uri
import com.bd2toolsbox.data.model.ModCategory
import com.bd2toolsbox.data.model.ModDefect
import com.bd2toolsbox.data.model.ModInfo
import com.bd2toolsbox.data.model.ModInstallState
import com.bd2toolsbox.data.model.ResolutionState
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T28：MOD 列表纯投影（搜索/筛选/四区/分组/可勾选）的 JVM 验证。
 *
 * 类名沿用计划里的 `ModLibraryViewModelTest`；当前被测对象是抽出的
 * [ModListProjection]（真正的 Android `ModLibraryViewModel`/`ModRoute` 拆分尚未完成）。
 */
class ModLibraryViewModelTest {

    @After fun teardown() = unmockkAll()

    private fun mod(
        name: String,
        character: String = "Justia",
        costume: String = "Default",
        type: String = "idle",
        kind: com.bd2toolsbox.data.model.ModKind = com.bd2toolsbox.data.model.ModKind.PC_SOURCE,
        targetHash: String? = "bundle_$name",
        resolution: ResolutionState = ResolutionState.KNOWN,
        installState: ModInstallState = ModInstallState.NOT_INSTALLED,
        defect: ModDefect? = null,
        outdated: String? = null
    ): ModInfo {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://mods/$name"
        return ModInfo(
            name = name, character = character, costume = costume, type = type,
            isEnabled = true, uri = uri, targetHashedName = targetHash, isDirectory = false,
            resolutionState = resolution, targetHash = targetHash,
            installState = installState, kind = kind, defect = defect, outdatedCurrentHash = outdated
        )
    }

    // ---------------------------------------------------------------- 搜索 / 状态筛选

    @Test fun searchMatchesAllKeywordsAcrossFields() {
        val mods = listOf(
            mod("Justia swimsuit", character = "Justia", costume = "Swimsuit", type = "idle"),
            mod("Scheherazade dress", character = "Scheherazade", costume = "Dress", type = "cutscene")
        )
        assertEquals(1, ModListProjection.filter(mods, "justia swimsuit", null).size)
        assertEquals(1, ModListProjection.filter(mods, "dress", null).size)
        assertEquals(1, ModListProjection.filter(mods, "cutscene", null).size)
        assertTrue(ModListProjection.filter(mods, "no-such-thing", null).isEmpty())
    }

    @Test fun blankQueryKeepsEverything() {
        val mods = listOf(mod("a"), mod("b"))
        assertEquals(2, ModListProjection.filter(mods, "   ", null).size)
    }

    @Test fun stateFilterNarrowsBeforeSearch() {
        val mods = listOf(
            mod("a", installState = ModInstallState.INSTALLED),
            mod("b", installState = ModInstallState.NOT_INSTALLED)
        )
        val filtered = ModListProjection.filter(mods, "", ModInstallState.INSTALLED)
        assertEquals(listOf("a"), filtered.map { it.name })
    }

    // ---------------------------------------------------------------- 四区划分

    @Test fun sectionsAreMutuallyExclusiveAndComplete() {
        val abnormal = mod("abnormal", defect = ModDefect.TRUNCATED)
        val outdated = mod("outdated", outdated = "newhash")
        val unknown = mod("unknown", character = "未识别")
        val normal = mod("normal")
        val all = listOf(abnormal, outdated, unknown, normal)
        val s = ModListProjection.sections(all, all)
        assertEquals(listOf("abnormal"), s.abnormal.map { it.name })
        assertEquals(listOf("outdated"), s.outdated.map { it.name })
        assertEquals(listOf("unknown"), s.unknown.map { it.name })
        assertEquals(listOf("normal"), s.normal.map { it.name })
        val total = s.abnormal.size + s.outdated.size + s.unknown.size + s.normal.size
        assertEquals("四区必须完备", all.size, total)
    }

    @Test fun abnormalTakesPrecedenceOverOutdatedAndUnknown() {
        // 同时有 defect 和 outdated：只算异常，不能同时进两区。
        val both = mod("both", defect = ModDefect.TRUNCATED, outdated = "h")
        val s = ModListProjection.sections(listOf(both), listOf(both))
        assertEquals(1, s.abnormal.size)
        assertTrue(s.outdated.isEmpty())
        assertTrue(s.unknown.isEmpty())
        assertTrue(s.normal.isEmpty())
    }

    @Test fun normalAllComesFromUnfilteredListForChipCounts() {
        val installed = mod("installed", installState = ModInstallState.INSTALLED)
        val other = mod("other", installState = ModInstallState.NOT_INSTALLED)
        val filtered = ModListProjection.filter(listOf(installed, other), "", ModInstallState.INSTALLED)
        val s = ModListProjection.sections(filtered, listOf(installed, other))
        assertEquals(1, s.normal.size)          // 筛选后的正常条目
        assertEquals(2, s.normalAll.size)       // chip 计数用未筛选的
    }

    // ---------------------------------------------------------------- 角色分组

    @Test fun groupsByCharacterThenBundle() {
        val a1 = mod("a1", character = "Alice", targetHash = "bundleA")
        val a2 = mod("a2", character = "Alice", targetHash = "bundleA")
        val b1 = mod("b1", character = "Bob", targetHash = "bundleB")
        val groups = ModListProjection.buildCharacterGroups(listOf(a1, a2, b1))
        assertEquals(listOf("Alice", "Bob"), groups.map { it.character })
        assertEquals(2, groups.first { it.character == "Alice" }.total)
        assertEquals(1, groups.first { it.character == "Alice" }.groups.size)
    }

    @Test fun unknownCharacterGoesLast() {
        val unknown = mod("u", character = "未识别")
        val zed = mod("z", character = "Zed")
        val groups = ModListProjection.buildCharacterGroups(listOf(unknown, zed))
        assertEquals(listOf("Zed", "未识别"), groups.map { it.character })
        assertTrue(groups.last().isUnknown)
    }

    @Test fun sharedBundleCountsAcrossCharacters() {
        // 同一 bundle 被两个角色指向：各自组里的 totalInBundle 都是 2。
        val a = mod("a", character = "Alice", targetHash = "shared")
        val b = mod("b", character = "Bob", targetHash = "shared")
        val groups = ModListProjection.buildCharacterGroups(listOf(a, b))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.groups.single().totalInBundle == 2 })
    }

    @Test fun categoryOrderingIsCutsceneThenIllustThenOther() {
        val other = mod("o", type = "other", targetHash = "h1")
        val illust = mod("i", type = "idle", targetHash = "h2")
        val cutscene = mod("c", type = "cutscene", targetHash = "h3")
        val groups = ModListProjection.buildCharacterGroups(listOf(other, illust, cutscene))
        val cats = groups.single().groups.map { it.category }
        assertEquals(
            listOf(ModCategory.CUTSCENE, ModCategory.ILLUST, ModCategory.OTHER),
            cats
        )
    }

    @Test fun stableIdsAreUriStrings() {
        val a = mod("a")
        val groups = ModListProjection.buildCharacterGroups(listOf(a))
        assertEquals("content://mods/a", groups.single().groups.single().mods.single().uri.toString())
    }

    // ---------------------------------------------------------------- 可勾选集合

    @Test fun selectableExcludesAbnormalOutdatedUnknownAndNonKnown() {
        val ok = mod("ok")
        val defect = mod("defect", defect = ModDefect.TRUNCATED)
        val outdated = mod("outdated", outdated = "h")
        val unknown = mod("unknown", character = "未识别")
        val misc = mod("misc", resolution = ResolutionState.MISC)
        val selectable = ModListProjection.selectableUris(listOf(ok, defect, outdated, unknown, misc))
        assertEquals(setOf("content://mods/ok"), selectable)
    }

    @Test fun emptyListProducesEmptySectionsAndGroups() {
        val s = ModListProjection.sections(emptyList(), emptyList())
        assertTrue(s.abnormal.isEmpty() && s.outdated.isEmpty() && s.unknown.isEmpty())
        assertTrue(s.normal.isEmpty() && s.characterGroups.isEmpty())
    }
}
