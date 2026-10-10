package com.bd2toolsbox.feature.guides

import com.bd2toolsbox.data.model.GuideArticle
import com.bd2toolsbox.data.model.GuideDetail
import com.bd2toolsbox.data.repository.GamekeeRepository
import com.bd2toolsbox.ui.viewmodel.GuideViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T30：攻略请求竞态。验证「后发先到者胜」「关闭后迟到结果不得重开」。
 *
 * 被测对象：纯 [RequestGeneration] + [GuideViewModel]（真 ViewModel，mock 仓储边界）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuideRequestRaceTest {

    @Before fun setup() {
        // GuideViewModel 在失败路径写 android.util.Log；JVM 下先桩住。
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ---------------------------------------------------------------- 纯代次控制

    @Test fun onlyLatestTokenIsCurrent() {
        val g = RequestGeneration()
        val first = g.next()
        val second = g.next()
        assertFalse(g.isCurrent(first))
        assertTrue(g.isCurrent(second))
    }

    @Test fun invalidateDropsInFlightTokens() {
        val g = RequestGeneration()
        val token = g.next()
        assertTrue(g.isCurrent(token))
        g.invalidate()
        assertFalse(g.isCurrent(token))
    }

    @Test fun currentReturnsTheLatestTokenForAppends() {
        val g = RequestGeneration()
        val token = g.next()
        assertEquals(token, g.current())
        assertTrue(g.isCurrent(g.current()))
    }

    // ---------------------------------------------------------------- ViewModel 竞态

    private fun article(id: Long) = GuideArticle(id, "title-$id", "", null, 0, 0)
    private fun detail(id: Long) = GuideDetail(article(id), "<html>$id</html>", 1L)

    private fun repoWithGatedDetails(): Pair<GamekeeRepository, MutableMap<Long, CompletableDeferred<GuideDetail>>> {
        val repo = mockk<GamekeeRepository>()
        val gates = mutableMapOf<Long, CompletableDeferred<GuideDetail>>()
        coEvery { repo.loadArticleDetail(any()) } coAnswers {
            val art = firstArg<GuideArticle>()
            gates.getOrPut(art.id) { CompletableDeferred() }.await()
        }
        return repo to gates
    }

    private fun readyId(vm: GuideViewModel): Long {
        val state = vm.detail.value
        assertTrue("期望 Ready，实得 $state", state is GuideViewModel.DetailState.Ready)
        return (state as GuideViewModel.DetailState.Ready).detail.article.id
    }

    @Test fun laterArticleRequestWinsWhenItReturnsFirst() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (repo, gates) = repoWithGatedDetails()
        val vm = GuideViewModel(repo)

        vm.openArticle(article(1))
        runCurrent()
        vm.openArticle(article(2))
        runCurrent()

        // B（后发）先到
        gates.getValue(2L).complete(detail(2))
        advanceUntilIdle()
        assertEquals(2L, readyId(vm))

        // A（先发）迟到，不得覆盖 B
        gates.getValue(1L).complete(detail(1))
        advanceUntilIdle()
        assertEquals("迟到的 A 不得覆盖 B", 2L, readyId(vm))
    }

    @Test fun lateResultAfterDismissCannotReopenTheArticle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (repo, gates) = repoWithGatedDetails()
        val vm = GuideViewModel(repo)

        vm.openArticle(article(7))
        runCurrent()
        vm.dismissArticle()

        gates.getValue(7L).complete(detail(7))
        advanceUntilIdle()
        assertEquals("关闭后迟到结果不得重开", GuideViewModel.DetailState.Closed, vm.detail.value)
    }

    @Test fun lateFailureAfterNewerRequestCannotOverrideIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = mockk<GamekeeRepository>()
        val failGate = CompletableDeferred<GuideDetail>()
        coEvery { repo.loadArticleDetail(article(1)) } coAnswers { failGate.await() }
        coEvery { repo.loadArticleDetail(article(2)) } returns detail(2)
        val vm = GuideViewModel(repo)

        vm.openArticle(article(1))
        runCurrent()
        vm.openArticle(article(2))
        advanceUntilIdle()
        assertEquals(2L, readyId(vm))

        failGate.completeExceptionally(RuntimeException("network down"))
        advanceUntilIdle()
        assertEquals("旧请求的失败不得覆盖新正文", 2L, readyId(vm))
    }

    @Test fun searchResetDropsThePreviousSearchPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = mockk<GamekeeRepository>()
        val firstGate = CompletableDeferred<GamekeeRepository.ArticlePage>()
        coEvery { repo.searchArticles("a", any()) } coAnswers { firstGate.await() }
        coEvery { repo.searchArticles("b", any()) } returns
            GamekeeRepository.ArticlePage(listOf(article(20)), hasMore = false)
        val vm = GuideViewModel(repo)

        vm.setSearchQuery("a")
        vm.submitSearch()
        runCurrent()
        vm.setSearchQuery("b")
        vm.submitSearch()
        advanceUntilIdle()
        assertEquals(listOf(20L), vm.articles.value.map { it.id })

        // 旧搜索迟到：不得覆盖新搜索结果
        firstGate.complete(GamekeeRepository.ArticlePage(listOf(article(10)), hasMore = false))
        advanceUntilIdle()
        assertEquals("旧搜索页不得覆盖新搜索", listOf(20L), vm.articles.value.map { it.id })
    }
}
