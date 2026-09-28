package com.bd2toolsbox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 协程调度器的注入（T17）。
 *
 * 生产代码里散落着直接写死的 `Dispatchers.IO` / `Dispatchers.Default`；把它们改成注入，
 * 纯逻辑测试就能换成 `StandardTestDispatcher` / `UnconfinedTestDispatcher`，不用真起线程池。
 * 用 `@Qualifier` 区分两种调度器，避免 Hilt 因「同一类型 `CoroutineDispatcher` 多个绑定」报错。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
