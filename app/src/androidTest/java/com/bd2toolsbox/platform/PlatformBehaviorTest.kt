package com.bd2toolsbox.platform

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.MainActivity
import com.bd2toolsbox.data.model.InstallJob
import com.bd2toolsbox.data.model.JobStatus
import com.bd2toolsbox.data.model.RepackJob
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.data.repository.UpdateRepository
import com.bd2toolsbox.service.InstallService
import com.bd2toolsbox.service.PrepackService
import com.bd2toolsbox.service.ScanService
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * T15 平台行为：SDK / 权限 / 前台服务 / 更新安装 / 16 KB 页。
 *
 * 每条用例都在 API 29、34、36（16 KB 页）三台 x86_64 AVD 上**全部真跑**，不用
 * `Assume` —— `Test-Bd2Device` 把 skip 判成失败（brief §4.6）。按 API 分支的地方
 * 两边都有断言，不是「低版本直接 return」。设备上真跑不了的（Shizuku、dataSync
 * 6 小时超时、系统安装器确认页之后的结果）不写进这里，登记在 T15.md 的未覆盖节。
 *
 * ## 前台服务「被拒」为什么用注入而不是真的切后台
 *
 * instrumentation 与 app 同进程，而**正在被 instrument 的进程**会被 AM 钉在
 * `PROCESS_STATE_FOREGROUND_SERVICE`（实测 API 34 logcat：回桌面后仍是
 * `Background started FGS: Allowed … uidState: FGS`）—— 同进程测试里永远构造不出
 * 系统拒绝。所以在 `Context.startForegroundService` 这条缝上注入系统的真实异常
 * （API 31+ 是 `ForegroundServiceStartNotAllowedException`，更早是
 * `IllegalStateException`），驱动的是**生产的** start / processInstallJobs。
 * 「前台时允许启动」那条仍是真启动、真断言服务进入前台状态。
 */
@RunWith(AndroidJUnit4::class)
class PlatformBehaviorTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val pkg get() = context.packageName

    /**
     * T17：appContext 与仓储改为构造注入后，测试用指定 context 直接构造 MainViewModel，
     * 不再反射 set 私有字段。仓储都建在同一个 context 上（filesDir 一致），保持账本单一事实源。
     */
    private fun newViewModel(ctx: Context): MainViewModel {
        val character = CharacterRepository(ctx)
        val metadata = com.bd2toolsbox.data.repository.CharacterMetaRepository(ctx)
        val installed = InstalledModRepository(ctx)
        return MainViewModel(
            SavedStateHandle(),
            ctx,
            character,
            ModRepository(ctx, character),
            installed,
            BundleBackupRepository(ctx),
            PreviewCacheRepository(ctx),
            com.bd2toolsbox.core.engine.ChaquopyModEngine(),
            metadata,
            com.bd2toolsbox.data.repository.AvatarRepository(ctx, metadata),
            com.bd2toolsbox.data.repository.SpineRuntimeRepository(ctx),
            com.bd2toolsbox.data.repository.UpdateRepository(ctx),
            com.bd2toolsbox.feature.install.DirectMoveCommitHost(com.bd2toolsbox.feature.install.InstallExecutor(installed, com.bd2toolsbox.core.fileaccess.GameFileAccess.get())),
            com.bd2toolsbox.feature.install.UninstallExecutor(installed),
            com.bd2toolsbox.feature.mods.DirectScanRunHost(com.bd2toolsbox.feature.mods.ScanExecutor(com.bd2toolsbox.core.fileaccess.GameFileAccess.get())),
            com.bd2toolsbox.feature.mods.ScanExecutor(com.bd2toolsbox.core.fileaccess.GameFileAccess.get()),
            com.bd2toolsbox.feature.preview.PrepackExecutor(com.bd2toolsbox.data.repository.PreviewCacheRepository(ctx)),
        )
    }

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }

    /**
     * startForegroundService 一律按系统拒绝处理的 Context（其余照常转发）。
     * applicationContext 仍给真实 Application：生产代码（PythonRuntime、AndroidPlatform）
     * 会把它强转成 Application —— 被测的 start 调用点都直接用传入的 context 本身。
     */
    private fun refusingContext(): Context = object : android.content.ContextWrapper(context) {
        override fun startForegroundService(service: Intent): ComponentName? {
            val message = "T15 injected: startForegroundService() not allowed"
            throw if (Build.VERSION.SDK_INT >= 31) {
                android.app.ForegroundServiceStartNotAllowedException(message)
            } else {
                IllegalStateException(message)
            }
        }
    }

    private fun bringAppToForeground() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline && !isTopActivityOurs()) SystemClock.sleep(300)
        assertTrue("MainActivity 没能进入前台", isTopActivityOurs())
        SystemClock.sleep(1_500)
    }

    private fun isTopActivityOurs(): Boolean =
        shell("dumpsys activity activities").lineSequence()
            .firstOrNull { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
            ?.contains(pkg) == true

    private fun notificationManager() = context.getSystemService(NotificationManager::class.java)!!

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(200)
        }
        return condition()
    }

    private fun isServiceForeground(cls: Class<*>): Boolean =
        shell("dumpsys activity services ${cls.name}").let { it.contains("isForeground=true") }

    @Before
    fun resetServices() {
        InstallService.stop(context)
        ScanService.stop(context)
    }

    @After
    fun cleanup() {
        InstallService.stop(context)
        ScanService.stop(context)
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
    }

    // ------------------------------------------------------------------ SDK / Manifest

    /** 发版目标的三个数字（T15 裁定：minSdk 29 / compileSdk 36 / targetSdk 34）。 */
    @Test
    fun sdkLevelsAreTheRecordedTargets() {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        assertEquals("minSdk 必须保持 29（Android 10+）", 29, info.minSdkVersion)
        assertEquals("targetSdk 由 T15 定为 34；改动它是一项单独的行为升级", 34, info.targetSdkVersion)
        if (Build.VERSION.SDK_INT >= 31) {
            assertEquals("compileSdk 36（T14）", 36, info.compileSdkVersion)
        } else {
            // compileSdkVersion 字段 API 31 才有；低版本从包里读不到，改查不会被裁掉的常量
            assertTrue("API ${Build.VERSION.SDK_INT} 上照样能拿到 ApplicationInfo", info.targetSdkVersion == 34)
        }
    }

    /**
     * 权限声明白名单：只有这些，且不含「全部文件访问」与旧存储权限（T15 删除）。
     * 新增权限必须过这里 —— 它会改变用户在安装/更新时看到的权限列表。
     *
     * MuMu 的包解析器会给**每个**包追加 QUERY_ALL_PACKAGES（实测：装上的 base.apk 与构建
     * 产物逐字节相同、APK 里没有这项，getPackageArchiveInfo 照样报出来）。只有当对照包 ——
     * 本测试 APK，清单里只有 REORDER_TASKS —— 也被报出同一项时，才认定是设备注入并剔除；
     * 否则它就是本 app 真的声明了，照常判失败。
     */
    @Test
    fun declaredPermissionsAreExactlyTheUsedOnes() {
        fun requestedOf(apk: String) = context.packageManager
            .getPackageArchiveInfo(apk, PackageManager.GET_PERMISSIONS)!!
            .requestedPermissions.orEmpty().toSet()
        val control = requestedOf(instrumentation.context.applicationInfo.sourceDir)
        val injected = control - Manifest.permission.REORDER_TASKS
        val requested = requestedOf(context.applicationInfo.sourceDir) - injected
        if (injected.isNotEmpty()) android.util.Log.i("PlatformBehaviorTest", "设备注入的权限（已剔除）：$injected")
        val expected = setOf(
            Manifest.permission.INTERNET,
            "moe.shizuku.manager.permission.API_V23",
            Manifest.permission.FOREGROUND_SERVICE,
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            "android.permission.POST_NOTIFICATIONS",
            // 库合并进来的：androidx.core 的动态广播接收器权限（签名级，仅本 app）
            "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        assertEquals("声明的权限必须恰好是这些", expected, requested)
        assertFalse(requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertFalse(requested.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    /** 三个前台服务：不导出、类型 dataSync；外部组件身份（authority）不变。 */
    @Test
    fun componentIdentityAndForegroundServiceTypes() {
        val pm = context.packageManager
        for (cls in listOf(InstallService::class.java, ScanService::class.java, PrepackService::class.java)) {
            val info = pm.getServiceInfo(ComponentName(context, cls), 0)
            assertFalse("${cls.simpleName} 不应导出", info.exported)
            assertEquals(
                "${cls.simpleName} 的前台服务类型必须是 dataSync",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                info.foregroundServiceType
            )
        }
        val fileProvider = pm.resolveContentProvider("$pkg.fileprovider", 0)
        assertNotNull("FileProvider authority 必须仍是 \${applicationId}.fileprovider", fileProvider)
        assertFalse(fileProvider!!.exported)
        assertTrue(fileProvider.grantUriPermissions)
        assertNotNull("Shizuku provider authority 不变", pm.resolveContentProvider("$pkg.shizuku", 0))
    }

    // ------------------------------------------------------------------ 前台服务

    /** 前台时允许启动：服务真的进入前台状态（不是只看 start 没抛）。 */
    @Test
    fun foregroundServiceStartsWhenAppIsInForeground() {
        bringAppToForeground()
        assertTrue("前台时 start 应返回 true", InstallService.start(context, 3))
        assertTrue(
            "InstallService 应进入前台状态",
            waitFor(5_000) { isServiceForeground(InstallService::class.java) }
        )
        InstallService.stop(context)
        assertTrue("stop 后服务应离开前台", waitFor(5_000) { !isServiceForeground(InstallService::class.java) })
    }

    /**
     * 系统拒绝启动前台服务时，三个服务的 start 都如实返回 false（此前 Install/Scan 只打
     * 一行日志、调用方照常开跑），且没有服务残留在前台。
     */
    @Test
    fun refusedForegroundServiceStartIsReported() {
        val refusing = refusingContext()
        assertFalse("InstallService.start 被拒必须返回 false", InstallService.start(refusing, 1))
        assertFalse("ScanService.start 被拒必须返回 false", ScanService.start(refusing, 1))
        val target = com.bd2toolsbox.feature.preview.PrepackTarget("content://t15/tree/x", "b", "h", 1L, "t15")
        assertFalse("PrepackService.start 被拒必须返回 false", PrepackService.start(refusing, listOf(target)))
        SystemClock.sleep(1_000)
        for (cls in listOf(InstallService::class.java, ScanService::class.java, PrepackService::class.java)) {
            assertFalse("${cls.simpleName} 被拒后不应在前台", isServiceForeground(cls))
        }
        // 对照：同一进程、真实 Context 下照常启动 —— 证明上面的 false 来自拒绝而不是恒假
        bringAppToForeground()
        assertTrue(InstallService.start(context, 1))
        assertTrue(waitFor(5_000) { isServiceForeground(InstallService::class.java) })
    }

    /**
     * 被拒时生产流程真的「安全停止并反馈」：从 MainViewModel 的装入入口走一遍，
     * 每个 job 都成了 Failed（写着原因），而不是照常开跑去改游戏文件、写账本。
     */
    @Test
    fun refusedForegroundServiceFailsInstallBatchWithReason() {
        val store = ViewModelStore()
        val refusing = refusingContext()
        // T17：appContext 与仓储改为构造注入，直接用 refusing context 构造 VM。
        val vm = newViewModel(refusing)
        store.put("t15-platform", vm)
        try {
            @Suppress("UNCHECKED_CAST")
            val jobs = MainViewModel::class.java.getDeclaredField("_installJobs").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<List<InstallJob>>
            jobs.value = listOf(
                InstallJob(RepackJob("t15fgsrefusedaaaaaaaaaaaaaaaaaaaa", emptyList())),
                InstallJob(RepackJob("t15fgsrefusedbbbbbbbbbbbbbbbbbbbb", emptyList()))
            )
            MainViewModel::class.java.getDeclaredMethod("processInstallJobs", Context::class.java)
                .apply { isAccessible = true }.invoke(vm, refusing)

            val refused = MainViewModel.FGS_REFUSED_MESSAGE
            assertTrue(
                "两个 job 都应标成 Failed（$refused），实际 ${jobs.value.map { it.status }}",
                waitFor(10_000) {
                    jobs.value.all { (it.status as? JobStatus.Failed)?.displayMessage == refused }
                }
            )
            assertTrue(
                "失败明细要说明目标未改动",
                jobs.value.all { (it.status as JobStatus.Failed).detailedLog.contains("目标未改动") }
            )
            // 汇总照常产出（结果框据此弹出），失败数 = 2、成功 0。生产里由 initialize() 挂上的
            // installJobs 收集器触发；本用例不走 initialize，直接调同一个 summarizeResults。
            MainViewModel::class.java.getDeclaredMethod("summarizeResults").apply { isAccessible = true }.invoke(vm)
            assertNotNull(vm.finalInstallResult.value)
            assertEquals(2, vm.finalInstallResult.value!!.failedJobs)
            assertEquals(0, vm.finalInstallResult.value!!.successfulJobs)
            assertFalse(isServiceForeground(InstallService::class.java))
        } finally {
            store.clear()
        }
    }

    /**
     * 通知被拒：前台服务照样能启动、进入前台（保活不依赖通知权限），只是通知不显示。
     *
     * API 33+ 用 `PermissionManager.revokePostNotificationPermissionWithoutKillForTest`
     * （@TestApi，instrumentation 默认可用）撤销：`pm revoke` 会杀掉 app 进程，而测试与
     * app 同进程。API 29 没有这项运行时权限，改用「通知总开关」等价的 appop 不存在 ——
     * 那一侧断言的是「通知默认可用、无需请求」且服务照常。
     */
    @Test
    fun deniedNotificationPermissionDoesNotBlockForegroundService() {
        if (Build.VERSION.SDK_INT >= 33) {
            revokeNotificationsWithoutKill()
            assertEquals(
                PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            )
            assertTrue("夹具前提：通知必须已被关掉", waitFor(3_000) { !notificationManager().areNotificationsEnabled() })
        } else {
            assertTrue("API ${Build.VERSION.SDK_INT}：通知默认可用", notificationManager().areNotificationsEnabled())
        }
        try {
            bringAppToForeground()
            assertTrue(InstallService.start(context, 2))
            assertTrue(
                "拒绝通知不应影响前台服务状态",
                waitFor(5_000) { isServiceForeground(InstallService::class.java) }
            )
            val visible = notificationManager().activeNotifications.any { it.id == 1002 }
            assertEquals("通知可见性应跟随权限", Build.VERSION.SDK_INT < 33, visible)
            InstallService.stop(context)
        } finally {
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    private fun revokeNotificationsWithoutKill() {
        val ui = instrumentation.uiAutomation
        ui.adoptShellPermissionIdentity()
        try {
            val pm = context.getSystemService("permission")!!
            pm.javaClass.getMethod(
                "revokePostNotificationPermissionWithoutKillForTest",
                String::class.java, Int::class.javaPrimitiveType
            ).invoke(pm, pkg, android.os.Process.myUserHandle().hashCode())
        } finally {
            ui.dropShellPermissionIdentity()
        }
    }

    // ------------------------------------------------------------------ SAF

    /**
     * SAF 持久授权被撤销：启动恢复时该目录被移出列表，**并且**有用户可见的提示
     * （lostSourceDirsNotice），不是悄悄变成空列表。
     *
     * 用一个本 app 从未获得授权的 tree URI 代表「已被撤销」—— 对 restoreModSourceDirs
     * 来说两者等价：persistedUriPermissions 里都没有它。
     */
    @Test
    fun revokedSafGrantIsReportedNotSilentlyDropped() {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("mod_source_dirs", null)
        val revoked = "content://com.android.externalstorage.documents/tree/primary%3AT15Revoked"
        val granted = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        assertFalse("夹具前提：该 URI 不应在持久授权里", revoked in granted)
        val store = ViewModelStore()
        val vm = newViewModel(context)
        store.put("t15-saf", vm)
        try {
            prefs.edit().putString("mod_source_dirs", (granted + revoked).joinToString("\n")).commit()
            MainViewModel::class.java.getDeclaredMethod("restoreModSourceDirs", android.content.SharedPreferences::class.java)
                .apply { isAccessible = true }.invoke(vm, prefs)

            assertFalse(
                "撤销的目录不应留在列表里（读不出任何东西）",
                vm.modSourceDirs.value.any { it.toString() == revoked }
            )
            assertEquals("必须把失效目录告诉用户", listOf(revoked), vm.lostSourceDirsNotice.value)
            vm.dismissLostSourceDirsNotice()
            assertNull(vm.lostSourceDirsNotice.value)
        } finally {
            store.clear()
            prefs.edit().apply { if (saved == null) remove("mod_source_dirs") else putString("mod_source_dirs", saved) }.commit()
        }
    }

    /** 授权还在的目录照常恢复，不弹提示（上一条的对照组）。 */
    @Test
    fun validSourceDirsRestoreWithoutNotice() {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val saved = prefs.getString("mod_source_dirs", null)
        val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
            .map { it.uri.toString() }
        val store = ViewModelStore()
        val vm = newViewModel(context)
        store.put("t15-saf-ok", vm)
        try {
            prefs.edit().putString("mod_source_dirs", granted.joinToString("\n")).commit()
            MainViewModel::class.java.getDeclaredMethod("restoreModSourceDirs", android.content.SharedPreferences::class.java)
                .apply { isAccessible = true }.invoke(vm, prefs)
            assertEquals(granted, vm.modSourceDirs.value.map { it.toString() })
            assertNull("没有失效目录时不应提示", vm.lostSourceDirsNotice.value)
        } finally {
            store.clear()
            prefs.edit().apply { if (saved == null) remove("mod_source_dirs") else putString("mod_source_dirs", saved) }.commit()
        }
    }

    // ------------------------------------------------------------------ 更新安装

    /**
     * 下载完成广播能被 MainActivity 动态注册的接收器收到（只认自己那个下载 id）；
     * FileProvider 能为 updates/ 下的包发 content URI，未知来源授权按系统状态如实报告。
     * 系统安装器的确认页由用户操作，不在自动化范围（登记在 T15.md）。
     */
    @Test
    fun updateInstallPathUsesFileProviderAndUnknownSourcesGate() {
        val repo = com.bd2toolsbox.data.repository.UpdateRepository(context)
        val apk = File(repo.updateDir(), "t15-probe.apk")
        try {
            apk.writeBytes(ByteArray(64) { 1 })
            val uri = FileProvider.getUriForFile(context, "$pkg.fileprovider", apk)
            assertEquals("content", uri.scheme)
            assertEquals("$pkg.fileprovider", uri.authority)
            context.contentResolver.openInputStream(uri)!!.use { assertEquals(64, it.readBytes().size) }

            // 安装 Intent 能被系统安装器解析（真机/模拟器都有 packageinstaller）
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertNotNull("系统应有安装器接这个 Intent", view.resolveActivity(context.packageManager))

            // 「安装未知应用」：新装默认未授予 → 生产走「先跳设置页」分支，那个设置页必须存在。
            // 不在这里切 appop：改 REQUEST_INSTALL_PACKAGES 会让系统杀掉 app 进程（实测
            // `Killing …: REQUEST_INSTALL_PACKAGES changed`），而测试与 app 同进程。
            // 授予后的 true 一侧由 T15.md 里的 adb 步骤核对。
            val mode = shell("cmd appops get $pkg REQUEST_INSTALL_PACKAGES")
            assertEquals(
                "canRequestPackageInstalls 必须与 appop 状态一致（$mode）",
                mode.contains(": allow"),
                context.packageManager.canRequestPackageInstalls()
            )
            val settings = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$pkg")
            )
            assertNotNull("未知来源授权设置页必须存在", settings.resolveActivity(context.packageManager))
        } finally {
            apk.delete()
        }
    }

    /** DownloadManager 完成广播：系统真的投递给 app（动态接收器所依赖的前提）。 */
    @Test
    fun downloadCompleteBroadcastIsDelivered() {
        val dm = context.getSystemService(DownloadManager::class.java)!!
        val received = java.util.concurrent.CountDownLatch(1)
        var gotId = -1L
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                gotId = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                received.countDown()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver,
            android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        // 必然失败的本地地址：完成广播对成功与失败都会发，不依赖外网
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse("http://127.0.0.1:9/t15-none.apk"))
                .setDestinationInExternalFilesDir(context, null, "updates/t15-none.apk")
                // VISIBILITY_HIDDEN 需要 DOWNLOAD_WITHOUT_NOTIFICATION，生产也不用；保持默认
        )
        try {
            assertTrue(
                "60 秒内应收到 ACTION_DOWNLOAD_COMPLETE",
                received.await(60, java.util.concurrent.TimeUnit.SECONDS)
            )
            assertEquals(id, gotId)
            assertFalse("失败的下载不能被当成成功", com.bd2toolsbox.data.repository.UpdateRepository(context).isDownloadSuccessful(id))
        } finally {
            context.unregisterReceiver(receiver)
            dm.remove(id)
        }
    }

    // ------------------------------------------------------------------ 16 KB 页

    /**
     * 本 APK 里自带的 JNI 库（lib/<abi>/）：ELF 每个 PT_LOAD 的 p_align ≥ 16 KB，且在 ZIP
     * 里未压缩存放、数据偏移 16 KB 对齐。两项分开检查（链接对齐 ≠ ZIP 对齐）。
     *
     * 只检查 APK 的 lib/ 目录；Chaquopy wheel 里的 .so 在 .imy 资产内、运行时解压，不走
     * 这条加载路径，登记在 T15.md。
     *
     * [KNOWN_4K_LIBS] 是登记在案的例外（来源不明、无法重编，T15.md §16 KB）：它们必须
     * **仍然**是 4 KB —— 哪天换成了 16 KB 版本，这里会红，提醒去更新登记与兼容模式声明。
     */
    @Test
    fun packagedJniLibrariesAre16KbAligned() {
        val apks = listOfNotNull(context.applicationInfo.sourceDir) +
            context.applicationInfo.splitSourceDirs.orEmpty()
        val checked = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (path in apks) ZipFile(path).use { zip ->
            for (e in zip.entries()) {
                if (!e.name.startsWith("lib/") || !e.name.endsWith(".so")) continue
                if (e.name.substringAfter("lib/").substringBefore('/') !in Build.SUPPORTED_ABIS) continue
                checked += e.name
                if (e.method != java.util.zip.ZipEntry.STORED) problems += "${e.name}: 被压缩"
                val offset = localDataOffset(path, e)
                if (offset % 16384L != 0L) problems += "${e.name}: ZIP 数据偏移 $offset 非 16 KB 对齐"
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                val align = minLoadAlign(bytes)
                val known = e.name.substringAfterLast('/') in KNOWN_4K_LIBS
                if (known && align != 4096L) problems += "${e.name}: 登记为 4 KB 例外，实际 p_align=0x${align.toString(16)}，更新登记"
                if (!known && align < 16384) problems += "${e.name}: PT_LOAD p_align=0x${align.toString(16)}"
            }
        }
        assertTrue("应至少检查到自带的 JNI 库，实际 $checked", checked.any { it.endsWith("/libtexture2ddecoder.so") })
        assertTrue("16 KB 对齐问题：\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    /**
     * 16 KB 页设备上的真实加载：自带 JNI 库能 dlopen（经 System.load，与 ctypes 同一个
     * linker）。4 KB 页设备上同样执行，作为对照。
     */
    @Test
    fun jniLibrariesLoadOnThisPageSize() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        // extractNativeLibs=false：库不落盘，路径形如 base.apk!/lib/<abi>/x.so，System.loadLibrary 走 APK 内映射
        System.loadLibrary("texture2ddecoder")
        System.loadLibrary("astcenc")
        android.util.Log.i("PlatformBehaviorTest", "pageSize=$pageSize nativeLibraryDir=$libDir 加载 OK")
        assertTrue(pageSize == 4096L || pageSize == 16384L)
    }

    private companion object {
        /** 仍只有 4 KB 链接对齐、且当前无法重编的自带 JNI（见 T15.md）。 */
        val KNOWN_4K_LIBS = setOf("libastcenc.so")
    }

    private fun localDataOffset(apk: String, entry: java.util.zip.ZipEntry): Long {
        // ZipEntry 不暴露本地头偏移：从中央目录找，再读本地头的变长字段
        java.io.RandomAccessFile(apk, "r").use { raf ->
            val cd = findCentralDirectory(raf)
            var pos = cd.first
            repeat(cd.second) {
                raf.seek(pos)
                val header = ByteArray(46).also { raf.readFully(it) }
                val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val nameLen = bb.getShort(28).toInt() and 0xffff
                val extraLen = bb.getShort(30).toInt() and 0xffff
                val commentLen = bb.getShort(32).toInt() and 0xffff
                val localOffset = bb.getInt(42).toLong() and 0xffffffffL
                val name = ByteArray(nameLen).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                if (name == entry.name) {
                    raf.seek(localOffset + 26)
                    val lens = ByteArray(4).also { raf.readFully(it) }
                    val lb = java.nio.ByteBuffer.wrap(lens).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val n = lb.getShort(0).toInt() and 0xffff
                    val x = lb.getShort(2).toInt() and 0xffff
                    return localOffset + 30 + n + x
                }
                pos += 46 + nameLen + extraLen + commentLen
            }
        }
        error("central directory entry not found: ${entry.name}")
    }

    private fun findCentralDirectory(raf: java.io.RandomAccessFile): Pair<Long, Int> {
        val len = raf.length()
        val tail = minOf(len, 65_557L).toInt()
        val buf = ByteArray(tail)
        raf.seek(len - tail); raf.readFully(buf)
        for (i in tail - 22 downTo 0) {
            if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() && buf[i + 2] == 5.toByte() && buf[i + 3] == 6.toByte()) {
                val bb = java.nio.ByteBuffer.wrap(buf, i, 22).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val count = bb.getShort(i + 10).toInt() and 0xffff
                val offset = bb.getInt(i + 16).toLong() and 0xffffffffL
                return offset to count
            }
        }
        error("EOCD not found")
    }

    /** ELF64 小端：所有 PT_LOAD 里最小的 p_align。 */
    private fun minLoadAlign(elf: ByteArray): Long {
        val bb = java.nio.ByteBuffer.wrap(elf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        check(elf[0] == 0x7f.toByte() && elf[4] == 2.toByte()) { "not ELF64" }
        val phoff = bb.getLong(0x20)
        val phentsize = bb.getShort(0x36).toInt() and 0xffff
        val phnum = bb.getShort(0x38).toInt() and 0xffff
        var min = Long.MAX_VALUE
        for (i in 0 until phnum) {
            val off = (phoff + i * phentsize).toInt()
            if (bb.getInt(off) == 1) min = minOf(min, bb.getLong(off + 0x30))
        }
        check(min != Long.MAX_VALUE) { "no PT_LOAD" }
        return min
    }
}
