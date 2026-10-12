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
 * T15/T51：**设备上真实的 ASTC 编解码往返**。
 *
 * 长期缺口是「ASTC 实际解码未验」—— 之前只验到 `System.loadLibrary("astcenc")`
 * 能加载（`PlatformBehaviorTest`），没有真的压过一张图再解回来。
 * 本类补上那一步：**编码 → 解码 → 逐像素比对**，走的是生产
 * `repacker.compress_image_astc` 与 `texture2ddecoder.decode_astc`，
 * 不是替身、不是 mock。
 *
 * ## 判据
 *
 * ASTC 4×4 是**有损**压缩，所以不能要求逐字节相等。断言三层：
 *
 * 1. **块布局**：输出长度必须恰好是 `ceil(w/4) * ceil(h/4) * 16` —— 少一个块或多一个块
 *    都是真错，和画质无关；
 * 2. **整体误差**：平均绝对误差在合理范围（平滑图上 4×4 应该很小）；
 * 3. **平坦区必须干净**：纯色块的解码结果应当**完全等于**原色 ——
 *    这一条最有用：布局错位、通道顺序错（RGBA/BGRA 弄反）都会在这里露出来，
 *    而只比平均误差时它们可能被平滑掉。
 */
@RunWith(AndroidJUnit4::class)
class AstcRoundTripTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun startPython() {
        PythonRuntime.ensureStarted(context)
        assertTrue(Python.isStarted())
    }

    /** 跑一段 Python，返回它设的 `RESULT`（多行字符串）。 */
    private fun python(code: String): String {
        val builtins = Python.getInstance().getModule("builtins")
        val globals = builtins.callAttr("dict")
        // 先 import `unpacker`：它负责把 vendored 的 `UnityPy` 目录插进 `sys.path`。
        // `repacker.repacker` 依赖 UnityPy，不先跑这一步会得到
        // `ModuleNotFoundError: No module named 'UnityPy'`（实测踩过）。
        // 这不是绕过什么 —— 生产路径本来也是先经过 unpacker 才用到 repacker。
        builtins.callAttr("exec", "import unpacker\n", globals)
        builtins.callAttr("exec", code, globals)
        return globals.callAttr("get", "RESULT").toString()
    }

    @Test
    fun astcEncodeDecodeRoundTripPreservesFlatRegions() {
        val result = python(
            """
from repacker import repacker
import texture2ddecoder as t2d

W, H = 64, 48
BX, BY = 4, 4

# 四象限纯色 + 一块渐变。纯色区用来验证「布局/通道没错位」，
# 渐变区用来给出一个有意义的整体误差数字。
flat = [(220, 30, 40, 255), (30, 200, 60, 255), (40, 60, 220, 255), (250, 240, 20, 255)]
src = bytearray(W * H * 4)
for y in range(H):
    for x in range(W):
        i = (y * W + x) * 4
        if x < W // 2 and y < H // 2:
            c = flat[0]
        elif x >= W // 2 and y < H // 2:
            c = flat[1]
        elif x < W // 2:
            c = flat[2]
        else:
            # 右下角伪随机噪声：ASTC 4x4 对噪声**一定有损**，所以这块用来证明
            # 「真的过了压缩」。如果这一块的 mae 也是 0，那说明根本没压，
            # 那种「通过」是假的。
            n = (x * 2654435761 + y * 40503) & 0xFFFFFFFF
            c = (n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, 255)
        src[i:i+4] = bytes(c)

encoded, err = repacker.compress_image_astc(bytes(src), W, H, BX, BY)
if encoded is None:
    RESULT = "ENCODE_FAILED: %s" % err
else:
    expect = ((W + BX - 1) // BX) * ((H + BY - 1) // BY) * 16
    decoded = t2d.decode_astc(encoded, W, H, BX, BY)
    dec = bytes(decoded)
    # decode_astc 返回 uint32 打包的像素，按小端取 RGBA
    if len(dec) == W * H * 4:
        pass
    elif len(dec) == W * H:
        out = bytearray(W * H * 4)
        for p in range(W * H):
            v = dec[p] if isinstance(dec[p], int) else dec[p]
            out[p*4+0] = v & 0xFF
            out[p*4+1] = (v >> 8) & 0xFF
            out[p*4+2] = (v >> 16) & 0xFF
            out[p*4+3] = (v >> 24) & 0xFF
        dec = bytes(out)
    else:
        RESULT = "DECODE_SIZE_UNEXPECTED: %d" % len(dec)
        raise SystemExit

    total = 0
    maxdiff = 0
    for i in range(len(src)):
        d = abs(src[i] - dec[i])
        total += d
        if d > maxdiff:
            maxdiff = d
    mae = total / len(src)

    # 噪声区的误差单独算：它必须 > 0，否则说明「压缩」是直通，不是真编码。
    # 也**只在这里**给上界：ASTC 4x4 对伪随机噪声是最坏情况，全图 maxdiff
    # 会被它主导（实测 121），拿全图 maxdiff 当画质门禁没有意义。
    noisy_total = 0
    noisy_count = 0
    noisy_max = 0
    for y in range(H // 2, H):
        for x in range(W // 2, W):
            i = (y * W + x) * 4
            for k in range(3):
                d = abs(src[i + k] - dec[i + k])
                noisy_total += d
                noisy_count += 1
                if d > noisy_max:
                    noisy_max = d
    noisy_mae = noisy_total / max(1, noisy_count)

    # 平坦区：取每个纯色象限中心的一个 4x4 块，要求完全一致；
    # 并把「平坦部分的整体 maxdiff」也算出来（它应当就是 0）。
    flat_ok = True
    flat_detail = []
    flat_maxdiff = 0
    for (cx, cy) in ((8, 8), (W - 8, 8), (8, H - 8)):
        for yy in range(cy, cy + 4):
            for xx in range(cx, cx + 4):
                i = (yy * W + xx) * 4
                for k in range(4):
                    d = abs(src[i + k] - dec[i + k])
                    if d > flat_maxdiff:
                        flat_maxdiff = d
                if src[i:i+4] != dec[i:i+4]:
                    flat_ok = False
                    flat_detail.append((xx, yy, list(src[i:i+4]), list(dec[i:i+4])))
    RESULT = ("OK encoded=%d expect=%d mae=%.3f maxdiff=%d noisy_mae=%.3f noisy_max=%d "
              "flat_maxdiff=%d flat_ok=%s flat_detail=%s") % (
        len(encoded), expect, mae, maxdiff, noisy_mae, noisy_max,
        flat_maxdiff, flat_ok, flat_detail[:3])
            """.trimIndent()
        )

        assertTrue("ASTC 编码失败：$result", !result.startsWith("ENCODE_FAILED"))
        assertTrue("ASTC 解码尺寸异常：$result", !result.startsWith("DECODE_SIZE_UNEXPECTED"))
        assertTrue("往返未产出结果：$result", result.startsWith("OK "))

        fun num(key: String): Double =
            Regex("$key=([0-9.]+)").find(result)!!.groupValues[1].toDouble()

        val encoded = num("encoded").toInt()
        val expect = num("expect").toInt()
        val mae = num("mae")
        val noisyMae = num("noisy_mae")
        val noisyMax = num("noisy_max")
        val flatMaxdiff = num("flat_maxdiff")
        // Python 打的是 `True`/`False`，不是小写 —— 直接比字符串会把成功的用例判成失败（实测踩过）
        val flatOk = Regex("flat_ok=(\\w+)").find(result)!!.groupValues[1].equals("true", ignoreCase = true)

        assertEquals("ASTC 块布局不对（块数或每块字节数错了）", expect, encoded)
        assertTrue("整图平均绝对误差过大：mae=$mae（原始结果 $result）", mae < 12.0)
        assertTrue(
            "噪声区的误差为 0，说明根本没经过 ASTC 压缩（直通），这个「通过」是假的：$result",
            noisyMae > 0.0
        )
        // 噪声是 ASTC 的最坏情况，只给一个宽松上界（防止「编码器完全没在干活」）
        assertTrue("噪声区误差异常大（$noisyMae / max $noisyMax）：$result", noisyMae < 60.0)
        assertTrue(
            "平坦区没有无损往返（maxdiff=$flatMaxdiff），多半是布局/通道顺序错：$result",
            flatOk && flatMaxdiff == 0.0
        )
    }

    @Test
    fun astcBlockSizeIsHonoured() {
        // 换一个块尺寸：8x8 的块数应当只有 4x4 的四分之一。
        // 这条独立于画质，纯粹验块数公式没写错。
        val result = python(
            """
from repacker import repacker

W, H = 32, 32
src = bytes([128] * (W * H * 4))
out4, err4 = repacker.compress_image_astc(src, W, H, 4, 4)
out8, err8 = repacker.compress_image_astc(src, W, H, 8, 8)
if out4 is None or out8 is None:
    RESULT = "FAILED %s %s" % (err4, err8)
else:
    RESULT = "OK %d %d" % (len(out4), len(out8))
            """.trimIndent()
        )
        assertTrue("ASTC 编码失败：$result", result.startsWith("OK "))
        val parts = result.removePrefix("OK ").trim().split(" ")
        val len4 = parts[0].toInt()
        val len8 = parts[1].toInt()
        assertEquals("4x4 块数不对", (32 / 4) * (32 / 4) * 16, len4)
        assertEquals("8x8 块数不对", (32 / 8) * (32 / 8) * 16, len8)
        assertEquals("8x8 应当正好是 4x4 的四分之一", len4 / 4, len8)
    }

    @Test
    fun astcRejectsAnImageTooSmallForOneBlock() {
        // 边界：比一个块还小的图。要么正常补零编码，要么明确报错 ——
        // 不允许静默产出 0 字节然后被当成成功。
        val result = python(
            """
from repacker import repacker
src = bytes([10, 20, 30, 255] * 4)   # 2x2 RGBA
out, err = repacker.compress_image_astc(src, 2, 2, 4, 4)
RESULT = "OK %s %s" % (None if out is None else len(out), err)
            """.trimIndent()
        )
        assertTrue("未返回结果：$result", result.startsWith("OK "))
        val body = result.removePrefix("OK ").trim()
        val lenText = body.substringBefore(' ')
        if (lenText == "None") {
            assertTrue("报错必须给出原因，不能是空字符串：$body", body.substringAfter(' ').isNotBlank())
        } else {
            assertEquals("2x2 图必须恰好压成一个 4x4 块", 16, lenText.toInt())
        }
    }
}
