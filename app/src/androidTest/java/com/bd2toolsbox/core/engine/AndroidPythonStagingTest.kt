package com.bd2toolsbox.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bd2toolsbox.core.python.PythonRuntime
import com.chaquo.python.Python
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T50：Android 侧 Python 源集剔除桌面 FMOD 之后的**运行时**事实。
 *
 * 断言的是设备上真实装载的内容与真实 import 结果，不是构建脚本的意图：
 * - 三条排除路径覆盖的桌面平台 FMOD 目录在设备上**不存在**；
 * - 保留的能力（UnityPy 类、导出模块、repacker）仍可 import；
 * - 音频基线不变：Ogg 走容器头直接返回字节（不经 FMOD）；
 *   `pyfmodex` 依旧不可用 —— 排除前后行为一致（排除没有引入退化）。
 */
@RunWith(AndroidJUnit4::class)
class AndroidPythonStagingTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun startPython() {
        PythonRuntime.ensureStarted(context)
        assertTrue(Python.isStarted())
    }

    private fun python(code: String): String {
        val builtins = Python.getInstance().getModule("builtins")
        val globals = builtins.callAttr("dict")
        builtins.callAttr("exec", code, globals)
        return globals.callAttr("get", "RESULT").toString()
    }

    @Test
    fun desktopFmodBinariesAreAbsentOnDevice() {
        val result = python(
            """
import os, sys
roots = []
for entry in sys.path:
    if entry and os.path.isdir(entry):
        roots.append(entry)
found = []
for root in roots:
    for dirpath, dirnames, filenames in os.walk(root):
        norm = dirpath.replace('\\', '/')
        if '/vendor/UnityPy/lib/FMOD/' in norm:
            for name in filenames:
                found.append(norm.split('/vendor/')[-1] + '/' + name)
RESULT = ','.join(sorted(found))
            """.trimIndent()
        )
        assertEquals("设备上不应存在任何桌面 FMOD 二进制", "", result)
    }

    @Test
    fun keptUnityPyModulesStillImport() {
        val result = python(
            """
import os, sys
mods = [
    'UnityPy',
    'UnityPy.export',
    'UnityPy.export.AudioClipConverter',
    'UnityPy.export.Texture2DConverter',
    'UnityPy.export.MeshExporter',
    'UnityPy.export.SpriteHelper',
    'UnityPy.helpers.TypeTreeHelper',
    'UnityPy.helpers.MeshHelper',
]
failed = []
for name in mods:
    try:
        __import__(name)
    except Exception as exc:
        failed.append(name + ':' + type(exc).__name__)
RESULT = ','.join(failed)
            """.trimIndent()
        )
        assertEquals("保留的 UnityPy 模块必须仍可 import", "", result)
    }

    @Test
    fun audioBaselineIsUnchangedByTheExclusion() {
        val result = python(
            """
import io
from UnityPy.export import AudioClipConverter

# 1) pyfmodex 依然不可用（它从来不在 pip 清单里）——排除前后一致
try:
    import pyfmodex
    pyfmodex_state = 'present'
except Exception as exc:
    pyfmodex_state = type(exc).__name__

# 2) Ogg 走容器头直接返回字节，不经过 FMOD
class _Clip(object):
    m_Name = 'sample'
    m_AudioData = b'OggS' + b'\x00' * 32
    m_Resource = None

out = AudioClipConverter.extract_audioclip_samples(_Clip(), False)
RESULT = pyfmodex_state + '|' + ','.join(sorted(out.keys())) + '|' + str(len(out['sample.ogg']))
            """.trimIndent()
        )
        val parts = result.split("|")
        assertEquals("pyfmodex 不可用是既有基线，排除没有改变它", "ModuleNotFoundError", parts[0])
        assertEquals("sample.ogg", parts[1])
        assertEquals(36, parts[2].toInt())
    }

    @Test
    fun repackerEntryPointIsStillImportable() {
        val result = python(
            """
import sys, importlib
# 生产入口自己会把 vendor 加进 sys.path 并导入 repacker.repacker；
# 这里走同一条链，不用测试专用的路径拼接。
failed = []
try:
    import main_script
except Exception as exc:
    failed.append('main_script:' + type(exc).__name__ + ':' + str(exc))
for name in ['repacker.repacker', 'unpacker', 'resolver', 'texture2ddecoder']:
    try:
        importlib.import_module(name)
    except Exception as exc:
        failed.append(name + ':' + type(exc).__name__ + ':' + str(exc))
RESULT = ','.join(failed)
            """.trimIndent()
        )
        assertEquals("应用自身的 Python 入口必须仍可 import", "", result)
    }
}
