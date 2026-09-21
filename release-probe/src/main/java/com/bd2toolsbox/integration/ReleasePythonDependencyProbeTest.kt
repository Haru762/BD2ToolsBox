package com.bd2toolsbox.integration

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException

/**
 * release Python 依赖 import 验收探针（T12）。
 *
 * 在**真实 minified、non-debuggable 的 com.bd2toolsbox 进程**里，通过
 * targetContext.classLoader **反射** Chaquopy 的 com.chaquo.python.Python /
 * com.chaquo.python.android.AndroidPlatform，证明打进 APK 的目标 CPython 3.8
 * 能真正把 9 个直接依赖与 3 个 bridge 模块 import 起来 —— 而不是拿 buildPython、
 * 拿 debug 包、或拿「wheel 已解析」冒充「设备内 import 已通过」。
 *
 * 为什么反射而不是直接依赖 Chaquopy：本模块是自足的 :release-probe（见
 * ReleaseContractProbeTest 抬头），刻意**不加 :app、也不加 Chaquopy 编译依赖**，
 * 更不放任何 keep 规则、不碰生产代码。目标进程里的 com.chaquo.python.* 由
 * Chaquopy native 侧按固定类名 JNI 查找，天然不被 R8 改名，所以 Class.forName
 * 在冻结的 minified 包里也拿得到。
 *
 * vendored 依赖（UnityPy 等）的 sys.path **沿用生产**：不新造 setup 模块，而是
 * getModule("main_script") —— 这正是 Kotlin 侧 ModdingService 用的入口，它在模块
 * 顶层执行 sys.path.append(.../vendor)，与生产完全同源。
 *
 * 结果全程 sendStatus 到 stream 字段（前缀 BD2_PYTHON_PROBE），即便某项失败也先把
 * 其余项收集完再在末尾断言。native 解码库另有一个诚实的 @Test：x86_64 现缺
 * libtexture2ddecoder.so，那条预期红灯，不允许拿「import 成功」顶替。
 */
@RunWith(AndroidJUnit4::class)
class ReleasePythonDependencyProbeTest {

    private companion object {
        const val TARGET_PACKAGE = "com.bd2toolsbox"
        const val TAG = "ReleasePythonDependencyProbe"
        const val STREAM_PREFIX = "BD2_PYTHON_PROBE"

        const val PY_CLASS = "com.chaquo.python.Python"
        const val PLATFORM_CLASS = "com.chaquo.python.android.AndroidPlatform"
        const val PLATFORM_BASE = "com.chaquo.python.Python\$Platform"
        const val PYOBJECT_CLASS = "com.chaquo.python.PyObject"

        // 9 个直接依赖：pip 名 -> 实际 import 名（Pillow->PIL、beautifulsoup4->bs4、
        // protobuf->google.protobuf、attrs->attr、lz4 取子模块 lz4.frame）。
        val DIRECT_IMPORTS = listOf(
            "PIL", "lz4.frame", "brotli", "fsspec", "attr",
            "requests", "bs4", "google.protobuf", "tqdm"
        )
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    // ---------------------------------------------------------------- 反射桥

    private fun loader(context: Context): ClassLoader = context.classLoader

    private fun pyObjectClass(context: Context): Class<*> =
        Class.forName(PYOBJECT_CLASS, false, loader(context))

    private fun callAttrMethod(context: Context) =
        pyObjectClass(context).getMethod("callAttr", String::class.java, arrayOf<Any>().javaClass)

    private fun getAttrMethod(context: Context) =
        pyObjectClass(context).getMethod("get", Any::class.java)

    /** PyObject.callAttr(name, args...)，异常原样抛给调用方处理。 */
    private fun callAttr(context: Context, obj: Any, name: String, vararg args: Any?): Any? =
        callAttrMethod(context).invoke(obj, name, args)

    /** getattr(obj, name)（Chaquopy 的 PyObject.get 即 getattr）。 */
    private fun getAttr(context: Context, obj: Any, name: String): Any? =
        getAttrMethod(context).invoke(obj, name)

    private fun rootCause(t: Throwable): Throwable {
        var cur = t
        while ((cur is InvocationTargetException || cur.cause != null) && cur.cause != null) {
            cur = cur.cause!!
        }
        return cur
    }

    /**
     * 保证目标解释器已启动。仅在尚未启动时用生产同款
     * AndroidPlatform(targetContext) 反射拉起（Python.start 不是线程安全的，
     * 但仪器测试单线程串行，且这里只在真需要时启动一次）。返回 Python 实例。
     */
    private fun ensurePython(context: Context): Any {
        val pyClass = Class.forName(PY_CLASS, true, loader(context))
        val isStarted = pyClass.getMethod("isStarted").invoke(null) as Boolean
        if (!isStarted) {
            val platformBase = Class.forName(PLATFORM_BASE, true, loader(context))
            val platform = Class.forName(PLATFORM_CLASS, true, loader(context))
                .getConstructor(Context::class.java).newInstance(context)
            pyClass.getMethod("start", platformBase).invoke(null, platform)
        }
        return pyClass.getMethod("getInstance").invoke(null)
            ?: error("Python.getInstance() 返回 null")
    }

    private fun getModule(context: Context, py: Any, name: String): Any {
        val m = py.javaClass.getMethod("getModule", String::class.java).invoke(py, name)
        return m ?: error("getModule(\"$name\") 返回 null")
    }

    private fun getBuiltins(context: Context, py: Any): Any =
        py.javaClass.getMethod("getBuiltins").invoke(py) ?: error("getBuiltins() 返回 null")

    private fun report(line: String) {
        val b = Bundle()
        b.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$STREAM_PREFIX $line\n")
        // 0 is reserved for a completed test in the runner protocol.
        instrumentation.sendStatus(2, b)
        Log.i(TAG, line)
    }

    /** 校验当前 host 就是真实、非 debuggable 的目标包进程。 */
    private fun assertHostIsTarget(context: Context) {
        assertEquals("探针必须跑在目标包进程里（instrumentation host）",
            TARGET_PACKAGE, Application.getProcessName())
        assertTrue("myPid 必须为正数", Process.myPid() > 0)
        val app = context.applicationInfo
        assertEquals("目标 applicationId", TARGET_PACKAGE, app.packageName)
        assertEquals("目标不得是 debuggable 构建", 0,
            app.flags and ApplicationInfo.FLAG_DEBUGGABLE)
    }

    private fun payloadAbi(context: Context): String {
        val dir = context.applicationInfo.nativeLibraryDir ?: return "UNKNOWN"
        return dir.trimEnd('/').substringAfterLast('/')
    }

    // ---------------------------------------------------------------- 测试 1

    /**
     * targetPythonImports：目标进程内先核身份（目标包 / getProcessName / 非
     * debuggable），再确保 Python 已启动，核 sys.version_info 为 3.8、报 sys.version，
     * 然后逐个 import 9 个直接依赖 + 3 个 bridge 模块（UnityPy 前先 getModule
     * main_script 沿用生产 vendor sys.path）。每项成功/异常都 sendStatus，末尾断言
     * 全部必需 import 成功；单项失败也继续收集其余结果再失败。
     */
    @Test
    fun targetPythonImports() {
        val context = instrumentation.targetContext
        assertHostIsTarget(context)

        val abi = payloadAbi(context)
        report("env abi=$abi supportedAbis=${Build.SUPPORTED_ABIS.joinToString(",")}")

        val py = ensurePython(context)
        val sys = getModule(context, py, "sys")

        // sys.version_info.major/minor 必须是 3.8（目标 wheel tag cp38 的解释器）。
        val versionInfo = getAttr(context, sys, "version_info")!!
        val major = getAttr(context, versionInfo, "major").toString().trim()
        val minor = getAttr(context, versionInfo, "minor").toString().trim()
        val version = getAttr(context, sys, "version").toString()
        report("python version_info=$major.$minor version=${version.replace('\n', ' ')}")
        assertEquals("目标解释器主版本必须是 3", "3", major)
        assertEquals("目标解释器次版本必须是 8", "8", minor)

        // 收集失败项：单项异常不中断，末尾统一断言。
        val failures = LinkedHashMap<String, String>()

        fun tryImport(label: String, module: String) {
            try {
                getModule(context, py, module)
                report("import $label module=$module status=OK")
            } catch (t: Throwable) {
                val cause = rootCause(t)
                val msg = "${cause.javaClass.name}: ${cause.message}"
                failures[label] = msg
                report("import $label module=$module status=FAIL error=$msg")
            }
        }

        // 9 个直接依赖。
        for (module in DIRECT_IMPORTS) tryImport(module, module)

        // bridge：vendored sys.path 沿用生产入口 main_script（ModdingService 同款），
        // 它在模块顶层 sys.path.append(.../vendor)，之后 UnityPy 才找得到。
        tryImport("main_script", "main_script")
        tryImport("UnityPy", "UnityPy")
        tryImport("maintenance_info_pb2", "maintenance_info_pb2")
        tryImport("texture2ddecoder", "texture2ddecoder")

        report("summary failures=${failures.size} detail=${failures}")
        assertTrue("以下必需 import 失败：$failures", failures.isEmpty())
    }

    // ---------------------------------------------------------------- 测试 2

    /**
     * nativeTextureDecoderAvailable：显式检查 texture2ddecoder._lib **非 None** ——
     * 该 .so 懒加载失败时模块仍能 import 成功（_lib=None），所以「import 成功」
     * 绝不等于「native 可用」。用一个唯一的 inline exec globals dict 探测 C exports
     * 与功能冒烟，不改包文件。
     *
     * 历史教训：曾出现过 arm64 库能加载（_lib 非 None）但缺失必需 C 符号的事故。
     * 故本测试不仅核实库存在，还强制遍历依赖清单里的每一个符号（hasattr），
     * 并选取确定的安全输入（4x4 BC1 白底 block）执行一次真实解码，验证产物。
     * 两个 ABI 的库均已打包；本测试只证明实际执行的设备 ABI，不外推 arm64 真机结果。
     */
    @Test
    fun nativeTextureDecoderAvailable() {
        val context = instrumentation.targetContext
        assertHostIsTarget(context)
        val abi = payloadAbi(context)

        val py = ensurePython(context)
        val builtins = getBuiltins(context, py)

        // 唯一 globals dict：exec 里 import 模块并探测。
        // 不 setattr 到包、不改文件；exec/dict 由 builtins 提供。
        val globals = callAttr(context, builtins, "dict")!!
        val code = """
            import texture2ddecoder as _t2d
            _probe_lib_present = _t2d._lib is not None
            _probe_lib_repr = repr(_t2d._lib)
            _probe_missing = ""
            _probe_smoke_ok = False
            _probe_smoke_error = ""
            
            if _probe_lib_present:
                _req = [
                    "decode_atc_rgb4", "decode_atc_rgba8",
                    "decode_etc1", "decode_etc2", "decode_etc2a1", "decode_etc2a8",
                    "decode_eacr", "decode_eacr_signed", "decode_eacrg", "decode_eacrg_signed",
                    "decode_bc1", "decode_bc3", "decode_bc4", "decode_bc5",
                    "decode_bc6", "decode_bc7",
                    "decode_astc", "decode_pvrtc",
                    "crunch_unpack_level", "unity_crunch_unpack_level"
                ]
                _miss = [s for s in _req if not hasattr(_t2d._lib, s)]
                _probe_missing = ",".join(_miss)
                
                if not _miss:
                    # 确定性的 BC1 解码冒烟测试（4x4 白底不透明 block = 8 字节输入 -> 64 字节 0xFF 输出）
                    try:
                        res = _t2d.decode_bc1(b"\xff\xff\x00\x00\x00\x00\x00\x00", 4, 4)
                        _probe_smoke_ok = (res == b"\xff" * 64)
                    except Exception as e:
                        _probe_smoke_error = repr(e)
        """.trimIndent()
        callAttr(context, builtins, "exec", code, globals)

        val present = callAttr(context, globals, "get", "_probe_lib_present").toString().trim()
        val libRepr = callAttr(context, globals, "get", "_probe_lib_repr").toString()
        val missing = callAttr(context, globals, "get", "_probe_missing").toString()
        val smokeOk = callAttr(context, globals, "get", "_probe_smoke_ok").toString().trim()
        
        val smokeError = callAttr(context, globals, "get", "_probe_smoke_error").toString()

        report("native abi=$abi texture2ddecoder._lib_present=$present repr=$libRepr missing=$missing smoke_ok=$smokeOk smoke_error=$smokeError")

        assertEquals(
            "texture2ddecoder._lib 必须非 None（native 库已加载）：abi=$abi repr=$libRepr",
            "True", present
        )
        assertEquals(
            "所有必需的 C exports 必须存在，缺失：$missing",
            "", missing
        )
        assertEquals(
            "BC1 解码冒烟测试必须通过并返回确定的 64 字节白底像素：$smokeError",
            "True", smokeOk
        )
    }
}
