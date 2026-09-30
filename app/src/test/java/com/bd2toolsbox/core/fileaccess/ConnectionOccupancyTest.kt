package com.bd2toolsbox.core.fileaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ConnectionOccupancy] 的占用计数（T19）：Activity 销毁不再直接触发释放，
 * 而是"最后一个占用者释放"才触发。这里验证纯计数逻辑本身，不涉及真实
 * Shizuku/Binder（那部分只能在设备上验证）。
 */
class ConnectionOccupancyTest {

    @Test
    fun releaseIsNoOpWhileOtherTokensAreStillHeld() {
        var released = 0
        val occupancy = ConnectionOccupancy { released++ }
        val activityToken = occupancy.acquire()
        val backgroundTaskToken = occupancy.acquire()

        occupancy.release(activityToken)

        assertEquals("released 只应在全部 token 都释放后触发", 0, released)
        assertEquals(1, occupancy.activeCount)
    }

    @Test
    fun releaseFiresOnlyAfterTheLastTokenIsReleased() {
        var released = 0
        val occupancy = ConnectionOccupancy { released++ }
        val a = occupancy.acquire()
        val b = occupancy.acquire()

        occupancy.release(a)
        assertEquals(0, released)
        occupancy.release(b)
        assertEquals(1, released)
        assertEquals(0, occupancy.activeCount)
    }

    @Test
    fun releasingTheSameTokenTwiceDoesNotFireTheCallbackAgain() {
        var released = 0
        val occupancy = ConnectionOccupancy { released++ }
        val token = occupancy.acquire()

        occupancy.release(token)
        assertEquals(1, released)

        // 重复 release 同一个已经不在集合里的 token：回调不能再次触发。关键在
        // remove() 的返回值——若只看 activeTokens.isEmpty()（不看 remove() 是否真正
        // 移除了元素），重复 release 会因为集合本来就是空的而重复触发——这在
        // ShizukuManager.unbindService() 的无参测试重载路径（自己构造一个临时 token
        // 立即 acquire+release）中会真实发生：若当时已有其他真实占用者刚好在同一刻
        // 释放完零，这个无参重载会对已经空的集合再 release 一次自己的 token。
        occupancy.release(token)
        assertEquals(1, released)
    }

    @Test
    fun onlyANewAcquireAfterFullReleaseCanBlockTheNextRelease() {
        var released = 0
        val occupancy = ConnectionOccupancy { released++ }
        val first = occupancy.acquire()
        occupancy.release(first)
        assertEquals(1, released)

        // 全部释放后，新的 acquire 必须能重新建立占用
        val second = occupancy.acquire()
        assertEquals(1, occupancy.activeCount)
        occupancy.release(second)
        assertEquals(2, released)
    }

    @Test
    fun concurrentAcquireAndReleaseNeverUnderOrOverCountsReleaseCallback() {
        // 100 个线程各自 acquire 一次并长常占用自己那个线程（直到被显式 shutdown），
        // 再另外用一个独立的固定线程池并发 release：released 必须恰好触发 1 次
        // （最后一个释放的那次），不能因为竞态触发 0 次或多次。
        //
        // 用两个独立的 executor 而不是同一个：如果 acquire 任务与 release 任务
        // 共用同一个有限线程池，而 acquire 任务长时间占着线程等 gate，会把池子止满，
        // 后面提交的任务排队无限等待。
        val threads = 100
        val released = AtomicInteger(0)
        val occupancy = ConnectionOccupancy { released.incrementAndGet() }
        val tokens = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val acquireLatch = CountDownLatch(threads)
        val releaseGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)
        val acquirePool = Executors.newFixedThreadPool(threads)
        val releasePool = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            acquirePool.submit {
                tokens.add(occupancy.acquire())
                acquireLatch.countDown()
                releaseGate.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(acquireLatch.await(5, TimeUnit.SECONDS))
        assertEquals(threads, occupancy.activeCount)

        val allTokens = tokens.toList()
        releaseGate.countDown()
        allTokens.forEach { token ->
            releasePool.submit {
                occupancy.release(token)
                doneLatch.countDown()
            }
        }
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
        acquirePool.shutdown()
        releasePool.shutdown()

        assertEquals("并发释放下回调必须恰好触发 1 次", 1, released.get())
        assertEquals(0, occupancy.activeCount)
    }
}
