package com.bd2toolsbox.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.ui.components.WorkbenchRow
import com.bd2toolsbox.ui.components.WorkbenchSection
import com.bd2toolsbox.ui.theme.AppTheme
import com.bd2toolsbox.ui.viewmodel.MainViewModel

/**
 * 设置 —— 底部弹层形式的功能与参数集合。
 *
 * 之所以做成弹层而不是一个页面：它不是「另一批 mod」，而是随时要用一下的参数与工具。
 * 弹层能盖在当前列表上、关掉就回到原处。
 *
 * 早先它是底部导航的第二项、叫「工作台」；两类 mod 合并成「mods」之后底部只剩一项，
 * 整条底部栏就去掉了，入口改成顶栏右上角的齿轮，名字也随之回归「设置」。
 * 文件与内部符号仍沿用 Workbench 一词（[WorkbenchRow] / [WorkbenchSection] 是通用行/分组件），
 * 只有露给用户的那个标题是「设置」。
 *
 * 结构上按「什么时候会用到」分组，加新功能时往对应组塞一个 [WorkbenchRow] 即可：
 *   转换设置 — 开工前要定的参数
 *   原版备份 — 影响卸载能否免流量
 *   游戏目录 — 对游戏的重操作
 *   外观     — 纯显示偏好
 *   高级     — 平时用不到的
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchSheet(
    viewModel: MainViewModel,
    onUninstallAll: () -> Unit,
    onUnpackTool: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onPrepack: () -> Unit,
    onPickWallpaper: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onDismiss: () -> Unit
) {
    val quality by viewModel.selectedQuality.collectAsState()
    val useAstc by viewModel.useAstc.collectAsState()
    val backupOn by viewModel.backupOriginals.collectAsState()
    val backupUsage by viewModel.backupUsage.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val wallpaperUri by viewModel.wallpaperUri.collectAsState()
    val wallpaperScrim by viewModel.wallpaperScrim.collectAsState()
    val hiddenCount by viewModel.hiddenCount.collectAsState()
    val previewCache by viewModel.previewCacheUsage.collectAsState()
    val spineRuntime by viewModel.spineRuntimeUsage.collectAsState()
    val avatarCache by viewModel.avatarCacheUsage.collectAsState()
    val prepackProgress by viewModel.prepackProgress.collectAsState()

    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )

            // ---------------------------------------------------------- 转换设置
            WorkbenchSection("转换设置") {
                // 下载画质选项已移除：App 会按游戏目录内容哈希自动检测 HD/SD
                // 并切换（见 MainViewModel.detectQualityAndFetchMeta），手动选档
                // 在检测面前没有价值，选错了反而让 mod 装进游戏不读的哈希目录。
                WorkbenchRow(
                    title = "ASTC 压缩",
                    subtitle = "产物体积更小，转换更慢",
                    trailing = {
                        Switch(checked = useAstc, onCheckedChange = { viewModel.setUseAstc(it) })
                    }
                )
            }

            // ---------------------------------------------------------- 原版备份
            WorkbenchSection("原版备份") {
                WorkbenchRow(
                    title = "装入前自动备份",
                    subtitle = "装哪个备份哪个，卸载时可直接拷回、无需下载",
                    trailing = {
                        Switch(checked = backupOn, onCheckedChange = { viewModel.setBackupOriginals(it) })
                    }
                )
                WorkbenchRow(
                    title = "已备份 ${backupUsage.second} 个",
                    subtitle = "占用 ${formatBytes(backupUsage.first)}" +
                            if (backupUsage.second > 0) " · 清空后卸载将改为重新下载" else "",
                    trailing = {
                        if (backupUsage.second > 0) {
                            TextButton(
                                onClick = { viewModel.clearBackups() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("清空") }
                        }
                    }
                )
            }

            // ---------------------------------------------------------- 预览缓存
            WorkbenchSection("预览缓存") {
                WorkbenchRow(
                    title = "批量预解包",
                    subtitle = "提前把产物解包存好，之后预览动画秒开",
                    enabled = prepackProgress == null,
                    onClick = onPrepack,
                    trailing = {
                        if (prepackProgress != null) {
                            Text(
                                "${prepackProgress!!.done}/${prepackProgress!!.total}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                )
                WorkbenchRow(
                    title = "已缓存 ${previewCache.second} 个",
                    subtitle = "占用 ${formatBytes(previewCache.first)}" +
                            if (previewCache.second > 0) " · 清空后预览需重新解包" else "",
                    trailing = {
                        if (previewCache.second > 0) {
                            TextButton(
                                onClick = { viewModel.clearPreviewCache() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("清空") }
                        }
                    }
                )
                // 渲染组件单独列一行：它不在安装包里（许可不允许），是首次预览时下载的。
                // 只显示状态不提供清空 —— 下载是原子的（.part + 改名），最终文件不可能
                // 是半截的，坏了重下这个场景不存在；清空只会让下次预览白白重下一次。
                WorkbenchRow(
                    title = if (spineRuntime > 0) "渲染组件已就绪" else "渲染组件未下载",
                    subtitle = if (spineRuntime > 0)
                        "占用 ${formatBytes(spineRuntime)} · 预览动画靠它渲染"
                    else
                        "首次预览动画时自动下载（约 1.2 MB）。它的许可不允许随安装包分发，只能单独取"
                )
            }

            // ---------------------------------------------------------- 角色头像
            WorkbenchSection("角色头像") {
                WorkbenchRow(
                    title = if (avatarCache.second > 0) "已下载 ${avatarCache.second} 张"
                            else "尚未下载",
                    // 图从 GitHub 取，国内不挂代理大概率连不上 —— 说清楚了，
                    // 用户看到一片占位图标时才知道该往哪儿找原因。
                    subtitle = if (avatarCache.second > 0)
                        "占用 ${formatBytes(avatarCache.first)} · 滚动列表时按需下载"
                    else
                        "列表滚到哪就下哪张（约 10 KB/张）。图源在 GitHub，连不上会一直显示占位图标",
                    trailing = {
                        if (avatarCache.second > 0) {
                            TextButton(
                                onClick = { viewModel.clearAvatarCache() },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("清空") }
                        }
                    }
                )
            }

            // ---------------------------------------------------------- 游戏目录
            WorkbenchSection("游戏目录") {
                WorkbenchRow(
                    title = "一键卸载全部 mod",
                    subtitle = "把游戏里所有被改过的资源还原成官方原版",
                    onClick = onUninstallAll,
                    trailing = {
                        Icon(
                            Icons.Default.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
                WorkbenchRow(
                    title = "重新检测 Shizuku",
                    subtitle = "启动 Shizuku 后点这里，无需重跑转换",
                    onClick = onRecheckShizuku,
                    trailing = { Icon(Icons.Default.ChevronRight, null) }
                )
            }

            // ---------------------------------------------------------- 外观
            WorkbenchSection("外观") {
                WorkbenchRow(
                    title = "跟随壁纸取色",
                    subtitle = if (dynamicSupported) "使用系统壁纸的配色方案，开启后下面的配色不生效"
                               else "需要 Android 12 或更高版本",
                    enabled = dynamicSupported,
                    trailing = {
                        Switch(
                            checked = dynamicColor && dynamicSupported,
                            enabled = dynamicSupported,
                            onCheckedChange = { viewModel.setDynamicColor(it) }
                        )
                    }
                )

                // 配色预设。做成一行色点而不是下拉菜单：选颜色这件事，
                // 直接看到颜色比读到「石墨」两个字快得多。
                WorkbenchRow(
                    title = "界面配色",
                    subtitle = if (dynamicColor && dynamicSupported) "已被「跟随壁纸取色」接管"
                               else "当前：${appTheme.label}"
                ) {}
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val locked = dynamicColor && dynamicSupported
                    AppTheme.entries.forEach { t ->
                        val selected = t == appTheme && !locked
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (locked) t.swatch.copy(alpha = 0.35f) else t.swatch)
                                .then(
                                    if (selected) Modifier.border(
                                        3.dp,
                                        MaterialTheme.colorScheme.onSurface,
                                        CircleShape
                                    ) else Modifier
                                )
                                .clickable(enabled = !locked) { viewModel.setAppTheme(t) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = t.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                WorkbenchRow(
                    title = if (wallpaperUri == null) "自定义壁纸" else "更换壁纸",
                    subtitle = if (wallpaperUri == null) "选一张图当界面背景"
                               else "拖下面的滑块调整它透出来的程度",
                    onClick = onPickWallpaper,
                    trailing = {
                        if (wallpaperUri != null) {
                            TextButton(
                                onClick = { viewModel.setWallpaper(null) },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("移除") }
                        } else {
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                )

                // 滑块只在有壁纸时出现 —— 没壁纸时它调不出任何可见变化，
                // 摆在那里只会让人以为坏了。
                if (wallpaperUri != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "壁纸清晰度",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                // 滑块值是遮罩不透明度，越大壁纸越淡。说给用户听要反过来，
                                // 否则「往右拖反而更看不见」就很费解。
                                "${((1f - wallpaperScrim) * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = 1f - wallpaperScrim,
                            onValueChange = { viewModel.setWallpaperScrim(1f - it) },
                            // 上限 0.75：留 25% 的白底垫着，否则深色壁纸上的正文
                            // 直接没法读了。宁可不给到「壁纸全见」也不能让界面用不了。
                            valueRange = 0f..0.75f
                        )
                        Text(
                            "越往右壁纸越清楚、文字越淡。看不清就往左拖一点。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---------------------------------------------------------- 高级
            WorkbenchSection("高级", showDivider = false) {
                WorkbenchRow(
                    title = "重看使用引导",
                    subtitle = "从头过一遍「怎么把 mod 装进游戏」",
                    onClick = onReplayOnboarding,
                    trailing = { Icon(Icons.Default.ChevronRight, null) }
                )
                WorkbenchRow(
                    title = "恢复已隐藏的项",
                    subtitle = if (hiddenCount > 0) "当前隐藏了 $hiddenCount 个" else "没有隐藏的条目",
                    enabled = hiddenCount > 0,
                    onClick = { viewModel.clearHidden() },
                    trailing = { Icon(Icons.Default.ChevronRight, null) }
                )
                WorkbenchRow(
                    title = "解包工具",
                    subtitle = "把某个 bundle 里的资源导出到 Download/outputs/",
                    onClick = onUnpackTool,
                    trailing = { Icon(Icons.Default.ChevronRight, null) }
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
