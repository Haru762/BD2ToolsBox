package com.bd2toolsbox.di

import android.content.Context
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.GamekeeRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 有状态 Repository 的应用级单一实例（T17）。
 *
 * 以前这些仓储在 `MainViewModel.initRepositories(context)` 里 `new` 出来；Activity 被 MIUI
 * 回收重建时会得到**新的一批**仓储实例。改成 Hilt `@Singleton` 后，整个应用图里每种仓储
 * 恰好一个实例——这正是验收要求的「同一应用图不会构造多个可竞争写账本的仓库」。
 *
 * 说明：
 *  - `InstalledModRepository` 的账本单写不变量本来就由 `LedgerSubmissionCoordinator.forDir`
 *    的进程级锁保证；`@Singleton` 在此之上再消除「多实例各持一份 lastError / 缓存」的分叉。
 *  - 全部只依赖 `@ApplicationContext`，不持有 Activity Context，可安全长寿命。
 *  - `ModRepository` 依赖 `CharacterRepository`，由 Hilt 按图装配同一个单例。
 *  - `BundleBackupRepository` 的 `digester` 用其构造默认值（`StreamingDigest.sha256()`）；
 *    需要 fake 时测试直接 `new`，不经此模块。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideCharacterRepository(@ApplicationContext context: Context): CharacterRepository =
        CharacterRepository(context)

    @Provides
    @Singleton
    fun provideModRepository(
        @ApplicationContext context: Context,
        characterRepository: CharacterRepository,
    ): ModRepository = ModRepository(context, characterRepository)

    @Provides
    @Singleton
    fun provideInstalledModRepository(@ApplicationContext context: Context): InstalledModRepository =
        InstalledModRepository(context)

    @Provides
    @Singleton
    fun provideBundleBackupRepository(@ApplicationContext context: Context): BundleBackupRepository =
        BundleBackupRepository(context)

    @Provides
    @Singleton
    fun providePreviewCacheRepository(@ApplicationContext context: Context): PreviewCacheRepository =
        PreviewCacheRepository(context)

    /**
     * 攻略阅读器的仓储（GuideViewModel 注入）。
     *
     * 委托给旧的 `GamekeeRepository.get()`，而不是直接 `new`——`CdkScreen` / `GuideScreen`
     * 的 Composable 里还在用 `get()` 取同一实例（不经 Hilt），两边必须是**同一个**单例，
     * 否则会出现两份缓存。等后续把这两个 Composable 也改成注入后，可改成直接构造。
     */
    @Provides
    @Singleton
    fun provideGamekeeRepository(@ApplicationContext context: Context): GamekeeRepository =
        GamekeeRepository.get(context)
}
