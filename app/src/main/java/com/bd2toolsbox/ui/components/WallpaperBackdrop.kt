package com.bd2toolsbox.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 铺在所有内容之下（主题里会把 background/surface 抽成透明，壁纸才透得出来），
 * 上面盖一层可调不透明度的白色遮罩 —— 「壁纸好看」和「字看得清」天生冲突，
 * 而哪张图配多少合适只有用户自己知道，所以给条滑块而不是定死一个值。
 *
 * 遮罩用**白色**而不是 colorScheme.surface：壁纸模式下 surface 本身已经被改成透明了，
 * 拿它当遮罩色等于没盖。整个界面是浅色方案，白色也正是它原本的底色。
 *
 * [uri] 为 null 时什么都不画，一次解码都不做。
 */
@Composable
fun WallpaperBackdrop(uri: Uri?, scrim: Float) {
    if (uri == null) return

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

    Box(Modifier.fillMaxSize()) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 图还没解出来时这层也照画：否则第一帧是纯透明，
        // 深色壁纸配深色文字会闪一下看不清。
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = scrim))
        )
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
