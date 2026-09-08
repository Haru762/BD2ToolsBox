package com.bd2toolsbox

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bd2toolsbox.data.repository.SpineRuntimeRepository
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder

/**
 * Spine 动画预览页：WebView 跑 assets 里的 spine-viewer，加载 mod 的
 * skel/atlas。
 *
 * pixi-spine 运行时不随包分发（专有许可与 GPLv3 不兼容），首次预览时从
 * npm CDN 取一次（SpineRuntimeRepository 管）；取到前先显示提示页。
 */
class SpinePreviewActivity : ComponentActivity() {

    private var webView: WebView? = null

    /** 预览用的临时目录（解包产物落在里面），onDestroy 时连根清掉。 */
    private var tempDir: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val skelPath = intent.getStringExtra("skelPath")
        val atlasPath = intent.getStringExtra("atlasPath")
        intent.getStringExtra("tempDirPath")?.let { tempDir = File(it) }

        if (skelPath == null || atlasPath == null) {
            finish()
            return
        }

        webView = WebView(this).apply {
            setContentView(this)
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                domStorageEnabled = true
                // 预览页要从 file:// 里再读本地文件（mod 资产与运行时），两条都要开
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }
        }

        val runtime = SpineRuntimeRepository.get(this)
        if (runtime.isReady()) {
            loadPreview(runtime.file, skelPath, atlasPath)
        } else {
            showMessage("正在获取 Spine 运行时…\n\n预览动画需要一个约 1.2 MB 的渲染组件。" +
                    "它不随安装包分发，只需下载一次。")
            lifecycleScope.launch {
                val error = runtime.ensure()
                // 已经有了就直接进，不多插一帧提示 —— 常态是秒开
                if (error == null) loadPreview(runtime.file, skelPath, atlasPath)
                else showMessage(error)
            }
        }
    }

    private fun loadPreview(runtimeFile: File, skelPath: String, atlasPath: String) {
        // 路径进 query 前都要编码：mod 名里带空格和中文很常见
        val skel = URLEncoder.encode(skelPath, "UTF-8")
        val atlas = URLEncoder.encode(atlasPath, "UTF-8")
        val rt = URLEncoder.encode("file://${runtimeFile.absolutePath}", "UTF-8")
        webView?.loadUrl(
            "file:///android_asset/spine-viewer/preview.html?skel=$skel&atlas=$atlas&runtime=$rt"
        )
    }

    /** 运行时下载中的提示与失败原因都走这里。深色底与预览页背景一致、不闪白。 */
    private fun showMessage(text: String) {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        webView?.loadDataWithBaseURL(
            null,
            """
            <html><body style="margin:0;background:#1a1a1a">
            <div style="color:#ddd;font:14px/1.7 sans-serif;padding:24px;white-space:pre-wrap">$safe</div>
            </body></html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        tempDir?.takeIf { it.exists() }?.deleteRecursively()
        // WebView 必须显式拆掉，否则渲染线程泄漏
        webView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.removeAllViews()
            it.destroy()
        }
        webView = null
    }
}
