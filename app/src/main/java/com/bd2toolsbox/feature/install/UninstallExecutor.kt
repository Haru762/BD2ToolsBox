package com.bd2toolsbox.feature.install

import com.bd2toolsbox.data.repository.InstalledModRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单个 mod 移除的账本侧判定：摘掉一条记录，看同包还剩谁。
 *
 * 只做原来 `MainViewModel.removeSingleMod` 的前半段（摘记录 + 查剩余），
 * 不读 UI 状态、不决定重打包还是还原、不碰游戏文件：
 * - 还有剩余 → 调用方按保留项重打包一次；
 * - 一个不剩 → 调用方走整包还原；
 * - 源文件缺失导致无法重打包 → 调用方先清整包账本再走整包还原。
 *
 * 账本写失败也不在这里中止：调用方拿到 [LedgerNotice] 后按原语义继续分支，
 * 不静默丢弃、不擅自改写卸载语义。
 */
@Singleton
class UninstallExecutor @Inject constructor(
    private val repository: InstalledModRepository
) {
    /**
     * 摘掉 [modUri] 的记录，返回本次写通知与同包剩余记录的 uri。
     *
     * 语义与旧实现逐字一致：先摘掉这一个，再看这个包上还剩谁。
     */
    fun removeSingleModRecord(modUri: String, targetHash: String): SingleModRemoval {
        // 早先是 detachMod(familyKey, uri)，而账本按 familyKey「一包一条」记 ——
        // 卸同包第二个 mod 时按它自己的 familyKey 查不到记录，detachMod 返回空，
        // 就被当成「该包已无 mod」而整包还原，把同包其他 mod 一起卸了。
        // 这里按 modUri 摘，listByTargetHash 按包查剩余，两者不再混用。
        val removal = repository.remove(modUri)
        val remaining = repository.listByTargetHash(targetHash).map { it.modUri }
        return SingleModRemoval(listOf(LedgerNotice("账本移除", removal)), remaining)
    }

    /**
     * 记录还在但源文件均缺失、重打包无从下手时的整包账本清理。
     * 返回本次写通知；调用方随后走整包还原。
     */
    fun clearTargetLedger(targetHash: String): List<LedgerNotice> {
        val removal = repository.removeByTargetHash(targetHash)
        return listOf(LedgerNotice("账本清理", removal))
    }
}

/** 摘记录的结果：写通知 + 同包剩余 mod uri（空表示该包已无 mod）。 */
data class SingleModRemoval(
    val notices: List<LedgerNotice>,
    val remainingModUris: List<String>
)
