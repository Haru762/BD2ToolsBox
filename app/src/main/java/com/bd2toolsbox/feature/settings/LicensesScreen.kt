package com.bd2toolsbox.feature.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

/**
 * 开源许可页（T38）。
 *
 * 用 AboutLibraries 的 Material3 `LibrariesContainer` 替换手写展示：数据来自
 * **构建期生成、随 APK 分发的 `res/raw/aboutlibraries.json`**（见 T37），所以
 * **离线可读**，无需网络；长列表自带滚动；配色与文字样式跟随当前 `MaterialTheme`
 * （明暗/主题切换自动生效）。
 *
 * 非 Maven 组件（Python/原生/JS）的定义与全文见
 * `config/aboutlibraries/libraries/`、`licenses/` 与 `THIRD-PARTY-NOTICES.md`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开源许可") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LibrariesContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}
