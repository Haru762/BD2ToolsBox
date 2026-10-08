package com.bd2toolsbox.core.operations

import com.bd2toolsbox.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class OperationCoordinatorTest {
    private val store = OperationStateStore()
    private val coordinator = OperationCoordinator(store)

    private fun request(id: String, vararg targets: String, kind: OperationKind = OperationKind.INSTALL) =
        OperationRequest(OperationId(id), kind, targets.toList())

    private fun submit(id: String, vararg targets: String, kind: OperationKind = OperationKind.INSTALL): OperationId {
        val request = request(id, *targets, kind = kind)
        assertEquals(OperationAdmission.Accepted(request.id), coordinator.submit(request))
        return request.id
    }

    private fun state(id: OperationId): OperationState = store.states.value.getValue(id)

    @Test
    fun sameBundleCannotBeAdmittedTwice() {
        val first = submit("first", "bundle-a", kind = OperationKind.PREPACK)
        val rejected = coordinator.submit(request("second", "bundle-a", kind = OperationKind.UNINSTALL))
        assertTrue(rejected is OperationAdmission.Rejected)
        rejected as OperationAdmission.Rejected
        assertEquals(first, rejected.conflictingId)
        assertEquals(OperationErrorKind.BUSY, rejected.error.kind)
        assertEquals(setOf(first), store.states.value.keys)
    }

    @Test
    fun duplicateIdCannotReplaceParametersOrCompletedResult() = runTest {
        val id = submit("first", "bundle-a")
        assertTrue(coordinator.submit(request("first", "bundle-b")) is OperationAdmission.Rejected)
        coordinator.execute(id) { commit("bundle-a") {} }
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        assertTrue(coordinator.submit(request("first", "bundle-b")) is OperationAdmission.Rejected)
    }

    @Test
    fun submissionCopiesTargetsBeforeCallerCanMutateThem() = runTest {
        val targets = mutableListOf("bundle-a")
        val request = OperationRequest(OperationId("first"), OperationKind.INSTALL, targets)
        coordinator.submit(request)
        targets[0] = "bundle-b"
        assertTrue(coordinator.submit(request("second", "bundle-a")) is OperationAdmission.Rejected)
        coordinator.execute(request.id) { commit("bundle-a") {} }
        assertEquals(listOf("bundle-a"), state(request.id).committedTargets)
    }

    @Test
    fun concurrentSubmissionsHaveExactlyOneOwner() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { n ->
                threads.submit(Callable {
                    ready.countDown()
                    check(start.await(3, TimeUnit.SECONDS))
                    coordinator.submit(request("task-$n", "bundle-a"))
                })
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            start.countDown()
            val admissions = results.map { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, admissions.count { it is OperationAdmission.Accepted })
            assertEquals(1, admissions.count { it is OperationAdmission.Rejected })
        } finally {
            start.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun clearingCacheIsExclusiveEvenWithDifferentTargetNames() {
        val id = submit("prepack", "bundle-a", kind = OperationKind.PREPACK)
        val rejected = coordinator.submit(request("clear", "preview-cache", kind = OperationKind.CLEAR_CACHE))
        assertEquals(id, (rejected as OperationAdmission.Rejected).conflictingId)
    }

    @Test
    fun cacheClearAlsoPreventsLaterWriters() {
        submit("clear", "preview-cache", kind = OperationKind.CLEAR_CACHE)
        assertTrue(coordinator.submit(request("install", "bundle-a")) is OperationAdmission.Rejected)
    }

    @Test
    fun disjointTargetsCanRunConcurrently() = runTest {
        val a = submit("a", "bundle-a")
        val b = submit("b", "bundle-b")
        val gate = CompletableDeferred<Unit>()
        val first = launch { coordinator.execute(a) { gate.await(); commit("bundle-a") {} } }
        runCurrent()
        coordinator.execute(b) { commit("bundle-b") {} }
        assertEquals(OperationStage.RUNNING, state(a).stage)
        assertEquals(OperationStage.COMPLETED, state(b).stage)
        gate.complete(Unit)
        first.join()
        assertEquals(OperationStage.COMPLETED, state(a).stage)
    }

    @Test
    fun removingUiObserverDoesNotCancelHostOrReleaseItsLock() = runTest {
        val id = submit("first", "bundle-a")
        val hostJob = SupervisorJob()
        val host = CoroutineScope(hostJob + StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val observer = launch { store.states.collect() }
        val worker = host.launch {
            coordinator.execute(id) { gate.await(); commit("bundle-a") {} }
        }
        try {
            runCurrent()
            observer.cancelAndJoin()
            assertTrue(worker.isActive)
            assertTrue(coordinator.submit(request("second", "bundle-a")) is OperationAdmission.Rejected)
            gate.complete(Unit)
            worker.join()
            assertEquals(OperationStage.COMPLETED, state(id).stage)
            assertFalse(hostJob.children.any())
            submit("second", "bundle-a")
        } finally {
            gate.complete(Unit)
            hostJob.cancel()
            observer.cancel()
        }
    }

    @Test
    fun aServiceRedeliveryCannotStartTheSameWorkAgain() = runTest {
        val id = submit("first", "bundle-a")
        val gate = CompletableDeferred<Unit>()
        var executions = 0
        val running = launch {
            coordinator.execute(id) { executions++; gate.await(); commit("bundle-a") {} }
        }
        runCurrent()
        assertNull(coordinator.execute(id) { executions++ })
        gate.complete(Unit)
        running.join()
        assertNull(coordinator.execute(id) { executions++ })
        assertEquals(1, executions)
    }

    @Test
    fun missingParametersDoNotExecuteOrProduceSuccess() = runTest {
        var ran = false
        assertNull(coordinator.execute(OperationId("lost")) { ran = true })
        assertFalse(ran)
        assertTrue(store.states.value.isEmpty())
    }

    @Test
    fun returningWithoutAllTargetResultsIsNotSuccess() = runTest {
        val id = submit("first", "bundle-a", "bundle-b")
        coordinator.execute(id) { commit("bundle-a") {} }
        assertEquals(OperationStage.PARTIALLY_FAILED, state(id).stage)
        assertEquals(1, state(id).completedTargets)
        assertEquals(2, state(id).totalTargets)
        assertNotNull(state(id).error)
    }

    @Test
    fun noResultsIsFailureRatherThanOneHundredPercent() = runTest {
        val id = submit("first", "bundle-a")
        coordinator.execute(id) {}
        assertEquals(OperationStage.FAILED, state(id).stage)
        assertEquals(0, state(id).completedTargets)
    }

    @Test
    fun partialFailureRetainsSuccessfulAndFailedTargetsSeparately() = runTest {
        val id = submit("first", "bundle-a", "bundle-b", "bundle-c")
        coordinator.execute(id) {
            commit("bundle-a") {}
            fail("bundle-b", OperationError(OperationErrorKind.IO, "写入失败"))
            skip("bundle-c")
        }
        val result = state(id)
        assertEquals(OperationStage.PARTIALLY_FAILED, result.stage)
        assertEquals(listOf("bundle-a"), result.committedTargets)
        assertEquals(listOf("bundle-b"), result.failedTargets.map { it.target })
        assertEquals(listOf("bundle-c"), result.skippedTargets)
        assertFalse(result.cancellable)
        submit("second", "bundle-a", "bundle-b", "bundle-c")
    }

    @Test
    fun anExceptionNeverOverwritesCommittedFactsWithSuccess() = runTest {
        val id = submit("first", "bundle-a", "bundle-b")
        coordinator.execute(id) {
            commit("bundle-a") {}
            throw IOException("disk unavailable")
        }
        assertEquals(OperationStage.PARTIALLY_FAILED, state(id).stage)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        assertEquals(OperationErrorKind.IO, state(id).error?.kind)
        submit("retry", "bundle-b")
    }

    @Test
    fun failingCommitDoesNotRecordTargetAsCommitted() = runTest {
        val id = submit("first", "bundle-a")
        coordinator.execute(id) { commit("bundle-a") { throw IOException("write failed") } }
        assertEquals(OperationStage.FAILED, state(id).stage)
        assertTrue(state(id).committedTargets.isEmpty())
        submit("retry", "bundle-a")
    }

    @Test
    fun targetMustBelongToRequestBeforeItsCommitActionRuns() = runTest {
        val id = submit("first", "bundle-a")
        var wrote = false
        coordinator.execute(id) { commit("bundle-other") { wrote = true } }
        assertFalse(wrote)
        assertEquals(OperationStage.FAILED, state(id).stage)
    }

    @Test
    fun aTargetCannotBeWrittenTwiceWithinOneOperation() = runTest {
        val id = submit("first", "bundle-a")
        var writes = 0
        coordinator.execute(id) {
            commit("bundle-a") { writes++ }
            commit("bundle-a") { writes++ }
        }
        assertEquals(1, writes)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        assertNotEquals(OperationStage.COMPLETED, state(id).stage)
    }

    @Test
    fun queuedCancellationReleasesParametersAndDoesNotRunExecutor() = runTest {
        val id = submit("first", "bundle-a")
        assertTrue(coordinator.requestCancellation(id))
        assertEquals(OperationStage.CANCELLED, state(id).stage)
        var ran = false
        assertNull(coordinator.execute(id) { ran = true })
        assertFalse(ran)
        assertFalse(coordinator.requestCancellation(id))
        submit("second", "bundle-a")
    }

    @Test
    fun cancellationAtSafePointDoesNotRunNextTarget() = runTest {
        val id = submit("first", "bundle-a", "bundle-b")
        var secondWrote = false
        coordinator.execute(id) {
            commit("bundle-a") {}
            coordinator.requestCancellation(id)
            commit("bundle-b") { secondWrote = true }
        }
        assertEquals(OperationStage.CANCELLED, state(id).stage)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        assertFalse(secondWrote)
    }

    @Test
    fun cancellationDuringCommitWaitsForCommitAndKeepsResourceLocked() = runTest {
        val id = submit("first", "bundle-a", "bundle-b")
        val gate = CompletableDeferred<Unit>()
        val worker = launch {
            coordinator.execute(id) {
                commit("bundle-a") { gate.await() }
                commit("bundle-b") { fail("Must not start another write") }
            }
        }
        runCurrent()
        assertEquals(OperationStage.COMMITTING, state(id).stage)
        assertFalse(state(id).cancellable)
        assertTrue(coordinator.requestCancellation(id))
        assertTrue(state(id).cancellationRequested)
        assertTrue(worker.isActive)
        assertTrue(coordinator.submit(request("second", "bundle-a")) is OperationAdmission.Rejected)
        gate.complete(Unit)
        worker.join()
        assertEquals(OperationStage.CANCELLED, state(id).stage)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        submit("second", "bundle-a")
    }

    @Test
    fun hostCancellationCannotInterruptTheCommitCriticalSection() = runTest {
        val id = submit("first", "bundle-a")
        val gate = CompletableDeferred<Unit>()
        var committed = false
        val worker = launch {
            coordinator.execute(id) {
                commit("bundle-a") { gate.await(); committed = true }
            }
        }
        runCurrent()
        worker.cancel()
        runCurrent()
        assertFalse(worker.isCompleted)
        assertEquals(OperationStage.COMMITTING, state(id).stage)
        assertTrue(coordinator.submit(request("second", "bundle-a")) is OperationAdmission.Rejected)
        gate.complete(Unit)
        worker.join()
        assertTrue(committed)
        assertEquals(OperationStage.INTERRUPTED, state(id).stage)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        submit("second", "bundle-a")
    }

    @Test
    fun aNormalHostCancellationIsInterruptedAndReleasesResources() = runTest {
        val id = submit("first", "bundle-a")
        val worker = launch { coordinator.execute(id) { awaitCancellation() } }
        runCurrent()
        worker.cancelAndJoin()
        assertEquals(OperationStage.INTERRUPTED, state(id).stage)
        assertTrue(state(id).committedTargets.isEmpty())
        submit("second", "bundle-a")
    }

    @Test
    fun onlyTheCoordinatorMayPublishTerminalOrCommitStages() = runTest {
        for (stage in listOf(OperationStage.QUEUED, OperationStage.PREPARING, OperationStage.COMMITTING) +
            OperationStage.entries.filter { it.isTerminal }) {
            val id = submit(stage.id, "bundle-a")
            coordinator.execute(id) { phase(stage) }
            assertEquals("Executor must not force $stage", OperationStage.FAILED, state(id).stage)
        }
    }

    @Test
    fun scanningAndPrepackCanReportTheirOwnDetailedPhases() = runTest {
        val id = submit("scan", "source-a", kind = OperationKind.SCAN_MOD_SOURCES)
        coordinator.execute(id) {
            phase(OperationStage.ENUMERATING, "枚举来源")
            assertEquals(OperationStage.ENUMERATING, state(id).stage)
            assertEquals("枚举来源", state(id).progressMessage)
            phase(OperationStage.PARSING, "解析条目")
            commit("source-a") {}
        }
        assertEquals(OperationStage.COMPLETED, state(id).stage)
    }

    @Test
    fun refusingForegroundStartEndsOnlyQueuedOperationAndReleasesTargets() = runTest {
        val id = submit("first", "bundle-a")
        val error = OperationError(OperationErrorKind.PERMISSION_DENIED, "前台服务启动被拒")
        assertTrue(coordinator.failToStart(id, error))
        assertEquals(OperationStage.FAILED, state(id).stage)
        assertEquals(error, state(id).error)
        assertNull(coordinator.execute(id) { fail("Must not run") })
        submit("second", "bundle-a")
    }

    @Test
    fun cancellationChecksCannotAbortAnEnteredCommit() = runTest {
        val id = submit("first", "bundle-a")
        var wrote = false
        coordinator.execute(id) {
            commit("bundle-a") {
                coordinator.requestCancellation(id)
                assertFalse("Blocking engine must finish its commit", cancellationRequested)
                checkpoint()
                wrote = true
            }
        }
        assertTrue(wrote)
        assertEquals(OperationStage.CANCELLED, state(id).stage)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
    }

    @Test
    fun cancellationOfExecutorScopeIsVisibleToBlockingEngine() = runTest {
        val id = submit("first", "bundle-a")
        var observed = false
        coordinator.execute(id) {
            currentCoroutineContext().cancel()
            observed = cancellationRequested
        }
        assertTrue(observed)
        assertEquals(OperationStage.INTERRUPTED, state(id).stage)
    }

    @Test
    fun lateCallbackCannotUseAnExecutionFromAnOlderTaskGeneration() = runTest {
        val id = submit("first", "bundle-a")
        lateinit var old: OperationCoordinator.Execution
        coordinator.execute(id) { old = this; commit("bundle-a") {} }
        coordinator.acknowledge(id)
        submit("first", "bundle-a")
        var lateWrite = false
        coordinator.execute(id) { old.commit("bundle-a") { lateWrite = true } }
        assertFalse(lateWrite)
        assertEquals(OperationStage.FAILED, state(id).stage)
        assertTrue(state(id).committedTargets.isEmpty())
    }

    @Test
    fun fatalExecutorErrorIsRethrownWithoutPublishingSuccess() = runTest {
        val id = submit("first", "bundle-a")
        try {
            coordinator.execute(id) {
                commit("bundle-a") {}
                throw LinkageError("engine unavailable")
            }
            fail("The host must receive a fatal engine error")
        } catch (e: LinkageError) {
            assertEquals("engine unavailable", e.message)
        }
        assertEquals(OperationStage.PARTIALLY_FAILED, state(id).stage)
        assertEquals(listOf("bundle-a"), state(id).committedTargets)
        assertNotNull(state(id).error)
        submit("retry", "bundle-a")
    }

    @Test
    fun oldForegroundRejectionCannotEndAnAlreadyRunningOperation() = runTest {
        val id = submit("first", "bundle-a")
        coordinator.execute(id) {
            assertFalse(coordinator.failToStart(id, OperationError(OperationErrorKind.UNKNOWN, "late failure")))
            assertFalse(coordinator.acknowledge(id))
            commit("bundle-a") {}
        }
        assertEquals(OperationStage.COMPLETED, state(id).stage)
        assertTrue(coordinator.acknowledge(id))
        assertFalse(store.states.value.containsKey(id))
    }
}
