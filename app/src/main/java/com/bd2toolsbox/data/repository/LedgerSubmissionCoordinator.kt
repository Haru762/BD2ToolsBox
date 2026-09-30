package com.bd2toolsbox.data.repository

import com.bd2toolsbox.core.storage.JournalTarget
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 一个 `filesDir` 目录的**同进程共享**提交协调器（T08 剩余要求 1：统一并发所有者）。
 *
 * ## 它解决什么
 *
 * 账本写入是「读全表→改一条→写回全表」的 read-modify-write；journal 推进也是读-改-写。
 * 之前锁与在途 owner 都挂在 [InstalledModRepository] **实例**上，于是同一 `filesDir`
 * 上的两个仓储实例（同进程重复初始化）互不可见 —— 两次提交叠加、RMW 交错丢记录，
 * 在途门禁形同虚设。
 *
 * 本类按 **canonical 路径**归一：同一目录的所有实例拿到**同一个**协调器，于是：
 *   - [lock] 是**同一把可重入锁**，罩住整段 ledger RMW 与全部 journal 读写/门禁/owner 变更；
 *   - [ownerToken]/[operationId]/[targets] 是**共享的在途租约**，跨实例可见。
 *
 * ## 租约（lease）：共享 owner ≠ 谁都能推进
 *
 * 共享 owner 不等于任意实例都能 `mark*`/`finish`/`release` 别人的提交。租约含
 * [ownerToken]（持有者实例的身份）+ [operationId] + [targets]：**只有持有 [ownerToken]
 * 的那个实例**能推进阶段或释放；迟到的旧 [operationId] 不能解锁已被接任的新 owner。
 *
 * ## 边界
 *
 * **只做同进程互斥**。不同 Android 进程之间不互斥（那需要文件锁 / 跨进程原语），
 * 本类不承诺，也不假装承诺 —— 与既有实现的口径一致，只是把「实例级」提升到「进程级」。
 * [lastError] **不**放这里：它是每实例的诊断状态，合成全局会让 A 的失败被 B 取走。
 */
internal class LedgerSubmissionCoordinator private constructor() {

    /** 同一把可重入锁，罩住 ledger RMW + journal 读写 + 门禁 + owner 变更。 */
    val lock = ReentrantLock()

    /** 在途租约的持有者身份（某个仓储实例的 token）；null = 当前无人持有。 */
    var ownerToken: Any? = null

    /** 在途提交的 id；一次 begin 到 finish/release 之间不变。 */
    var operationId: String? = null

    /** 在途提交的目标摘要（begin 时记下，`mark*` 默认沿用）。 */
    var targets: List<JournalTarget> = emptyList()

    /** 恢复租约持有者；与提交租约分开，避免观察期间普通 put/submit 绕过。 */
    var recoveryOwnerToken: Any? = null

    /** 每次恢复独有的身份，避免旧 finally 释放同一实例后来获得的新租约。 */
    var recoveryLeaseToken: Any? = null

    /** beginRecovery 时 journal 文件的 SHA-256；apply 前必须一致。 */
    var recoveryJournalFingerprint: String? = null

    /** beginRecovery 时账本文件的 SHA-256；apply 前必须一致。 */
    var recoveryLedgerFingerprint: String? = null

    /** 清空租约（收尾/释放时调用，须在 [lock] 内）。 */
    fun clearLease() {
        ownerToken = null
        operationId = null
        targets = emptyList()
    }

    fun clearRecoveryLease() {
        recoveryOwnerToken = null
        recoveryLeaseToken = null
        recoveryJournalFingerprint = null
        recoveryLedgerFingerprint = null
    }

    companion object {
        private val coordinators = ConcurrentHashMap<String, LedgerSubmissionCoordinator>()

        /**
         * 取得该目录的共享协调器。按 **canonical 路径**归一：`dir` 与 `dir/./` 等别名
         * 指向同一物理目录时拿到同一个协调器（canonical 解析失败时退回绝对路径）。
         */
        fun forDir(filesDir: File): LedgerSubmissionCoordinator {
            val key = try {
                filesDir.canonicalPath
            } catch (e: Exception) {
                filesDir.absolutePath
            }
            return coordinators.computeIfAbsent(key) { LedgerSubmissionCoordinator() }
        }
    }
}
