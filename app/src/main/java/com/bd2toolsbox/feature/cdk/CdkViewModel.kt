package com.bd2toolsbox.feature.cdk

import androidx.lifecycle.ViewModel
import com.bd2toolsbox.data.model.CdkItem
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
    private val repo: Bd2RedeemRepository
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
     * 批量兑换。串行 + 互斥；返回真实汇总。
     *
     * 仓储抛异常时如实把错误放进状态并继续上抛 —— 不吞成「全部成功」。
     */
    suspend fun redeemAll(items: List<CdkItem>): RedeemSummary = submitMutex.withLock {
        _ui.update { it.copy(batchRedeeming = true, error = null) }
        try {
            val outcomes = repo.autoRedeem(items)
            val summary = summarizeOutcomes(outcomes.map { it.second })
            _ui.update {
                it.copy(
                    batchRedeeming = false,
                    lastSummary = summary,
                    history = repo.loadHistory(),
                    settledCodes = settledCodesFor(items)
                )
            }
            summary
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
            val summary = summarizeOutcomes(outcomes.map { it.second })
            _ui.update {
                it.copy(redeemingCode = null, lastSummary = summary, history = repo.loadHistory())
            }
            summary
        } catch (e: Exception) {
            _ui.update { it.copy(redeemingCode = null, error = e.message ?: "兑换失败") }
            throw e
        }
    }
}
