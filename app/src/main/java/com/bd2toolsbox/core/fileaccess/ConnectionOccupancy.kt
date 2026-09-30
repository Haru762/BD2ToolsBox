package com.bd2toolsbox.core.fileaccess

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯粹的占用计数（T19）：谁在用一份共享资源、最后一个用完时通知释放。
 *
 * 从 [ShizukuGameFileAccess] 抽出便于独立测试——真正的 `unbindService()` 会触碰
 * `Shizuku` 的静态初始化（JVM 单测环境没有真实 Binder，直接调用会崩），
 * 而占用计数本身是纯逻辑，不该依赖 Android/Shizuku 才能验证正确性。
 *
 * 用集合而不是简单整数计数：同一个 token 被误 release 两次不会把计数减到负数，
 * 调试时也能看清「到底还有谁占着」。线程安全：acquire/release 可能来自不同线程
 * （installScope 的 IO 调度线程、Main 线程的 UI 触发）。
 */
internal class ConnectionOccupancy(private val onAllReleased: () -> Unit) {

    private val activeTokens: MutableSet<Any> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /** 当前占用者数量，仅供测试观察。 */
    val activeCount: Int get() = activeTokens.size

    fun acquire(): Any {
        val token = Any()
        activeTokens.add(token)
        return token
    }

    /**
     * 释放一次占用；重复释放同一个 token 是 no-op，**不**会再次触发 [onAllReleased]。
     * 关键在 `remove()` 的返回值：只有这次调用**真正移除了**一个 token 才可能让集合变空，
     * 若直接看 `isEmpty()` 不看 `remove()` 的返回值，重复 release 同一个已经不在集合里的
     * token 会因为集合本来就是空的而重复触发回调。
     */
    fun release(token: Any) {
        val actuallyRemoved = activeTokens.remove(token)
        if (actuallyRemoved && activeTokens.isEmpty()) {
            onAllReleased()
        }
    }
}
