package com.bd2toolsbox.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bd2toolsbox.data.repository.AvatarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 角色头像（列表里那个方块）。
 *
 * 三种状态刻意分开呈现，否则用户分不清「没图」和「网慢」：
 *   有图        → 画图
 *   还在取      → 灰人形图标
 *   确定没有图  → 角色名首字母
 *
 * 「确定没有」是真实存在的情况：那 12 个 file_id 为 `npc*` 的剧情 NPC
 * （Ailee、Guild Girl、Darian Silverstein 等）在角色图鉴数据里没有条目，
 * 图源仓库也没有 `icon_npc` 目录 —— 客观上取不到，不是加载失败。
 * 下载失败（没网、被墙）也归到这一类，同样是「最终没有图」。
 */
@Composable
fun CharacterAvatar(
    characterName: String,
    size: Dp = 40.dp,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember(context) { AvatarRepository.get(context) }

    // 初值走 peek 那条不建表、不解码的快路：附加数据表已就绪且图已解码时，
    // 首帧就能直接画出来，来回滚动不会每次先闪一下占位图。
    val initial = remember(characterName) {
        repo.peekHead(characterName)?.let { (key, _) -> repo.peek(key) }
    }
    var image by remember(characterName) { mutableStateOf(initial) }
    var settled by remember(characterName) { mutableStateOf(initial != null) }

    LaunchedEffect(characterName) {
        if (image == null) {
            // headFor 可能要建表（读 52 KB assets json），所以放 IO 上
            val resolved = withContext(Dispatchers.IO) { repo.headFor(characterName) }
            image = resolved?.let { repo.load(it.first, it.second) }
            settled = true
        }
    }

    AvatarFrame(
        image = image,
        size = size,
        cornerRadius = cornerRadius,
        contentDescription = characterName,
        // 取完了还是没图 —— 显示首字母而不是继续摆着加载中的图标
        fallbackText = if (settled) initialOf(characterName) else null,
        modifier = modifier
    )
}

private fun initialOf(name: String): String {
    val c = name.trim().firstOrNull() ?: return "?"
    return if (c.isLetter()) c.uppercaseChar().toString() else "?"
}

/**
 * 某套皮肤的立绘缩略图（角色卡片里每行那个小图）。
 *
 * [fileId] 是 mod 的 file_id（`char000708` / `cutscene_char004091_1` /
 * `illust_dating16`），内部负责剥出 costume_id 或 dating_id 去反查。
 */
@Composable
fun SkinThumbnail(
    fileId: String?,
    size: Dp = 44.dp,
    cornerRadius: Dp = 6.dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember(context) { AvatarRepository.get(context) }

    var image by remember(fileId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(fileId) {
        if (fileId != null && image == null) {
            val resolved = withContext(Dispatchers.IO) { repo.illustForFileId(fileId) }
            if (resolved != null) {
                image = repo.load(resolved.first, resolved.second)
            }
        }
    }

    // 皮肤行不做首字母兜底：那一行紧挨着就是皮肤名，再放个首字母是重复；
    // 而且这里的图是立绘缩略图（70 KB），慢一点属正常，不该急着判定「没有」。
    AvatarFrame(image, size, cornerRadius, contentDescription, null, modifier)
}

@Composable
private fun AvatarFrame(
    image: ImageBitmap?,
    size: Dp,
    cornerRadius: Dp,
    contentDescription: String?,
    fallbackText: String?,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
            fallbackText != null -> Text(
                fallbackText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Icon(
                Icons.Default.Person,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}
