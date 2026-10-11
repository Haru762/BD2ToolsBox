package com.bd2toolsbox.feature.cdk

import androidx.lifecycle.ViewModel
import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.model.RedeemOutcome
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 兑换流程的宿主（T31）。
 *
 * Composable 不再直接创建仓储、加载历史或发起批量兑换；账号、任务状态、重试与结果
 * 写入都在这里。批量/单码提交共用一把互斥锁：屏幕重组、旋转或重复点击不会同
 * （账号×码）双发。
 *
 * 结果用 [RedeemSummary] 表达：区分成功 / 服务器已兑换 / 终局失败 / 可重试失败 /
 * 未处理 / 未知；「全部处理完」不等于「全部成功」。
 */
@HiltViewModel
class CdkViewModel @Inject constructor(
    private val repo: Bd2RedeemRepository,
    private val gamekee: GamekeeRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(CdkUiState())
    val ui: StateFlow<CdkUiState> = _ui.asStateFlow()

    /** 提交互斥：并发/重复点击不得同（账号×码）双发。 */
    private val submitMutex = Mutex()

    fun refreshAccounts() {
        _ui.update { it.copy(accounts = repo.savedUserIds()) }
    }

    fun loadHistory() {
        _ui.update { it.copy(history = repo.loadHistory()) }
    }

    fun addAccount(userId: String): Boolean {
        val ok = repo.addUserId(userId)
        if (ok) refreshAccounts()
        return ok
    }

    suspend fun removeAccount(userId: String) {
        repo.removeUserId(userId)
        refreshAccounts()
    }

    /** 所有账号都已处理完（成功 ∪ 终局失败）的码。 */
    fun settledCodesFor(items: List<CdkItem>): Set<String> {
        val accounts = repo.savedUserIds()
        if (accounts.isEmpty()) return emptySet()
        val perAccount = accounts.map { repo.autoDoneCodes(it) + repo.historyCodes(it) }
        return perAccount.reduce { a, b -> a intersect b }
    }

    /**
     * 拉清单（T31/T45）。
     *
     * 拉完就重算行级标记；**不**在这里自动兑换 —— 自动兑换的触发时机是产品决策，
     * 由屏幕在拉到清单后显式调 [redeemAll]（与原实现一致）。
     */
    suspend fun loadItems(): Result<List<CdkItem>> {
        _ui.update { it.copy(loading = true, error = null) }
        return try {
            val items = gamekee.loadCdk()
            _ui.update {
                it.copy(
                    loading = false,
                    items = items,
                    accounts = repo.savedUserIds(),
                    settledCodes = settledCodesFor(items)
                )
            }
            Result.success(items)
        } catch (e: CancellationException) {
            _ui.update { it.copy(loading = false) }
            throw e // 切 tab/退出时的协程取消不是加载失败，别吞
        } catch (e: Exception) {
            val message = e.message ?: "加载失败"
            _ui.update { it.copy(loading = false, error = message) }
            Result.failure(e)
        }
    }

    /** 重算行级标记（账号增删/兑换后调用）。 */
    fun refreshMarks() {
        val items = _ui.value.items
        _ui.update {
            it.copy(
                accounts = repo.savedUserIds(),
                settledCodes = if (items == null) emptySet() else settledCodesFor(items)
            )
        }
    }

    /**
     * 批量兑换。串行 + 互斥；返回真实汇总。
     *
     * 仓储抛异常时如实把错误放进状态并继续上抛 —— 不吞成「全部成功」。
     */
    suspend fun redeemAll(items: List<CdkItem>): RedeemSummary = submitMutex.withLock {
        _ui.update { it.copy(batchRedeeming = true, error = null) }
        try {
            val outcomes = repo.autoRedeem(items)
            publishOutcomes(outcomes, items)
            _ui.value.lastSummary
        } catch (e: Exception) {
            _ui.update { it.copy(batchRedeeming = false, error = e.message ?: "兑换失败") }
            throw e
        }
    }

    /** 单码多账号。与批量共用互斥锁。 */
    suspend fun redeemOne(item: CdkItem): RedeemSummary = submitMutex.withLock {
        _ui.update { it.copy(redeemingCode = item.code, error = null) }
        try {
            val outcomes = repo.redeemCodeForAll(item.code, item.reward)
            publishOutcomes(outcomes, _ui.value.items.orEmpty())
            _ui.value.lastSummary
        } catch (e: Exception) {
            _ui.update { it.copy(redeemingCode = null, error = e.message ?: "兑换失败") }
            throw e
        }
    }

    /**
     * 把官方返回的 `(账号, 响应)` 列表同时落成**合计**与**账号维度**结果（T45）。
     *
     * 账号维度按账号分组后各自汇总；界面逐行展示，全终局失败时不会出现任何成功字样。
     */
    private fun publishOutcomes(outcomes: List<Pair<String, RedeemOutcome>>, items: List<CdkItem>) {
        val perAccount = outcomes
            .groupBy({ it.first }, { dispositionOf(it.second) })
            .toSortedMap()
            .map { (accountId, dispositions) ->
                AccountRedeemResult(
                    accountId = accountId,
                    accountName = accountId,
                    summary = summarizeRedemption(dispositions)
                )
            }
        val aggregate = BatchRedeemSummary(perAccount).aggregate
        _ui.update {
            it.copy(
                batchRedeeming = false,
                redeemingCode = null,
                lastSummary = aggregate,
                lastPerAccount = perAccount,
                history = repo.loadHistory(),
                settledCodes = settledCodesFor(items)
            )
        }
    }
}
