package com.bd2toolsbox.di

import com.bd2toolsbox.core.engine.ModEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** T20 清理旧静态调用方前，DI 与旧入口使用同一个不可替换的惰性实例。 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun provideModEngine(): ModEngine = ModEngine.get()
}
