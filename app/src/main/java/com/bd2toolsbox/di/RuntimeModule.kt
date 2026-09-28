package com.bd2toolsbox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 可注入的时钟（T17）。
 *
 * 统一时间来源，让依赖「当前时刻」的逻辑在测试里可控（fake 成固定值），而不是各处直接
 * 调 `System.currentTimeMillis()`。默认实现就是系统钟；本任务只提供这个 seam，不强行把
 * 现有 11 处 `System.currentTimeMillis()` 全部改写（那属于按需迁移，无关调用点不动）。
 */
fun interface Clock {
    fun nowMs(): Long
}

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock { System.currentTimeMillis() }
}
