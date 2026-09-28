package com.bd2toolsbox.service

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.storage.BackupProvenance
import com.bd2toolsbox.core.storage.BaselineSource
import com.bd2toolsbox.data.model.InstallJob
import com.bd2toolsbox.data.model.JobStatus
import com.bd2toolsbox.data.model.RepackJob
import com.bd2toolsbox.data.repository.BundleBackupRepository
import com.bd2toolsbox.data.repository.CharacterRepository
import com.bd2toolsbox.data.repository.InstalledModRepository
import com.bd2toolsbox.data.repository.ModRepository
import com.bd2toolsbox.data.repository.PreviewCacheRepository
import com.bd2toolsbox.ui.viewmodel.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * 「备份失败要如实回报」这条链的端到端验收（T07 二轮整改，裁定 3 附加）。
 *
 * ## 为什么必须单独有一条
 *
 * 用户的原始要求是：「成功备份的备份，失败的就跳过，直到完成该次任务，然后进行回报
 * 是否全部完成，如果没有则显示哪些没有」。三处备份失败里，转换流程与还原那两处
 * **不中止任务**，只有一闪而过的 toast，任务随后被置成 `Finished` ——
 * 不汇总的话，用户在结果对话框里**看不到**它们。
 *
 * 这正是复核者反复点名的那类接缝（`task-T07-review.md` §F.2：「纯函数被 9 条覆盖、
 * `AtomicFileWriter` 被 24 条覆盖，但两者之间那根线只经过编译与人工核对」）。
 * 所以这里**从生产的入口函数进去**（`reportBackupFailure`，三处调用点用的就是它），
 * 而不是直接调累加器 —— 否则删掉入口里的那一行，用例照样绿，又是一条自我参照的空断言。
 *
 * ## 覆盖到哪、覆盖不到哪（如实登记）
 *
 * - ✅ 覆盖：`reportBackupFailure` → 累加 → `summarizeResults` → `FinalInstallResult`
 *   这条链真的通了；去重生效；**计数不被污染**（备份失败不是装入失败）；
 *   以及 `reportBackupFailure` **没有**把正在处理的那个 job 标成 `Failed`
 *   （三轮补的守卫，见 [backupFailureDoesNotMarkTheJobAsFailed]）。
 * - ❌ 覆盖不到：`processSingleJob:1520` / `restoreBaseBundle:1644` 那两行**真的调用了**
 *   `reportBackupFailure`（那是 `MainViewModel` 内部调用点，要驱动整条转换流程才能到，
 *   本仓没有能跑完整转换的测试设施）；以及 `AppDialogs` 那一节的渲染。
 *   前者与 §7.2(e) 是同一格缺口，本用例把它从「接线完全没测」缩小到「入口测了、
 *   调用点没测」，但**没有消除**。
 *
 * ## 三轮整改：夹具必须真的往 `_installJobs` 里放 job（否则断言是空转）
 *
 * 二轮的夹具**从不给 `_installJobs` 赋值**，而它初值是 `emptyList()` —— 于是
 * `summarizeResults` 里 `failed = jobs.filter { it.status is JobStatus.Failed }`
 * **恒为空**，「计数不被污染」这条断言在「计数永远为 0」的前提下恒真。
 * 二轮复核者据此做了两次破坏（把 `reportBackupFailure` 里的状态行改成 `Failed`、
 * 把整行删掉），**两次都全绿** —— 与一轮真缺陷 #1 完全同形的「断言看着在守、
 * 实际守不住」。
 *
 * 现在夹具用一个 job 列表给 `_installJobs` 赋值，让「计数」与「状态行」都作用在
 * **非空**的 job 集合上。生产里 `reportBackupFailure` 的 `hashedName` 一定在那个
 * 集合里（它就是 `processSingleJob` 当前正在处理的 bundle），夹具照此构造前提。
 */
@RunWith(AndroidJUnit4::class)
class BackupFailureSummaryTest {

    private val store = ViewModelStore()
    // T17：appContext 与仓储改构造注入。vm 直接用 targetContext + 真实仓储构造。
    private val vm: MainViewModel = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val character = CharacterRepository(ctx)
        MainViewModel(
            SavedStateHandle(), ctx, character, ModRepository(ctx, character),
            InstalledModRepository(ctx), BundleBackupRepository(ctx), PreviewCacheRepository(ctx),
            com.bd2toolsbox.core.engine.ChaquopyModEngine(),
        )
    }

    private val reportBackupFailure = MainViewModel::class.java
        .getDeclaredMethod("reportBackupFailure", String::class.java, String::class.java)
        .apply { isAccessible = true }

    private val summarizeResults = MainViewModel::class.java
        .getDeclaredMethod("summarizeResults")
        .apply { isAccessible = true }

    private val installJobsFlow: MutableStateFlow<List<InstallJob>> = run {
        @Suppress("UNCHECKED_CAST")
        MainViewModel::class.java.getDeclaredField("_installJobs").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<List<InstallJob>>
    }

    init {
        store.put("t07-backup-summary", vm)
        // appContext 与 backupRepository 已在构造时注入（targetContext + 真实仓储）。
    }

    @After
    fun tearDown() {
        store.clear()
    }

    /**
     * 把 [hashedNames] 作为**尚未收尾的** job 放进 `_installJobs`。
     *
     * 这是夹具的**前提构造**，不是被测行为：`reportBackupFailure` 的调用点
     * （`processSingleJob`）一定已经把当前 bundle 放进去了，不构造这个前提，
     * 关于 job 状态与计数的断言就都是空转。
     */
    private fun seedInstallJobs(vararg hashedNames: String) {
        installJobsFlow.value = hashedNames.map { InstallJob(RepackJob(it, emptyList())) }
    }

    /**
     * 等主线程把 [MainViewModel.updateJobStatus] 里 `installScope.launch(Dispatchers.Main)`
     * 排上的那一段跑完。
     *
     * `runOnMainSync` 把任务投到**队尾**，所以队列里已有的（我们这一批）会先执行 ——
     * 这样断言读到的是「状态行已经落定」之后的状态，而不是取决于调度时机的中间态。
     */
    private fun drainMainLooper() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { }
    }

    /**
     * 从**后台线程**调用 [reportBackupFailure] —— 与生产完全一致的线程姿势。
     *
     * 生产里这个方法由 `processSingleJob`（`launch(Dispatchers.IO)`）与
     * `restoreBaseBundle` 调用，两者都**不在**主线程上。而
     * `Toast.makeText(...).show()` 在没有 Looper 的线程上会抛
     * `NullPointerException: Can't toast on a thread that has not called Looper.prepare()`
     * —— 那个异常会被 `processSingleJob` 的 `catch (e: Exception)` 接住，把整个转换
     * 连同已下载的基底一起判死，用户看到一条与真实原因无关的报错。
     *
     * **这个缺陷就是这条用例抓出来的**：初版这里在主线程上调用（`runOnMainSync`），
     * 全绿；改成后台线程后立刻暴露出实现少了线程切换。所以断言里除了汇总内容，
     * 还包含「调用本身不许抛」—— 那是「备份失败不中止任务」这条裁定的前提。
     */
    private fun reportOnBackgroundThread(hashedName: String, message: String) {
        // 每次调用前清空：这是实例字段，一条用例里会调多次，且 JUnit 每条用例新建实例 ——
        // 但同一条用例内第二次调用若不清空，会把第一次的失败原因误报成第二次的。
        workerFailure.set(null)
        val worker = Thread {
            try {
                reportBackupFailure.invoke(vm, hashedName, message)
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }
        worker.start()
        worker.join(10_000)
        assertFalse("reportBackupFailure 不许挂住", worker.isAlive)
        assertNull(
            "reportBackupFailure 在后台线程上不许抛异常（Toast 需要主线程 Looper）",
            workerFailure.get()
        )
    }

    /** 后台线程里抛出的异常；由 [reportOnBackgroundThread] 断言必须为 null。 */
    private val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

    /**
     * **三处备份失败都要出现在结果的「未完成的备份」一节里，且不动成功/失败计数。**
     *
     * 断言的是「哪些没有」（用户原话）—— 每个没做成的备份都要能被指名道姓地列出来。
     *
     * 夹具**先放进两个 job**：否则 `_installJobs` 是空的，「计数不被污染」这条断言
     * 会在「计数恒为 0」的前提下恒真 —— 那正是二轮复核抓到的那类空转写法
     * （见类注释与 [backupFailureDoesNotMarkTheJobAsFailed]）。
     */
    @Test
    fun backupFailuresAreReportedInTheFinalSummaryWithoutChangingCounts() {
        seedInstallJobs("bundle-alpha", "bundle-beta")

        reportOnBackgroundThread("bundle-alpha", "原版备份失败：转换产物不受影响，但卸载后无法本地还原。")
        reportOnBackgroundThread("bundle-beta", "还原产物的备份失败：还原本身不受影响，但卸载后无法本地秒还原。")
        drainMainLooper()

        summarizeResults.invoke(vm)

        val result = vm.finalInstallResult.value
        assertNotNull("收尾后必须有 FinalInstallResult", result)
        assertEquals(
            "两个没做成的备份都必须被列出来",
            listOf("bundle-alpha", "bundle-beta"),
            result!!.unfinishedBackups.map { it.hashedName }
        )
        assertEquals(
            "每个条目必须带上原因（用户要知道「哪些没有」以及为什么）",
            "原版备份失败：转换产物不受影响，但卸载后无法本地还原。",
            result.unfinishedBackups.first { it.hashedName == "bundle-alpha" }.error
        )
        // 这一条是裁定 3 附加的硬要求：备份失败**不算**装入失败（CDN 能重下）。
        // 两个 job 此刻都还没收尾，所以这两个 0 是「真的没有」而不是「集合本来就是空的」。
        assertEquals("前提：夹具真的放进了两个 job", 2, vm.installJobs.value.size)
        assertEquals("备份失败不许抬高失败计数", 0, result.failedJobs)
        assertEquals("备份失败不许抬高成功计数", 0, result.successfulJobs)
    }

    /**
     * **同一个 bundle 只留一条**：一次任务里同一个分组可能被多处顺手备份，
     * 都失败时不该在结果里重复出现（用户要的是「哪些没有」，不是「失败了几次」）。
     */
    @Test
    fun repeatedFailuresForTheSameBundleCollapseIntoOneEntry() {
        reportOnBackgroundThread("bundle-gamma", "第一次失败")
        reportOnBackgroundThread("bundle-gamma", "第二次失败")

        summarizeResults.invoke(vm)

        val listed = vm.finalInstallResult.value!!.unfinishedBackups
        assertEquals("同一个分组只许出现一次", 1, listed.size)
        assertEquals("bundle-gamma", listed.single().hashedName)
        assertEquals("保留首次失败的原因", "第一次失败", listed.single().error)
    }

    /**
     * **备份失败不许把当前 job 标成 [JobStatus.Failed]**（T07 三轮整改，真缺陷 #2 的守卫）。
     *
     * ## 为什么必须有这一条（二轮复核抓到的守卫缺口）
     *
     * 二轮复核者把 `reportBackupFailure` 里那行 `updateJobStatus(...)` 从
     * [JobStatus.Installing] 改成 [JobStatus.Failed]、又把它**整行删掉**，
     * 本套件**两次都全绿** —— 因为夹具从不给 `_installJobs` 赋值，关于计数的断言
     * 恒为 0。这正是与一轮真缺陷 #1 同形的「断言看着在守、实际守不住」。
     *
     * 生产里那个 `hashedName` **一定**在 `_installJobs` 里（`processSingleJob`
     * 正在处理它），所以那行一旦变成 `Failed`：
     *   - `summarizeResults` 的 `failedJobs` 会把**每个备份失败**都算成**装入失败**，
     *     用户看到「失败 N 个」而实际那些 mod 装成了 —— 正是裁定 3 附加明令
     *     「不改变计数」要防的那件事；
     *   - 更糟的是 `updateJobStatus(Failed)` 让该 job 进入**终态**，可能让批次被提前
     *     判定收尾。
     *
     * ## 这条守卫怎么覆盖四种破坏形态
     *
     * 夹具先放进两个 job（`bundle-alpha` / `bundle-beta`），再对 `bundle-alpha`
     * 报一次备份失败，然后：
     *   1. 断言 `bundle-alpha` 的状态**不是** [JobStatus.Failed]（改值 → 红）；
     *   2. 断言 `bundle-alpha` 的状态**确实还在**（删掉整行 → 状态停在 Pending，
     *      仍然不是 Failed，所以第 1 条抓不住「删行」—— 靠第 3 条）；
     *   3. 断言它的进度消息**就是**那条备份失败说明（删行 → 消息对不上 → 红）。
     *
     * 第 1 条与第 3 条合起来把「改值」与「删行」两种破坏都钉死，见 `T07.md` §6.7。
     */
    @Test
    fun backupFailureDoesNotMarkTheJobAsFailed() {
        seedInstallJobs("bundle-alpha", "bundle-beta")
        val message = "原版备份失败：转换产物不受影响，但卸载后无法本地还原。"

        reportOnBackgroundThread("bundle-alpha", message)
        drainMainLooper()

        val jobs = vm.installJobs.value
        assertEquals("前提：夹具必须真的把两个 job 放进了 _installJobs", 2, jobs.size)
        val alpha = jobs.single { it.job.hashedName == "bundle-alpha" }
        val beta = jobs.single { it.job.hashedName == "bundle-beta" }

        assertFalse(
            "备份失败不许把当前 job 标成 Failed —— 那会让它进入终态、并被算进 failedJobs",
            alpha.status is JobStatus.Failed
        )
        assertTrue(
            "备份失败必须让用户看得见（状态行仍要被写，删掉那行这里就红）",
            alpha.status is JobStatus.Installing
        )
        assertEquals(
            "状态行必须带上这次备份失败的原因",
            message,
            (alpha.status as JobStatus.Installing).progressMessage
        )
        assertEquals(
            "别的 job 的状态不该被这次备份失败碰到",
            JobStatus.Pending,
            beta.status
        )

        summarizeResults.invoke(vm)
        val result = vm.finalInstallResult.value
        assertNotNull("收尾后必须有 FinalInstallResult", result)
        assertEquals(
            "备份失败不许抬高 failedJobs —— 这正是二轮那个假绿的形状",
            0,
            result!!.failedJobs
        )
        assertEquals("备份失败不许抬高 successfulJobs", 0, result.successfulJobs)
        assertEquals(
            "没做成的备份仍要单列一节",
            listOf("bundle-alpha"),
            result.unfinishedBackups.map { it.hashedName }
        )
    }

    /**
     * **`restoreBaseBundle` 的顺手备份必须尊重 `backup_originals` 开关**（T07 三轮整改）。
     *
     * ## 这条修的是什么
     *
     * 二轮把还原产物的备份失败收进了「未完成的备份」汇总，但那一处**不检查开关**
     * 就调 `saveBackup` —— 用户**明确关掉**自动备份时，一次**没被要求过**的备份
     * 失败会被收进汇总，结果对话框弹「未完成的备份：……卸载时需重新下载原版」。
     * 用户没要求备份，却被告知备份没做成。
     *
     * ## 三个子检查，每个都单独有判别力（少一个就留一条空转路径）
     *
     * | 子检查 | 开关 | 源 | 断言 | 去掉开关判断会怎样 |
     * |---|---|---|---|---|
     * | 1 | 关 | **可用** | 备份目录**没被建出来** | 目录被建出来 → **红** |
     * | 2 | 关 | **不可用** | **没有**条目 | 记下一条「未完成的备份」→ **红** |
     * | 3 | 开 | 不可用 | **有**条目（阳性对照） | —（证明这条链是活的） |
     *
     * 子检查 1 与 2 各自堵一条路：只做 1，则「开关关 + 备份失败」这条（正是用户会遇到的
     * 那一条）没人守；只做 2，则「开关关 + 备份本来能成功」时目录照样被建、只是不报错。
     * 子检查 3 是**阳性对照** —— 没有它，子检查 2 的「没有条目」可能只是「代码压根没跑」。
     *
     * ## 这条用例测的是哪一层（说清，避免再出现「看着在守、实际守不住」）
     *
     * 它反射驱动的是**从 `restoreBaseBundle` 尾部拆出来的那个私有方法**
     * （`backupRestoredBundle`）。生产调用链是
     * `restoreBaseBundle`（suspend）→ `withContext(Dispatchers.IO)` → 该方法；
     * 用例**跳过**了前半段（那需要跑通整条还原流程：Python + 一个合法的 CDN bundle，
     * 本仓没有这个设施）。所以它**不**证明「`restoreBaseBundle` 真的调了它」——
     * 与 [backupFailureDoesNotMarkTheJobAsFailed] 是同一类折扣，如实登记在
     * `T07.md` §7.2(f)。它证明的是：**开关关着时这段代码一个字节都不写、
     * 一条记录都不产生；开关开着时这条链真的会在失败时上报。**
     */
    @Test
    fun restoreBackupHonoursTheAutoBackupSwitch() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // backupRepository 已在 vm 构造时注入为真实仓储（同一 targetContext），无需再 set。
        val bundleName = "t07r3-switch-${java.util.UUID.randomUUID()}"
        val hashDir = "hash-${java.util.UUID.randomUUID()}"
        val cacheDir = ctx.cacheDir
        val workDir = File(cacheDir, "t07r3/$bundleName")
        val good = File(workDir, "__data").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 7 }) }
        val missing = File(workDir, "does-not-exist")
        val backupDir = File(ctx.getExternalFilesDir(null), "origin_backup/$bundleName")

        try {
            // ---- 1：开关关 + 源可用 → 备份目录都不该被建出来 ----
            vm.setBackupOriginals(false)
            invokeBackupRestoredBundle(bundleName, hashDir, good)
            assertFalse(
                "开关关着时一个字节都不该写进备份目录（${backupDir.absolutePath}）",
                backupDir.exists()
            )

            // ---- 2：开关关 + 源不可用 → 不许产生任何「未完成的备份」条目 ----
            invokeBackupRestoredBundle(bundleName, hashDir, missing)
            drainMainLooper()
            summarizeResults.invoke(vm)
            assertEquals(
                "开关关着时，一次没被要求过的备份失败不许变成用户可见的条目",
                emptyList<String>(),
                vm.finalInstallResult.value!!.unfinishedBackups.map { it.hashedName }
            )
            assertFalse("前提：这条子检查确实没建出备份目录", backupDir.exists())

            // ---- 3（阳性对照）：开关开 + 源不可用 → 必须有条目 ----
            vm.setBackupOriginals(true)
            invokeBackupRestoredBundle(bundleName, hashDir, missing)
            drainMainLooper()
            summarizeResults.invoke(vm)
            assertEquals(
                "开关开着且备份失败时，这条「未完成的备份」必须被列出来（阳性对照：证明这条链是活的）",
                listOf(bundleName),
                vm.finalInstallResult.value!!.unfinishedBackups.map { it.hashedName }
            )
        } finally {
            // 只清自己造的那一份，不碰用户已有的备份；开关恢复成默认值 true
            vm.setBackupOriginals(true)
            backupDir.deleteRecursively()
            workDir.deleteRecursively()
        }
    }

    /**
     * 反射调用 `restoreBaseBundle` 尾部那段私有备份逻辑（见上面用例的「测的是哪一层」）。
     *
     * **T09 二轮整改：签名从三参数变成四参数。** T09 给 `backupRestoredBundle` 加了
     * `provenance: BackupProvenance`（它要能区分「这份还原产物锚定自什么输入」，
     * 否则 `saveBackup` 只能写出 `catalogVerified=false` 的备份）。
     * 参数变了但这里没跟着改，于是本用例在 T09 交付态下抛
     * `NoSuchMethodException`（复核者实测 `Tests run: 5, Failures: 1`）——
     * **一条已交付的设备用例被静默打坏**。现在补上第四个实参。
     *
     * 传的 provenance 对本用例的三个子检查没有影响（它们断言的是「开关关着时备份目录
     * 建没建」「有没有产生未完成条目」），只要构造得合法即可。用 `GAME_DIR` +
     * `catalogSize` 是它与生产调用点最接近的一种形状。
     */
    private fun invokeBackupRestoredBundle(hashedName: String, hashDir: String, output: File) {
        MainViewModel::class.java
            .getDeclaredMethod(
                "backupRestoredBundle",
                String::class.java, String::class.java, File::class.java, BackupProvenance::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                vm, hashedName, hashDir, output,
                BackupProvenance(source = BaselineSource.GAME_DIR, catalogSize = 64),
            )
    }

    /**
     * **没失败就不显示这一节**：备份都成功（或用户关掉了自动备份）时，
     * 结果里不该冒出一条空标题吓人。
     */
    @Test
    fun noBackupFailuresMeansNoSectionAtAll() {
        summarizeResults.invoke(vm)

        assertEquals(
            "没有备份失败时必须给出空列表（界面据此不渲染这一节）",
            emptyList<String>(),
            vm.finalInstallResult.value!!.unfinishedBackups.map { it.hashedName }
        )
    }
}
