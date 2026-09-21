package com.bd2toolsbox.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T08 纯日志恢复投影（无 I/O）。
 *
 * 守的是 [docs/superpowers/plans/2026-09-20-t08-content-reconciliation-next.md] 批次 A：
 * 给定 JournalEntry 序列，投影出已闭合 / 待核对 / 不可安全恢复。
 * 写入顺序用下标，不用 atMillis。
 */
class JournalRecoveryProjectionTest {

    @Test
    fun emptyJournalIsClear() {
        assertEquals(JournalRecoveryProjection.Result.Clear, JournalRecoveryProjection.project(emptyList()))
    }

    @Test
    fun plannedAndStagedNeverOpenAWindow() {
        val entries = listOf(
            entry("op-a", JournalStage.PLANNED, 0, 50),
            entry("op-a", JournalStage.STAGED, 1, 10)
        )
        assertEquals(JournalRecoveryProjection.Result.Clear, JournalRecoveryProjection.project(entries))
    }

    @Test
    fun clockGoingBackwardsDoesNotHideAnEarlierUnclosedReplace() {
        val older = replaced("op-old", at = 9000L, bundle = "bundle-old", uri = "uri-old")
        val newer = replaced("op-new", at = 1000L, bundle = "bundle-new", uri = "uri-new")
        val result = JournalRecoveryProjection.project(listOf(older, newer))
        val pending = result as JournalRecoveryProjection.Result.Pending
        assertEquals(setOf("bundle-old", "bundle-new"), pending.bundleNames.toSet())
        assertEquals(setOf("uri-old", "uri-new"), pending.modUris.toSet())
        assertEquals(setOf("op-old", "op-new"), pending.operationIds.toSet())
    }

    @Test
    fun anotherOperationsCommitDoesNotCloseThisOp() {
        val entries = listOf(
            replacing("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a"),
            committed("op-b", at = 2L, bundle = "bundle-b", uri = "uri-b"),
            replaced("op-a", at = 3L, bundle = "bundle-a", uri = "uri-a")
        )
        val pending = JournalRecoveryProjection.project(entries) as JournalRecoveryProjection.Result.Pending
        assertEquals(listOf("op-a"), pending.operationIds)
        assertEquals(listOf("bundle-a"), pending.bundleNames)
    }

    @Test
    fun aLaterReplaceAfterThisOpsOwnCommitOpensANewWindow() {
        val entries = listOf(
            replacing("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a"),
            committed("op-a", at = 2L, bundle = "bundle-a", uri = "uri-a"),
            replaced("op-a", at = 3L, bundle = "bundle-a", uri = "uri-a")
        )
        val pending = JournalRecoveryProjection.project(entries) as JournalRecoveryProjection.Result.Pending
        assertEquals(listOf("op-a"), pending.operationIds)
        assertEquals(listOf("bundle-a"), pending.bundleNames)
    }

    @Test
    fun emptyTargetsOnAnOpenReplaceAreUnsafe() {
        val entries = listOf(
            JournalEntry("op-a", JournalStage.TARGET_REPLACED.id, 1L, emptyList())
        )
        val unsafe = JournalRecoveryProjection.project(entries)
        assertTrue("empty targets cannot be recovered, actual $unsafe", unsafe is JournalRecoveryProjection.Result.Unsafe)
    }

    @Test
    fun legacyReplaceWithoutFactsIsPendingNotClear() {
        val entries = listOf(replaced("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a", facts = null))
        val pending = JournalRecoveryProjection.project(entries) as JournalRecoveryProjection.Result.Pending
        assertEquals(listOf("op-a"), pending.operationIds)
        assertTrue("legacy ops have no recoverable facts", pending.operations.single().hasCompleteFacts.not())
    }

    @Test
    fun completeFactsOnReplacingAndReplacedProjectTheFrozenUpserts() {
        val facts = facts("bundle-a", "uri-a", SHA_A)
        val entries = listOf(
            replacing("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a", facts = facts),
            replaced("op-a", at = 2L, bundle = "bundle-a", uri = "uri-a", facts = facts)
        )
        val pending = JournalRecoveryProjection.project(entries) as JournalRecoveryProjection.Result.Pending
        val op = pending.operations.single()
        assertTrue(op.hasCompleteFacts)
        assertEquals(SHA_A, op.targets.single().recoveryFacts!!.expectedContentSha256)
        assertEquals(listOf("uri-a"), op.targets.single().recoveryFacts!!.ledgerUpserts.map { it.modUri })
    }

    @Test
    fun conflictingShaAcrossStagesIsUnsafe() {
        val first = facts("bundle-a", "uri-a", SHA_A)
        val second = facts("bundle-a", "uri-a", SHA_B)
        val entries = listOf(
            replacing("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a", facts = first),
            replaced("op-a", at = 2L, bundle = "bundle-a", uri = "uri-a", facts = second)
        )
        assertTrue(JournalRecoveryProjection.project(entries) is JournalRecoveryProjection.Result.Unsafe)
    }

    @Test
    fun factsPresentThenMissingOnTheSameBundleIsUnsafe() {
        val entries = listOf(
            replacing("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a", facts = facts("bundle-a", "uri-a", SHA_A)),
            replaced("op-a", at = 2L, bundle = "bundle-a", uri = "uri-a", facts = null)
        )
        assertTrue(JournalRecoveryProjection.project(entries) is JournalRecoveryProjection.Result.Unsafe)
    }

    @Test
    fun sameUriMappedToTwoBundlesInOneOpIsUnsafe() {
        val entries = listOf(
            JournalEntry(
                operationId = "op-a",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 1L,
                targets = listOf(
                    target("bundle-a", "uri-a", facts("bundle-a", "uri-a", SHA_A)),
                    target("bundle-b", "uri-a", facts("bundle-b", "uri-a", SHA_B))
                )
            )
        )
        assertTrue(JournalRecoveryProjection.project(entries) is JournalRecoveryProjection.Result.Unsafe)
    }

    @Test
    fun duplicateBundleInOneEntryIsUnsafe() {
        val entries = listOf(
            JournalEntry(
                operationId = "op-a",
                stage = JournalStage.TARGET_REPLACED.id,
                atMillis = 1L,
                targets = listOf(
                    target("bundle-a", "uri-a", facts("bundle-a", "uri-a", SHA_A)),
                    target("bundle-a", "uri-b", facts("bundle-a", "uri-b", SHA_A))
                )
            )
        )
        assertTrue(JournalRecoveryProjection.project(entries) is JournalRecoveryProjection.Result.Unsafe)
    }

    @Test
    fun multipleUnclosedOpsAreAllReported() {
        val entries = listOf(
            replaced("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a"),
            replaced("op-b", at = 2L, bundle = "bundle-b", uri = "uri-b")
        )
        val pending = JournalRecoveryProjection.project(entries) as JournalRecoveryProjection.Result.Pending
        assertEquals(setOf("op-a", "op-b"), pending.operationIds.toSet())
        assertEquals(setOf("bundle-a", "bundle-b"), pending.bundleNames.toSet())
    }

    @Test
    fun ownCommitClosesEvenIfTheLedgerWouldLookUninstalled() {
        val entries = listOf(
            replacing("op-a", at = 1L, bundle = "bundle-a", uri = "uri-a"),
            replaced("op-a", at = 2L, bundle = "bundle-a", uri = "uri-a"),
            committed("op-a", at = 3L, bundle = "bundle-a", uri = "uri-a")
        )
        assertEquals(JournalRecoveryProjection.Result.Clear, JournalRecoveryProjection.project(entries))
    }

    private fun entry(op: String, stage: JournalStage, at: Long, millisSkew: Long) =
        JournalEntry(op, stage.id, millisSkew, listOf(JournalTarget("bundle-a", "hash-a", 1L, listOf("uri-a"))))

    private fun replacing(op: String, at: Long, bundle: String, uri: String, facts: JournalRecoveryFacts? = null) =
        JournalEntry(op, JournalStage.TARGET_REPLACING.id, at, listOf(target(bundle, uri, facts)))

    private fun replaced(op: String, at: Long, bundle: String, uri: String, facts: JournalRecoveryFacts? = null) =
        JournalEntry(op, JournalStage.TARGET_REPLACED.id, at, listOf(target(bundle, uri, facts)))

    private fun committed(op: String, at: Long, bundle: String, uri: String) =
        JournalEntry(op, JournalStage.LEDGER_COMMITTED.id, at, listOf(target(bundle, uri, null)))

    private fun target(bundle: String, uri: String, facts: JournalRecoveryFacts?) =
        JournalTarget(bundle, "hash-$bundle", 4L, listOf(uri), facts)

    private fun facts(bundle: String, uri: String, sha: String) = JournalRecoveryFacts(
        formatVersion = 1,
        expectedContentSha256 = sha,
        ledgerUpserts = listOf(
            LedgerRecord(uri, "name-$uri", "family-$uri", bundle, "HD", true, 123L)
        )
    )

    companion object {
        private const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
