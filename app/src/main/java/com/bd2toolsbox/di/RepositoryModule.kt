package com.bd2toolsbox.di

import android.content.Context
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
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
 *  - T20 的四个 UI 仓储也已改为构造注入；叶节点通过 UiRepositories 取得同一图的实例，
 *    本模块不再委托到进程级静态 get()，也不重复提供构造绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /**
     * 提交执行的宿主（T22）：生产把提交交给 `InstallService`。
     *
     * 这是**必须**在这里绑而不是在 ViewModel 里 new 的原因：JVM 用例要能替换成
     * [com.bd2toolsbox.feature.install.DirectMoveCommitHost] 来验证提交语义本身，
     * 而生产路径由设备用例 `InstallServiceRunHostTest` 覆盖。
     */

    /**
     * 提交执行的宿主（T22）：生产把提交交给 `InstallService`（自己的 scope + 前台服务，
     * 界面销毁不会取消它）。绑在接口上是为了让 JVM 用例能换成
     * [com.bd2toolsbox.feature.install.DirectMoveCommitHost] 验证提交语义本身；
     * 生产路径由设备用例 `InstallServiceRunHostTest` 覆盖。
     */
    /**
     * 扫描执行的宿主（T23）：生产把扫描交给 `ScanService`（自己的 scope + 前台通知）。
     * 与 MoveCommitHost 同一理由与同一可替换性。
     */
    @Provides
    @Singleton
    fun provideScanRunHost(
        impl: com.bd2toolsbox.service.ScanService.Host
    ): com.bd2toolsbox.feature.mods.ScanRunHost = impl

    @Provides
    @Singleton
    fun provideMoveCommitHost(
        impl: com.bd2toolsbox.service.InstallService.Host
    ): com.bd2toolsbox.feature.install.MoveCommitHost = impl

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
}
