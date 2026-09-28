package com.bd2toolsbox.di

import com.bd2toolsbox.core.engine.ChaquopyModEngine
import com.bd2toolsbox.core.engine.ChaquopyPyCaller
import com.bd2toolsbox.core.engine.ModEngine
import com.bd2toolsbox.core.engine.PyCaller
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Kotlin–Python 引擎适配层的注入（T18）。
 *
 * - [PyCaller] 绑定到生产的 [ChaquopyPyCaller]（真正走 Chaquopy）。
 * - [ModEngine] 提供**唯一**单例，并在构造时 [ModEngine.install] 注册为进程级默认——
 *   这样 Hilt 注入方（MainViewModel、PrepackService）与静态调用方
 *   （ShizukuManager/BundleNameResolver/ModRepository 经 `ModEngine.get()`，T20 清理）
 *   拿到同一实例，不会出现两份桥。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindPyCaller(impl: ChaquopyPyCaller): PyCaller

    companion object {
        @Provides
        @Singleton
        fun provideModEngine(impl: ChaquopyModEngine): ModEngine {
            ModEngine.install(impl)
            return impl
        }
    }
}
