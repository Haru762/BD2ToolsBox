package com.example.bd2modmanager

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.bd2modmanager.data.repository.SpineRuntimeRepository
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder

class SpinePreviewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var tempDir: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val skelPath = intent.getStringExtra("skelPath")
        val atlasPath = intent.getStringExtra("atlasPath")
        val tempDirPath = intent.getStringExtra("tempDirPath")

        if (tempDirPath != null) {
            tempDir = File(tempDirPath)
        }

        if (skelPath == null || atlasPath == null) {
            // Handle error, maybe show a toast and finish
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)

        webView?.settings?.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true // Needed for loading local files
        }

        // pixi-spine 不在安装包里（专有许可，与本项目 GPLv3 不兼容），首次预览要先取一次。
        // 已经有了就直接进，不多插一帧提示 —— 常态是秒开。
        val runtime = SpineRuntimeRepository.get(this)
        if (runtime.isReady()) {
            loadPreview(runtime.file, skelPath, atlasPath)
        } else {
            showMessage("正在获取 Spine 运行时…\n\n预览动画需要一个约 1.2 MB 的渲染组件。" +
                    "它不随安装包分发，只需下载一次。")
            lifecycleScope.launch {
                val error = runtime.ensure()
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

    /** 下载中的提示与失败原因都走这里。深色底是为了和预览页的背景一致、不闪白。 */
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
        // Clean up the temporary directory
        tempDir?.let {
            if (it.exists()) {
                it.deleteRecursively()
            }
        }
        // Destroy the WebView to prevent memory leaks
        webView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.removeAllViews()
            it.destroy()
        }
        webView = null
    }
}
