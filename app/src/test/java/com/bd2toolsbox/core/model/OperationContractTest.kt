package com.bd2toolsbox.core.model

import com.bd2toolsbox.core.fileaccess.DirectoryEntry
import com.bd2toolsbox.core.fileaccess.DirectoryListingResult
import com.bd2toolsbox.core.fileaccess.DirectoryPage
import com.bd2toolsbox.core.fileaccess.DirectoryPageSource
import com.bd2toolsbox.core.fileaccess.FileAccessFailureSignal
import com.bd2toolsbox.core.fileaccess.PagedDirectoryLister
import com.bd2toolsbox.core.fileaccess.toError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * T05 的领域契约回归。
 *
 * 本类只驱动**纯**模型与纯函数（`core.model` + `core.fileaccess`），不碰
 * Android 对象、不需要夹具、也不需要 Robolectric —— 本仓没有 Robolectric，
 * 一旦模型里混进 `android.*`，这些用例会直接以 `Stub!` 崩掉（这也是最后
 * 那条反射扫描用例存在的意义）。
 *
 * 覆盖三件事：
 *   1. **目录访问**：成功（哪怕为空）与失败是**不同类型**，且失败的一页不是最后一页 ——
 *      这是实测过的真实缺陷（`ShizukuManager.listGameBundles` 把失败的一页当成最后一页，
 *      提交看似完整、实则截断的索引）。
 *   2. **错误分类**：权限拒绝 / Binder 断连 / 解析失败各有自己的类别与可重试性，
 *      不允许被统一塌成「空列表」或 `false`。
 *   3. **计划模型**：同 bundle 的多个 MOD 计划保留既有安装项；去重只按 uri，
 *      绝不按 familyKey；计划模型不依赖 UI 包与 Android 对象。
 */
class OperationContractTest {

    // ------------------------------------------------------------------
    // 1. 目录访问：成功为空 ≠ 失败
    // ------------------------------------------------------------------

    @Test
    fun successfulButEmptyListingIsSuccessWithNoEntries() {
        val result = PagedDirectoryLister(pageSize = 2)
            .list(ScriptedSource(listOf(DirectoryPage.Content(emptyList()))))

        assertTrue("读到了、且目录确实为空 —— 必须是 Success", result is DirectoryListingResult.Success)
        val success = result as DirectoryListingResult.Success
        assertEquals(0, success.entries.size)
        assertTrue(success.isEmpty)
    }

    @Test
    fun failedPageIsNeverTreatedAsTheLastPage() {
        // 第一页读满，第二页失败。旧分页循环 `length() < pageSize` 会把失败的一页
        // 当成「最后一页」，于是提交第一页就收工 —— 索引被静默截断。
        val firstPage = listOf(
            DirectoryEntry("bundle-a", "hash-a", 11L),
            DirectoryEntry("bundle-b", "hash-b", 22L)
        )
        val source = ScriptedSource(
            listOf(
                DirectoryPage.Content(firstPage),
                DirectoryPage.Failed(
                    FileAccessFailureSignal.SERVICE_DISCONNECTED
                        .toError("Shizuku 连接已断开", target = "Shared")
                )
            )
        )

        val result = PagedDirectoryLister(pageSize = 2).list(source)

        assertTrue(
            "失败的一页不是最后一页：整次列举必须失败，不能提交截断列表",
            result is DirectoryListingResult.Failure
        )
        val failure = result as DirectoryListingResult.Failure
        assertEquals(OperationErrorKind.NOT_CONNECTED, failure.error.kind)
        assertEquals("Shared", failure.error.target)
        assertTrue("连接断了是可重试的", failure.error.retryable)
        assertEquals("失败前读到的一页也必须丢弃，不能只读一页就返回", 2, source.offsets.size)
    }

    @Test
    fun shortPageEndsTheListingSuccessfully() {
        val source = ScriptedSource(
            listOf(
                DirectoryPage.Content(listOf(DirectoryEntry("a"), DirectoryEntry("b"))),
                DirectoryPage.Content(listOf(DirectoryEntry("c")))
            )
        )

        val result = PagedDirectoryLister(pageSize = 2).list(source)

        val success = result as DirectoryListingResult.Success
        assertEquals(listOf("a", "b", "c"), success.entries.map { it.name })
        assertEquals(listOf(0, 2), source.offsets)
    }

    @Test
    fun fullPageFollowedByEmptyPageIsSuccess() {
        val source = ScriptedSource(
            listOf(
                DirectoryPage.Content(listOf(DirectoryEntry("a"), DirectoryEntry("b"))),
                DirectoryPage.Content(emptyList())
            )
        )

        val success = PagedDirectoryLister(pageSize = 2).list(source) as DirectoryListingResult.Success
        assertEquals(listOf("a", "b"), success.entries.map { it.name })
    }

    @Test
    fun permissionDeniedIsAFailureNotAnEmptyListing() {
        val result = PagedDirectoryLister().list(
            ScriptedSource(
                listOf(
                    DirectoryPage.Failed(
                        FileAccessFailureSignal.PERMISSION_DENIED
                            .toError("没有读取该目录的权限")
                    )
                )
            )
        )

        assertFalse("权限被拒绝不能变成「成功且为空」", result is DirectoryListingResult.Success)
        val failure = result as DirectoryListingResult.Failure
        assertEquals(OperationErrorKind.PERMISSION_DENIED, failure.error.kind)
        assertFalse("权限被拒重试同一个动作无用", failure.error.retryable)
    }

    @Test
    fun malformedPayloadIsItsOwnKindNotAnEmptyListing() {
        val failure = PagedDirectoryLister().list(
            ScriptedSource(
                listOf(
                    DirectoryPage.Failed(
                        FileAccessFailureSignal.PAYLOAD_MALFORMED
                            .toError("分页 JSON 被截断")
                    )
                )
            )
        ) as DirectoryListingResult.Failure

        assertEquals(OperationErrorKind.MALFORMED_DATA, failure.error.kind)
        assertFalse(failure.error.retryable)
    }

    @Test
    fun failureResultExposesNoEntryListAtAll() {
        // 结构性保证：调用方即使想「把失败提交成空索引」也没有入口可拿列表 ——
        // 这比断言 `!= emptyList()` 更难被绕开。
        val failureFields = DirectoryListingResult.Failure::class.java.declaredFields.map { it.name }
        assertTrue(
            "Failure 不得暴露任何条目列表，实际字段：$failureFields",
            failureFields.none { it == "entries" || it == "items" || it == "list" }
        )
        val successFields = DirectoryListingResult.Success::class.java.declaredFields.map { it.name }
        assertTrue("Success 才持有 entries，实际字段：$successFields", successFields.contains("entries"))
    }

    @Test
    fun signalToErrorKindMappingIsTotalAndFixed() {
        val expected = mapOf(
            FileAccessFailureSignal.PERMISSION_DENIED to OperationErrorKind.PERMISSION_DENIED,
            FileAccessFailureSignal.SERVICE_DISCONNECTED to OperationErrorKind.NOT_CONNECTED,
            FileAccessFailureSignal.PATH_MISSING to OperationErrorKind.NOT_FOUND,
            FileAccessFailureSignal.PAYLOAD_MALFORMED to OperationErrorKind.MALFORMED_DATA,
            FileAccessFailureSignal.IO_FAILURE to OperationErrorKind.IO,
            FileAccessFailureSignal.UNKNOWN to OperationErrorKind.UNKNOWN
        )
        // 信号与映射必须一一对应：新增信号却不更新映射时，这条会红。
        assertEquals(FileAccessFailureSignal.values().size, expected.size)
        for ((signal, kind) in expected) {
            assertEquals("信号 $signal 的映射变了", kind, signal.toOperationErrorKind())
        }
    }

    @Test
    fun retryabilityComesFromTheErrorKindOnly() {
        val expected = mapOf(
            OperationErrorKind.PERMISSION_DENIED to false,
            OperationErrorKind.NOT_CONNECTED to true,
            OperationErrorKind.NOT_FOUND to false,
            OperationErrorKind.MALFORMED_DATA to false,
            OperationErrorKind.INVALID_INPUT to false,
            OperationErrorKind.UNSUPPORTED to false,
            OperationErrorKind.IO to true,
            OperationErrorKind.BUSY to true,
            OperationErrorKind.CANCELLED to false,
            OperationErrorKind.UNKNOWN to false
        )
        assertEquals(OperationErrorKind.values().size, expected.size)
        for ((kind, retryable) in expected) {
            assertEquals("$kind 的可重试性变了", retryable, kind.retryable)
            val error = OperationError(kind, "消息")
            assertEquals(kind.retryable, error.retryable)
        }
    }

    // ------------------------------------------------------------------
    // 2. 操作类别、阶段、可取消性、已提交/失败目标
    // ------------------------------------------------------------------

    @Test
    fun scanSourcesScanGameIndexAndPrepackAreDifferentOperations() {
        // 三者是不同操作，不是同一个「任务」的三个名字：id 必须各自稳定且互不相同。
        assertEquals("scan-mod-sources", OperationKind.SCAN_MOD_SOURCES.id)
        assertEquals("scan-game-index", OperationKind.SCAN_GAME_INDEX.id)
        assertEquals("prepack", OperationKind.PREPACK.id)

        val ids = OperationKind.values().map { it.id }
        assertEquals("操作 id 必须互不相同", ids.size, ids.toSet().size)
        assertNotEquals(OperationKind.SCAN_MOD_SOURCES, OperationKind.SCAN_GAME_INDEX)
        assertNotEquals(OperationKind.SCAN_GAME_INDEX, OperationKind.PREPACK)
        assertNotEquals(OperationKind.SCAN_MOD_SOURCES, OperationKind.PREPACK)
    }

    @Test
    fun everyOperationDeclaresAWellFormedStageSequence() {
        for (kind in OperationKind.values()) {
            assertEquals("$kind 必须从 QUEUED 开始", OperationStage.QUEUED, kind.stages.first())
            assertEquals("$kind 必须终于 COMPLETED", OperationStage.COMPLETED, kind.stages.last())
            assertTrue("$kind 的正常路径不得含终态旁路", kind.stages.none { it == OperationStage.FAILED || it == OperationStage.CANCELLED })
        }
    }

    @Test
    fun terminalStagesIncludePartialFailureAndInterruption() {
        val terminalIds = OperationStage.values().filter { it.isTerminal }.map { it.id }.toSet()
        assertEquals(setOf("completed", "partially-failed", "failed", "cancelled", "interrupted"), terminalIds)
    }

    @Test
    fun terminalAndCommitStagesAreNeverImmediatelyCancellable() {
        for (stage in OperationStage.values()) {
            val state = OperationState(OperationId("op-1"), OperationKind.PREPACK, stage)
            assertEquals(
                "阶段 $stage 的可取消性不对",
                !stage.isTerminal && stage != OperationStage.COMMITTING && OperationKind.PREPACK.cancellable,
                state.cancellable
            )
            assertEquals(stage.isTerminal, state.isTerminal)
        }
    }

    @Test
    fun failedTargetMustCarryItsOwnErrorAndNothingElseMay() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetOutcome("bundle-a", TargetStatus.FAILED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetOutcome("bundle-a", TargetStatus.COMMITTED, OperationError(OperationErrorKind.IO, "写失败"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TargetOutcome("bundle-a", TargetStatus.SKIPPED, OperationError(OperationErrorKind.IO, "写失败"))
        }
        // 跳过不是失败：不带 error 也合法。
        assertEquals(TargetStatus.SKIPPED, TargetOutcome("bundle-a", TargetStatus.SKIPPED).status)
    }

    @Test
    fun committedAndFailedTargetsMustNotOverlap() {
        val failedOutcome = TargetOutcome(
            "bundle-a",
            TargetStatus.FAILED,
            OperationError(OperationErrorKind.IO, "写失败", target = "bundle-a")
        )
        assertThrows(IllegalArgumentException::class.java) {
            OperationState(
                id = OperationId("op-1"),
                kind = OperationKind.PREPACK,
                stage = OperationStage.COMMITTING,
                committedTargets = listOf("bundle-a"),
                failedTargets = listOf(failedOutcome)
            )
        }
        // 部分成功是常态：一半提交、一半失败，必须能同时表达。
        val partial = OperationState(
            id = OperationId("op-2"),
            kind = OperationKind.PREPACK,
            stage = OperationStage.COMMITTING,
            committedTargets = listOf("bundle-a"),
            failedTargets = listOf(
                TargetOutcome(
                    "bundle-b",
                    TargetStatus.FAILED,
                    OperationError(OperationErrorKind.IO, "写失败", target = "bundle-b")
                )
            )
        )
        assertEquals(listOf("bundle-a"), partial.committedTargets)
        assertEquals(listOf("bundle-b"), partial.failedTargets.map { it.target })
        assertFalse(partial.isTerminal)
    }

    @Test
    fun requestRejectsMissingAmbiguousOrDuplicateTargets() {
        for (targets in listOf(emptyList(), listOf(""), listOf(" bundle-a"), listOf("bundle-a", "bundle-a"))) {
            assertThrows(IllegalArgumentException::class.java) {
                OperationRequest(OperationId("first"), OperationKind.INSTALL, targets)
            }
        }
    }

    @Test
    fun skippedTargetsCannotBeCountedAsCommittedOrFailed() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationState(
                OperationId("first"), OperationKind.INSTALL, OperationStage.RUNNING,
                committedTargets = listOf("bundle-a"), skippedTargets = listOf("bundle-a")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OperationState(
                OperationId("first"), OperationKind.INSTALL, OperationStage.RUNNING,
                failedTargets = listOf(TargetOutcome("bundle-a", TargetStatus.SKIPPED))
            )
        }
    }

    @Test
    fun reportedProgressCannotExceedRequestedTotal() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationState(
                OperationId("first"), OperationKind.INSTALL, OperationStage.RUNNING,
                committedTargets = listOf("bundle-a"), totalTargets = 0
            )
        }
    }

    @Test
    fun acceptedCancellationIsNotOfferedToUiAgain() {
        val state = OperationState(
            OperationId("first"), OperationKind.INSTALL, OperationStage.RUNNING,
            totalTargets = 1, cancellationRequested = true
        )
        assertFalse(state.cancellable)
        assertFalse(state.isTerminal)
        assertEquals(0, state.completedTargets)
    }

    @Test
    fun operationIdRejectsBlankValues() {
        assertThrows(IllegalArgumentException::class.java) { OperationId("   ") }
        assertEquals("scan-1", OperationId("scan-1").value)
    }

    // ------------------------------------------------------------------
    // 3. 计划模型：同 bundle 的多个 MOD 计划保留既有安装项
    // ------------------------------------------------------------------

    @Test
    fun sameBundleKeepsAlreadyInstalledModsAndPutsThemFirst() {
        val selected = listOf(planned("uri-new", "新装的 mod", HASH_A))
        val installed = listOf(
            InstalledModRef("uri-a", HASH_A, "已装的 A"),
            InstalledModRef("uri-b", HASH_A, "已装的 B")
        )
        val available = mapOf(
            "uri-a" to planned("uri-a", "已装的 A", HASH_A),
            "uri-b" to planned("uri-b", "已装的 B", HASH_A),
            "uri-new" to selected[0]
        )

        val plan = RepackPlanBuilder.build(selected, installed, available)

        assertEquals(1, plan.jobs.size)
        assertEquals(
            "同一 bundle 上已装的 mod 必须一起重打包，且排在本次选中之前",
            listOf("uri-a", "uri-b", "uri-new"),
            plan.jobs[0].mods.map { it.modUri }
        )
        assertTrue(plan.droppedInstalled.isEmpty())
    }

    @Test
    fun deduplicationIsByUriNeverByFamilyKey() {
        // `_extract_family_key` 会剥掉 `_1` 后缀：Part A / Part B 是两个资源、要同时装，
        // 却算出同一个 familyKey。按 familyKey 去重会弄丢其中一个。
        val sharedFamilyKey = "cutscene_char004091"
        val partA = planned("uri-part-a", "过场 Part A", HASH_A, familyKey = sharedFamilyKey)
        val partB = planned("uri-part-b", "过场 Part B", HASH_A, familyKey = sharedFamilyKey)
        val installedSameFamily = InstalledModRef("uri-c", HASH_A, "同 familyKey 的已装项")
        val available = mapOf(
            "uri-c" to planned("uri-c", "同 familyKey 的已装项", HASH_A, familyKey = sharedFamilyKey),
            "uri-part-a" to partA,
            "uri-part-b" to partB
        )

        val plan = RepackPlanBuilder.build(
            selected = listOf(partA, partB),
            installed = listOf(installedSameFamily),
            availableByUri = available
        )

        assertEquals(
            "同 familyKey 的三个 mod 必须都在，按 familyKey 去重会少项",
            listOf("uri-c", "uri-part-a", "uri-part-b"),
            plan.jobs[0].mods.map { it.modUri }
        )
    }

    @Test
    fun installedModWhoseSourceIsGoneIsDroppedAndReported() {
        val selected = listOf(planned("uri-new", "新装的 mod", HASH_A))
        val gone = InstalledModRef("uri-gone", HASH_A, "源文件已被删除的 mod")

        val plan = RepackPlanBuilder.build(
            selected = selected,
            installed = listOf(gone),
            availableByUri = mapOf("uri-new" to selected[0])
        )

        assertEquals(listOf("uri-new"), plan.jobs[0].mods.map { it.modUri })
        assertEquals(
            "源文件已不在的已装项必须逐条报出来，不能静默丢弃",
            listOf("uri-gone"),
            plan.droppedInstalled.map { it.modUri }
        )
    }

    @Test
    fun installedModOnAnotherBundleIsNotPulledIn() {
        val selected = listOf(planned("uri-new", "新装的 mod", HASH_A))
        val other = InstalledModRef("uri-z", HASH_B, "别的包上的 mod")

        val plan = RepackPlanBuilder.build(
            selected = selected,
            installed = listOf(other),
            availableByUri = mapOf(
                "uri-new" to selected[0],
                "uri-z" to planned("uri-z", "别的包上的 mod", HASH_B)
            )
        )

        assertEquals(listOf(HASH_A), plan.jobs.map { it.targetHash })
        assertTrue("本次没装的包不该被拉进来", plan.jobs[0].mods.none { it.modUri == "uri-z" })
    }

    @Test
    fun selectedModThatIsAlsoInstalledAppearsExactlyOnce() {
        val selected = listOf(planned("uri-a", "已经装着的同一个 mod", HASH_A))

        val plan = RepackPlanBuilder.build(
            selected = selected,
            installed = listOf(InstalledModRef("uri-a", HASH_A, "已经装着的同一个 mod")),
            availableByUri = mapOf("uri-a" to selected[0])
        )

        assertEquals(listOf("uri-a"), plan.jobs[0].mods.map { it.modUri })
    }

    @Test
    fun emptySelectionProducesNoJobs() {
        val plan = RepackPlanBuilder.build(
            selected = emptyList(),
            installed = listOf(InstalledModRef("uri-a", HASH_A, "已装的 A")),
            availableByUri = mapOf("uri-a" to planned("uri-a", "已装的 A", HASH_A))
        )
        assertTrue(plan.jobs.isEmpty())
        assertTrue(plan.droppedInstalled.isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. 迁出后的计划/结果类型保持原有语义
    // ------------------------------------------------------------------

    @Test
    fun prepackPlanSpaceBoundaryIsUnchanged() {
        // 原实现：预估占用 < 可用空间的九成才放行。
        assertTrue(plan(free = 1000L, estimated = 899L).enoughSpace)
        assertFalse(plan(free = 1000L, estimated = 900L).enoughSpace)
        assertFalse(plan(free = 0L, estimated = 0L).enoughSpace)
    }

    @Test
    fun uninstallPlanRendersClassifiedFailureWithoutCallingItClean() {
        val failed = UninstallPlan(
            bundles = emptyList(),
            fromBackup = 0,
            needDownload = 0,
            downloadBytes = 0L,
            failure = OperationError(
                kind = OperationErrorKind.NOT_CONNECTED,
                message = "无法读取游戏目录。",
                detail = "Shizuku 未运行"
            )
        )
        assertEquals("无法读取游戏目录。", failed.error)
        assertEquals("Shizuku 未运行", failed.detail)
        assertTrue(failed.failure!!.retryable)

        // 没有 failure 才是「确实没有要卸的」—— 两者不能混。
        val clean = UninstallPlan(emptyList(), 0, 0, 0L)
        assertNull(clean.error)
        assertNull(clean.detail)
    }

    @Test
    fun convertedOverwritePlanCarriesDomainIdentifiersNotModInfo() {
        val plan = ConvertedOverwritePlan(
            modUris = listOf("content://mod/1", "content://mod/2"),
            casualties = mapOf("bundle-x" to listOf("被覆盖的 A", "被覆盖的 B")),
            recoverable = setOf("bundle-x")
        )
        assertEquals(2, plan.total)
        assertEquals(listOf("content://mod/1", "content://mod/2"), plan.modUris)
        assertTrue("bundle-x" in plan.recoverable)
    }

    @Test
    fun hashRenameKeepsSkipAndFailureApart() {
        // 跳过（目标名已存在）是那种情形下唯一安全的选择，不是失败。
        assertNotEquals(HashRenameResult.Renamed, HashRenameResult.TargetExists)
        val results = listOf(
            HashRenameResult.Renamed,
            HashRenameResult.TargetExists,
            HashRenameResult.GameBehindCatalog,
            HashRenameResult.Failed("改名失败")
        )
        // 四种结局互不相等：跳过、游戏落后、已改名都不会被当成失败。
        assertEquals(4, results.toSet().size)
        val failures = results.filterIsInstance<HashRenameResult.Failed>()
        assertEquals(1, failures.size)
        assertEquals("改名失败", failures.single().reason)
    }

    // ------------------------------------------------------------------
    // 5. 模型纯度：不得依赖 android.* / UI 包
    // ------------------------------------------------------------------

    @Test
    fun domainModelsReferenceNoAndroidOrUiTypes() {
        val violations = ArrayList<String>()
        val visited = HashSet<String>()
        val pending = ArrayDeque<Pair<Class<*>, String>>()
        for (root in ROOTS) pending.add(root to root.simpleName)

        while (pending.isNotEmpty()) {
            val (cls, via) = pending.removeFirst()
            val name = cls.name
            if (name.startsWith("android.") || name.startsWith("com.bd2toolsbox.ui.")) {
                violations.add("$via -> $name")
                continue
            }
            if (!visited.add(name)) continue
            // 只往本仓类型里递归；JDK/Kotlin 类型不必展开。
            if (!name.startsWith("com.bd2toolsbox.")) continue
            // **父类与接口也要入队**：只扫自己声明的成员会漏掉
            // `class Foo : android.os.Parcelable` 这种「依赖在继承链上」的写法 ——
            // Foo 自己的字段和方法签名可以全是干净的。独立复核查出这条盲区后补上，
            // 证伪实验见 T05.md §9。
            cls.superclass?.let { pending.add(it to "$name : super") }
            for (iface in cls.interfaces) pending.add(iface to "$name : interface")
            for (field in cls.declaredFields) {
                // **静态字段不再跳过**：伴生对象属性编译成 `Companion` 这个静态字段，
                // 跳过它等于整块伴生对象不在扫描面内。`INSTANCE`/`$VALUES` 入队无害
                // （要么已访问过，要么就是本类自己的数组）。
                for (raw in rawClasses(field.genericType)) pending.add(raw to "$name.${field.name}")
            }
            for (method in cls.declaredMethods) {
                for (raw in rawClasses(method.genericReturnType)) pending.add(raw to "$name.${method.name}()")
                for (parameter in method.genericParameterTypes) {
                    for (raw in rawClasses(parameter)) pending.add(raw to "$name.${method.name}(...)")
                }
            }
        }

        // 注：ModInfo.uri 是 android.net.Uri，所以「计划里塞一个 ModInfo」会被这条抓到。
        //
        // **这条闸门的边界（独立复核逐条点出，不装作它是包级封闭的）**：
        //   - 只覆盖**签名**（字段类型 / 方法返回与参数类型 / 父类与接口），
        //     **不扫方法体**——在函数体里 new 一个 Uri 再 toString() 溜得过去；
        //   - **不扫顶层函数/属性**（它们在 `…Kt` 类里，没人引用就不会入队）；
        //   - ROOTS 是**手写清单**，新增 core 类型忘了加进来、又没有任何 root
        //     的签名指向它，就静默不被扫。
        // 换成「按包枚举 classpath」能自动扩展，但要读编译产物目录，脆；这里选择
        // 把边界写清楚，而不是让读者高估覆盖面。
        assertTrue(
            "core 领域模型不得引用 android.* / UI 包类型，实际命中：\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 按脚本逐页返回的 [DirectoryPageSource]（fake 只用来隔离文件访问边界）。 */
    private class ScriptedSource(private val pages: List<DirectoryPage>) : DirectoryPageSource {
        val offsets = ArrayList<Int>()

        override fun read(offset: Int, limit: Int): DirectoryPage {
            offsets.add(offset)
            val index = offsets.size - 1
            if (index >= pages.size) {
                throw AssertionError("列举器在第 ${offsets.size} 次读取后仍未终止：offset=$offset")
            }
            return pages[index]
        }
    }

    private fun planned(
        uri: String,
        name: String,
        targetHash: String,
        familyKey: String? = null
    ) = PlannedMod(modUri = uri, name = name, targetHash = targetHash, familyKey = familyKey)

    private fun plan(free: Long, estimated: Long) = PrepackPlan(
        total = 1,
        alreadyCached = 0,
        todo = 1,
        estimatedBytes = estimated,
        freeBytes = free,
        ratioFromSamples = false
    )

    private companion object {
        const val HASH_A = "hash-bundle-a"
        const val HASH_B = "hash-bundle-b"

        /** 递归收集一个 [Type] 里出现的所有原始类（含泛型实参）。 */
        fun rawClasses(type: Type?): List<Class<*>> = when (type) {
            null -> emptyList()
            is Class<*> -> listOf(type)
            is ParameterizedType ->
                listOf(type.rawType as Class<*>) + type.actualTypeArguments.flatMap { rawClasses(it) }
            is GenericArrayType -> rawClasses(type.genericComponentType)
            is WildcardType -> rawClasses(type.upperBounds.firstOrNull())
            else -> emptyList()
        }

        val ROOTS: List<Class<*>> = listOf(
            // core.model
            OperationId::class.java,
            OperationRequest::class.java,
            OperationAdmission.Accepted::class.java,
            OperationAdmission.Rejected::class.java,
            OperationKind::class.java,
            OperationStage::class.java,
            OperationError::class.java,
            OperationErrorKind::class.java,
            TargetOutcome::class.java,
            TargetStatus::class.java,
            OperationState::class.java,
            ModScanProgress::class.java,
            PrepackPlan::class.java,
            UninstallPlan::class.java,
            ConvertedOverwritePlan::class.java,
            PreviewAssets::class.java,
            HashRenameResult::class.java,
            HashRenameResult.Failed::class.java,
            PlannedMod::class.java,
            InstalledModRef::class.java,
            RepackPlanJob::class.java,
            RepackPlan::class.java,
            RepackPlanBuilder::class.java,
            // core.fileaccess
            DirectoryEntry::class.java,
            DirectoryPage::class.java,
            DirectoryPage.Content::class.java,
            DirectoryPage.Failed::class.java,
            DirectoryPageSource::class.java,
            DirectoryListingResult::class.java,
            DirectoryListingResult.Success::class.java,
            DirectoryListingResult.Failure::class.java,
            PagedDirectoryLister::class.java,
            FileAccessFailureSignal::class.java
        )
    }
}
