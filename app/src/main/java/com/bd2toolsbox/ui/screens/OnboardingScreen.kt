package com.bd2toolsbox.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 首次启动的使用引导 —— 把「装一个 mod」这件事从头到尾讲一遍。
 *
 * **为什么不做成高亮真实按钮的那种**：首次启动时列表是空的、右下角的操作按钮
 * （要选中 mod 才出现）压根不存在，没有东西可高亮。所以改成分步说明，
 * 并在最要紧的那一步（选 mod 文件夹）直接给一个能用的按钮，看完就能接着做。
 *
 * 顺序是按「卡住的先后」排的，不是按功能重要性：Shizuku 排在选文件夹前面，
 * 因为它没跑起来的话，后面每一步都能做、只有最后装入会失败 ——
 * 那时候用户已经等完一轮转换了，最挫败。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onPickFolder: () -> Unit,
    onFinish: () -> Unit
) {
    val pages = remember { onboardingPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == pages.lastIndex

    Surface(modifier = Modifier.fillMaxSize()) {
        // 全屏浮层盖过主界面，得自己避让状态栏，不然「跳过」会顶进系统栏
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 跳过放在顶部右上：想读的人不会误触，不想读的人一眼就能找到
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onFinish) { Text("跳过") }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { index ->
                OnboardingPage(page = pages[index], onPickFolder = onPickFolder)
            }

            // 页码点
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (active) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // edge-to-edge 后窗口延伸到导航条后面，按钮行得自己避让，
                    // 不然三键导航下「下一步」会被 scrim 压暗、下半截点不到
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 第一页没有「上一步」可回，留个等宽的空位免得「下一步」跳位置
                if (pagerState.currentPage > 0) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Text("上一步") }
                } else {
                    Spacer(Modifier.width(64.dp))
                }

                Button(
                    onClick = {
                        if (isLast) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                ) { Text(if (isLast) "开始使用" else "下一步") }
            }
        }
    }
}

/**
 * 一页引导的内容。
 *
 * [bullets] 是要点，[note] 是那种「不说会踩坑」的补充。分开而不是都塞进正文，
 * 是因为要点要能一眼扫完 —— 混在一起用户就一句都不看了。
 */
private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val bullets: List<String> = emptyList(),
    val note: String? = null,
    /** 这一页要不要给「现在就选文件夹」的按钮 */
    val withFolderAction: Boolean = false
)

private fun onboardingPages() = listOf(
    OnboardingPage(
        icon = Icons.Default.Extension,
        title = "把 mod 装进游戏",
        body = "这个工具会把 PC 版的 mod 转成安卓版读得懂的格式，再替换进游戏的本地缓存。",
        bullets = listOf(
            "全程在你自己的手机上完成，不上传任何东西",
            "改的只是本机缓存，随时能一键还原成官方原版"
        )
    ),
    OnboardingPage(
        icon = Icons.Default.Key,
        title = "第一步：让 Shizuku 跑起来",
        body = "安卓 11 起不许普通应用读写别的应用的数据目录，而 mod 正是要写进游戏目录，" +
                "所以必须借 Shizuku 提权。",
        bullets = listOf(
            "先装 Shizuku，按它自己的说明启动（免 root 用无线调试，有 root 直接点启动）",
            "回到本工具，第一次会弹授权框，点「允许」"
        ),
        note = "没有 Shizuku 也能转换，但装不进游戏 —— 会白等一轮转换，所以先弄好它。"
    ),
    OnboardingPage(
        icon = Icons.Default.FolderOpen,
        title = "第二步：告诉工具 mod 放在哪",
        body = "点左上角的文件夹图标 →「添加 mod 文件夹」，选装着 mod 的那个目录。",
        bullets = listOf(
            "子目录会自动往下找，不必一个个选",
            "可以添加多个目录，它们会合并成同一份列表",
            "同一个菜单里的「管理文件夹」能看到都记住了哪些、并单独移除"
        ),
        withFolderAction = true
    ),
    OnboardingPage(
        icon = Icons.Default.PlaylistAddCheck,
        title = "第三步：勾选，然后装入",
        body = "在列表里勾上想装的 mod，右下角会出现操作按钮。",
        bullets = listOf(
            "PC 版 mod → 点「转换所选」，转换完再装入",
            "已经转换好的产物 → 点「直接装入」，几秒就好",
            "同一个资源包里只能装一个 mod，勾多了会提示哪些会被覆盖"
        ),
        note = "转换要联网从官方 CDN 取原版资源当底板。游戏目录里已有的会直接复用，能省不少流量。"
    ),
    OnboardingPage(
        icon = Icons.Default.RestartAlt,
        title = "最后：重启游戏",
        body = "装完必须把游戏完全关掉再打开，切后台不算。",
        bullets = listOf(
            "想还原：设置 →「一键卸载全部 mod」",
            "开着「装入前自动备份」的话，卸载能直接拷回原版、不用重新下载"
        ),
        note = "这份引导之后可以在 设置 → 高级 →「重看使用引导」再打开。"
    )
)

@Composable
private fun OnboardingPage(page: OnboardingPage, onPickFolder: () -> Unit) {
    // 限宽 + 居中：横屏（模拟器和平板上是常态）宽度有 1920，不限的话正文会拉成
    // 一行一米长、要点贴在最左边离标题老远，整页看着是散的。
    // 外层 Box 负责居中，滚动挂在内层 —— 内容短时居中、长时能滚，两种都成立。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (page.bullets.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                page.bullets.forEach { line ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            // 要点左对齐：居中的多行文本每行起点都不一样，扫读很累
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }

        if (page.withFolderAction) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onPickFolder) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("现在就选一个文件夹")
            }
        }

        page.note?.let {
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 1.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        }
    }
}
