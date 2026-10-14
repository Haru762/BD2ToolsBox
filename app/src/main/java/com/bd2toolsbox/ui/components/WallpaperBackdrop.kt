package com.bd2toolsbox.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义壁纸底层。
 *
 * T40 分层：这是**独立的背景层**，铺在所有内容之下（根 Scaffold 容器透明），
 * 上面盖一层可调不透明度的底色遮罩 —— 「壁纸好看」和「字看得清」天生冲突，
 * 而哪张图配多少合适只有用户自己知道，所以给条滑块而不是定死一个值。
 * 文字密集区域用 [AppScreenSurface] 的不透明底色，**不**靠全局透明让图片穿过文字。
 *
 * 遮罩跟主题走：取 colorScheme 里不透明的底色，而不是写死 Color.White —— 换配色
 * 预设、开「跟随壁纸取色」时遮罩会跟着变，不再永远是一层纯白。
 *
 * 用 surfaceContainerLowest（始终不透明、最接近 background），**不用**
 * background/surface —— T40 后它们恒不透明，但遮罩需要能单独叠在图上；
 * 且 Color.Transparent 的 RGB 是黑，copy(alpha = scrim) 会得到一层黑色遮罩。
 *
 * [uri] 为 null 时什么都不画，一次解码都不做。
 */
@Composable
fun WallpaperBackdrop(uri: Uri?, scrim: Float) {
    // 始终铺一层不透明的主题背景色兜底：
    // 1) 未设置壁纸（uri == null）时，根 Scaffold 为透明，若不画这层底色，
    //    顶栏（topBar）与状态栏会直接漏出 themes.xml 的 windowBackground（纯黑），
    //    导致浅色主题下的深色顶栏文字/图标在黑底上完全看不见；
    // 2) 设置了壁纸但异步解码尚未完成或 URI 失效时，也不会闪出黑底。
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (uri == null) return@Box

        val context = LocalContext.current
        val config = LocalConfiguration.current
        val density = LocalDensity.current

        // 按屏幕像素宽解码，别把一张 4000×3000 的原图整个读进内存 ——
        // 它只是背景，超过屏幕的分辨率一个像素都用不上。
        val targetPx = remember(config.screenWidthDp, density) {
            with(density) { config.screenWidthDp.dp.toPx() }.toInt().coerceAtLeast(720)
        }

        var image by remember(uri, targetPx) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(uri, targetPx) {
            image = withContext(Dispatchers.IO) { decodeScaled(context, uri, targetPx) }
        }

        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = scrim))
            )
        }
    }
}

private fun decodeScaled(context: Context, uri: Uri, targetPx: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
    }
} catch (e: Exception) {
    // 权限被回收、文件被删、格式不认 —— 都退回没有壁纸，不打扰用户
    Log.w("WallpaperBackdrop", "壁纸读取失败: $uri", e)
    null
} catch (e: OutOfMemoryError) {
    Log.w("WallpaperBackdrop", "壁纸太大，内存不足: $uri")
    null
}

private val Int.dp: androidx.compose.ui.unit.Dp
    get() = androidx.compose.ui.unit.Dp(this.toFloat())
