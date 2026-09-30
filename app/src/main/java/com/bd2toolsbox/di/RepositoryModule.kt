package com.bd2toolsbox.di

import android.content.Context
import com.bd2toolsbox.data.repository.AvatarRepository
import com.bd2toolsbox.data.repository.Bd2RedeemRepository
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterMetaRepository
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
 *  - `SpineRuntimeRepository`/`UpdateRepository`（T20）已完全迁移为构造注入，自带
 *    `@Inject constructor`，Hilt 直接识别，不需要在此模块重复声明 `@Provides`。
 *  - `CharacterMetaRepository`/`AvatarRepository`/`GamekeeRepository`/`Bd2RedeemRepository`
 *    （T20）仍保留 `private constructor` + `get(context)` 静态入口——还有 Composable
 *    （`CharacterScreen`/`AvatarImage`/`CdkScreen`/`GuideScreen.GuideThumb`）没有接入
 *    Hilt，两边必须是**同一个**单例，否则会出现两份缓存。下面四个 `@Provides`
 *    均委托到 `get()` 的惰性单例。
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

    @Provides
    @Singleton
    fun provideCharacterMetaRepository(@ApplicationContext context: Context): CharacterMetaRepository =
        CharacterMetaRepository.get(context)

    @Provides
    @Singleton
    fun provideAvatarRepository(@ApplicationContext context: Context): AvatarRepository =
        AvatarRepository.get(context)

    @Provides
    @Singleton
    fun provideGamekeeRepository(@ApplicationContext context: Context): GamekeeRepository =
        GamekeeRepository.get(context)

    @Provides
    @Singleton
    fun provideBd2RedeemRepository(@ApplicationContext context: Context): Bd2RedeemRepository =
        Bd2RedeemRepository.get(context)
}
