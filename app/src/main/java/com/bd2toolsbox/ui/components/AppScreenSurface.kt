package com.bd2toolsbox.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color

/**
 * 内容面（T40）：文字密集区域的不透明底色。
 *
 * 壁纸分层原则：**壁纸只在背景层可见**（[WallpaperBackdrop] + 根 Scaffold 透明容器），
 * 列表、搜索/筛选、工具栏、菜单、弹窗、底部面板等文字密集区域一律用**不透明**的主题
 * 底色，不让图片穿过关键文字。
 *
 * 取 [MaterialTheme.colorScheme] 的 `background`（T39 起该语义色恒为 alpha=1）；
 * 不用壁纸遮罩色 —— 那只是背景层的事。
 */
@Composable
fun AppScreenSurface(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color.copy(alpha = 1f))
    ) {
        content()
    }
}
