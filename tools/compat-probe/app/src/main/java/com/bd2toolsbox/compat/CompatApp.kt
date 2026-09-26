package com.bd2toolsbox.compat

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.util.Log
import com.mikepenz.aboutlibraries.Libs
import dagger.hilt.android.EntryPointAccessors
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@HiltAndroidApp
class CompatApp : Application()

/** 有状态依赖：由 Hilt 以单例提供，构造注入 ApplicationContext。 */
@Singleton
class ProbeRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val packageName: String get() = context.packageName
}

interface Greeter { fun greet(): String }

@Module
@InstallIn(SingletonComponent::class)
object ProbeModule {
    @Provides
    @Singleton
    fun greeter(): Greeter = object : Greeter { override fun greet() = "hilt-ok" }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProbeEntryPoint {
    fun repository(): ProbeRepository
    fun greeter(): Greeter
}

@HiltViewModel
class ProbeViewModel @Inject constructor(private val greeter: Greeter) : ViewModel() {
    fun message(): String = greeter.greet()
}

/** 固定字段名与默认值，模拟生产持久化 DTO 的迁移目标形态。 */
@Serializable
data class ProbeSnapshot(
    @SerialName("schemaVersion") val schemaVersion: Int = 1,
    @SerialName("mods") val mods: List<String> = emptyList(),
    @SerialName("savedAt") val savedAt: Long,
)

/** 三项检查的唯一入口；设备测试与界面共用同一实现。 */
object ProbeChecks {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @JvmStatic
    fun serializationRoundTrip(): String {
        val original = ProbeSnapshot(mods = listOf("char000104", "char000201"), savedAt = 1789992348967L)
        val text = json.encodeToString(ProbeSnapshot.serializer(), original)
        val decoded = json.decodeFromString(ProbeSnapshot.serializer(), text)
        check(decoded == original) { "round-trip mismatch: $text" }
        // 未知字段被忽略、缺失字段取默认值：迁移旧快照时依赖这两条语义。
        val legacy = json.decodeFromString(ProbeSnapshot.serializer(), """{"savedAt":1,"extra":true}""")
        check(legacy.schemaVersion == 1 && legacy.mods.isEmpty()) { "defaults not applied: $legacy" }
        return text
    }

    @JvmStatic
    fun ensurePython(context: Context): Python {
        synchronized(Python::class.java) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    /** 返回 "3.8|<abi>|<lz4 roundtrip>|<PIL version>"。 */
    @JvmStatic
    fun pythonCall(context: Context): String {
        val py = ensurePython(context)
        return py.getModule("probe").callAttr("run").toString()
    }

    @JvmStatic
    fun aboutLibrariesCount(context: Context): Int {
        val raw = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        return Libs.Builder().withJson(raw).build().libraries.size
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repository: ProbeRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selfCheck = intent.getBooleanExtra(EXTRA_SELF_CHECK, false)
        setContent {
            val vm: ProbeViewModel = hiltViewModel()
            MaterialTheme { Text("${vm.message()} / ${repository.packageName}") }
            if (selfCheck) {
                // 在 Compose 里取得的 hiltViewModel 一并纳入自检：覆盖 Activity 注入 + Compose VM 工厂。
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val vmMessage = vm.message()
                    Thread { runSelfCheck(vmMessage) }.start()
                }
            }
        }
    }

    /**
     * minified release 的自检入口：不依赖测试包（测试包按 release mapping 调用会撞上 R8 内联/删除的
     * 方法，见 release-probe/build.gradle.kts 顶部）。每项独立捕获异常，最后一行汇总。
     */
    private fun runSelfCheck(vmMessage: String) {
        val results = linkedMapOf<String, String>()
        fun check(name: String, block: () -> String) {
            results[name] = try { "PASS " + block() } catch (t: Throwable) { "FAIL ${t.javaClass.name}: ${t.message}" }
            Log.i(TAG, "$name ${results[name]}")
        }
        check("hiltActivityAndViewModel") {
            require(repository.packageName == packageName) { repository.packageName }
            require(vmMessage == "hilt-ok") { vmMessage }
            "repository=${repository.packageName} vm=$vmMessage"
        }
        check("hiltEntryPointSingleton") {
            val ep = EntryPointAccessors.fromApplication(applicationContext, ProbeEntryPoint::class.java)
            require(ep.repository() === ep.repository()) { "singleton broken" }
            require(ep.repository() === repository) { "activity/entry point instances differ" }
            ep.greeter().greet()
        }
        check("serialization") { ProbeChecks.serializationRoundTrip() }
        check("python") {
            val r = ProbeChecks.pythonCall(this)
            val payloadAbi = applicationInfo.nativeLibraryDir.trimEnd('/').substringAfterLast('/')
                .let { if (it == "arm64") "arm64-v8a" else it }
            require(r == "3.8|$payloadAbi|lz4-ok|PIL-9.2.0") { "$r (payload=$payloadAbi)" }
            r
        }
        check("aboutLibraries") {
            val n = ProbeChecks.aboutLibrariesCount(this)
            require(n > 10) { "only $n" }
            "libraries=$n"
        }
        val failed = results.count { !it.value.startsWith("PASS") }
        Log.i(TAG, "SUMMARY passed=${results.size - failed} failed=$failed debuggable=" +
            "${(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
    }

    companion object {
        const val EXTRA_SELF_CHECK = "selfCheck"
        const val TAG = "BD2_COMPAT_SELFCHECK"
    }
}
