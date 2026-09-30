package com.bd2toolsbox.di

import com.bd2toolsbox.core.fileaccess.GameFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * T19：与 [EngineModule] 同一个模式——DI 与旧静态门面
 * ([com.bd2toolsbox.service.ShizukuManager]) 必须共享 [GameFileAccess.get] 返回的
 * 同一个惰性单例，否则占用计数（谁还在用连接）会因为两份实例分裂而失真。
 */
@Module
@InstallIn(SingletonComponent::class)
object FileAccessModule {
    @Provides
    @Singleton
    fun provideGameFileAccess(): GameFileAccess = GameFileAccess.get()
}
