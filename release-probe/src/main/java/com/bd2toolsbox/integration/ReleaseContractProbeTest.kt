package com.bd2toolsbox.integration

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * release 契约探针 —— 在**真实 minified release 包**里跑，用来证明这个包确实装得上、
 * 测试 runner 起得来、主界面找得到、应用身份对得上，而不是拿 debug 包或测试专用保留规则
 * 冒充生产行为。
 *
 * 本类位于 `:release-probe`（一个**自足的普通应用**），不是 `:app` 的 androidTest 包。
 * 这是被实测逼出来的，不是偏好：androidTest 包会被 AGP 去重掉「被测 app 已提供的类」，
 * 并被 R8 按被测 app 的映射改写引用，于是
 *   * 用 debug androidTest 包驱动 release 包 → ClassNotFoundException: Intrinsics；
 *   * 用 release 变体的 androidTest 包   → NoSuchMethodError: P1.h.e(...)。
 * 两条都有实测记录，见 docs/refactor/evidence/T04.md。自足探针两条都不受影响，因此可以
 * 驱动**预先构建好的** T03 冻结基线（`:app` 源码早就变过，映射不可能再对上）。
 *
 * 与 `Test-Bd2ReleaseSmoke -Case baseline-startup` 配套：脚本负责构建/安装/签名核对与
 * `am start -W` 启动，本类负责在**目标进程内**核对身份、载荷与签名自洽。两侧都不改
 * 生产 DTO、不加测试专用 keep、不读真实游戏目录。
 *
 * 只断言「包本身的身份与可用性」，不调用业务内部类：R8 对模块内部符号的重命名属于
 * `full-r8-contract`（后续 case）的范围，在这里断言会把正常的收缩误报成缺陷。
 */
@RunWith(AndroidJUnit4::class)
class ReleaseContractProbeTest {

    private companion object {
        /** T03 冻结的基线身份。后续版本要另加 case，不许把这里的期望改松。 */
        const val BASELINE_PACKAGE = "com.bd2toolsbox"
        const val BASELINE_VERSION_NAME = "0.2.16"
        const val BASELINE_VERSION_CODE = 17L
        const val MAIN_ACTIVITY = "com.bd2toolsbox.MainActivity"
        const val EXPECTED_RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        const val TAG = "ReleaseContractProbe"
    }

    private fun signingDigests(context: Context, packageName: String): List<String> {
        val info = context.packageManager.getPackageInfo(
            packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return signers.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * `baseline-startup`：真实 minified 原版 / 同源原工具链重建包的
     * 安装、测试 runner、主界面启动与应用身份。
     */
    @Test
    fun baselineStartup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pm = context.packageManager

        // ---- 1. 测试 runner：能执行到这里，本身就说明 runner 已在目标进程里跑起来 ----
        assertEquals(
            "被测包必须由 manifest 里声明的标准 runner 驱动，而不是别的测试宿主",
            EXPECTED_RUNNER, instrumentation.javaClass.name
        )

        // ---- 2. 确实跑在 release 包上：debug/测试包必须在这里被挡住 ----
        val appInfo = context.applicationInfo
        assertTrue(
            "baseline-startup 必须在真实 release 包上运行（当前包是 debuggable）",
            appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
        )
        assertTrue(
            "被测的生产包不应带 testOnly 标记",
            appInfo.flags and ApplicationInfo.FLAG_TEST_ONLY == 0
        )

        // ---- 3. 应用身份 ----
        val args = InstrumentationRegistry.getArguments()
        val expectedVersionName = args.getString("expectedVersionName")?.takeIf { it.isNotBlank() }
            ?: BASELINE_VERSION_NAME
        val expectedVersionCode = args.getString("expectedVersionCode")?.toLongOrNull()
            ?: BASELINE_VERSION_CODE
        assertEquals("包名必须与冻结基线一致", BASELINE_PACKAGE, context.packageName)
        val pkg = pm.getPackageInfo(context.packageName, 0)
        assertEquals("版本名必须与预期一致", expectedVersionName, pkg.versionName)
        assertEquals("版本号必须与预期一致", expectedVersionCode, pkg.longVersionCode)

        // manifest 里没有 android:name 时应用类就是默认 Application；写了就必须是它本人。
        // 这样断言不依赖「当前还没有 Bd2Application」这个阶段性事实。
        val declaredApp = appInfo.className
        val runningApp = (context.applicationContext as Application).javaClass.name
        if (declaredApp.isNullOrEmpty()) {
            assertEquals(
                "manifest 未声明 android:name 时，运行的必须是默认 Application",
                Application::class.java.name, runningApp
            )
        } else {
            assertEquals("运行的 Application 必须就是 manifest 声明的那个", declaredApp, runningApp)
        }

        // ---- 4. 主界面可启动 ----
        // 刻意不用 MATCH_DEFAULT_ONLY：本应用的 LAUNCHER 过滤器只声明了 MAIN + LAUNCHER，
        // 没有 CATEGORY_DEFAULT，带上该标志会一律解析为 null（第一次真跑就撞上这个）。
        // 系统启动器本身也是按 MAIN + LAUNCHER 查询的，所以这里用同一口径。
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0
        )
        assertEquals("LAUNCHER 入口必须唯一：$launchers", 1, launchers.size)
        val launcher = launchers[0].activityInfo
        assertEquals("LAUNCHER 入口必须是生产 MainActivity", MAIN_ACTIVITY, launcher.name)
        assertTrue("LAUNCHER 入口必须 exported", launcher.exported)

        // ---- 5. 载荷与设备自洽：装错 ABI 的包会在这里露馅 ----
        // nativeLibraryDir 的末段就是包实际落地的 ABI 目录（…/lib/x86_64）。
        // 刻意不用 ApplicationInfo.primaryCpuAbi：它是 hidden API，compileSdk 的
        // android.jar 里没有，一引用就编不过（本项第一次真跑就撞上这个编译错误）。
        val nativeDir = appInfo.nativeLibraryDir
        assertNotNull("带 native 库的 release 包必须解析出 native 库目录", nativeDir)
        val payloadAbi = payloadAbiFromNativeLibraryDir(nativeDir)
        assertNotNull("载荷 ABI 目录 $nativeDir 无法识别为受支持的 ABI", payloadAbi)
        assertTrue(
            "载荷 ABI $payloadAbi (来自目录 $nativeDir) 不在设备支持的 ABI 列表中：${Build.SUPPORTED_ABIS.joinToString()}",
            Build.SUPPORTED_ABIS.contains(payloadAbi)
        )

        // ---- 6. 签名：被测包与测试包必须同一证书，否则 instrumentation 本就不该连上 ----
        val targetCerts = signingDigests(context, context.packageName)
        assertFalse("被测包必须带签名证书", targetCerts.isEmpty())
        val testCerts = signingDigests(instrumentation.context, instrumentation.context.packageName)
        assertFalse("测试包必须带签名证书", testCerts.isEmpty())
        assertTrue(
            "被测包与测试包签名证书不一致：target=$targetCerts test=$testCerts",
            targetCerts.intersect(testCerts.toSet()).isNotEmpty()
        )

        // 原始观测值随 am instrument 输出一起被脚本抓进报告，供证据文件引用
        println(
            "$TAG identity package=${context.packageName} version=${pkg.versionName}" +
                " versionCode=${pkg.longVersionCode} application=$runningApp" +
                " payloadAbi=$payloadAbi nativeLibraryDir=$nativeDir runner=${instrumentation.javaClass.name}" +
                " targetCertSha256=${targetCerts.joinToString(",")}"
        )
    }
}
