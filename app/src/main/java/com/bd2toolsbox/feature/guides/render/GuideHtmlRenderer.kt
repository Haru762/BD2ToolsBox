package com.bd2toolsbox.feature.guides.render

/**
 * 攻略正文的主题包装（T47）。
 *
 * 正文 HTML 由 [com.bd2toolsbox.data.repository.GamekeeRepository] 生成并**缓存到磁盘**，
 * 里面写死的是浅色配色（`color:#222`、`#ccc` 边框、`#fef…` 卡片底）。
 * 深色主题下直接把这份 HTML 塞进 WebView 就是「深色页面里的白底正文 / 透明文字」。
 *
 * 这里在**加载时**注入一层主题覆盖，因此：
 * - **旧缓存不用删**：缓存里仍是原文，主题在渲染时套用；
 * - **不改 Wiki 内容**：只追加 `<style>`，不动正文结构与数据；
 * - **幂等**：重复渲染不会叠加多份样式（按 [STYLE_ID] 替换）。
 *
 * 纯函数，可在 JVM 上直接测。
 */
object GuideHtmlRenderer {

    const val STYLE_ID = "bd2-theme-override"

    /** 深色覆盖：与 app 的深色主题语义色对齐。 */
    private val DARK_CSS = """
        :root { color-scheme: dark; }
        body { color:#E6E1E5 !important; background:transparent !important; }
        a { color:#A8C7FA !important; }
        hr { border-top-color:#49454F !important; }
        td, th, .gktable td { border-color:#49454F !important; }
        .gktable td[style], td[style], th[style] { background-color:transparent !important; }
        .hl { border-color:#4A4458 !important; }
        .fold { border-color:#49454F !important; }
        .mention { background:#2B2930 !important; color:#A8C7FA !important; }
        .array-card { background:#2B2930 !important; border-color:#8E7B4F !important;
                      color:#E8C88A !important; }
        .battle { background:#1D1B20 !important; border-color:#6B5F3E !important; }
        .b-desc { background:#2B2930 !important; color:#CAC4D0 !important; }
        .b-v2-cell { background:#2B2930 !important; border-color:#49454F !important; color:#E6E1E5 !important; }
        .b-v2-empty { background:transparent !important; color:#938F99 !important; }
        .b-v3-cname, .b-v2-round > summary, .b-v2-enemy > summary { color:#E6E1E5 !important; }
        .b-v3-sname, .b-v2-note, .b-v2-skin, .b-team-t { color:#CAC4D0 !important; }
        .b-v3-summary { color:#A8C7FA !important; }
        .b-v3-detail { border-top-color:#49454F !important; }
        .tabs .tl { background:#2B2930 !important; color:#CAC4D0 !important; }
        .tabs .tr:checked + .tl { background:#D0BCFF !important; color:#381E72 !important; }
        .tabs .tp { border-color:#49454F !important; }
        .tp-e { color:#D0BCFF !important; }
        .battle .b-head, .battle .b-name { color:#E6E1E5 !important; }
        blockquote { border-left-color:#49454F !important; color:#CAC4D0 !important; }
        code, pre { background:#2B2930 !important; color:#E6E1E5 !important; }
    """.trimIndent()

    /** 浅色：只声明 `color-scheme`，避免表单控件/滚动条与页面底色打架。 */
    private val LIGHT_CSS = """
        :root { color-scheme: light; }
    """.trimIndent()

    /**
     * 把 [rawHtml] 包成当前主题下可读的文档。
     *
     * @param dark 当前是否为深色主题。
     */
    fun render(rawHtml: String, dark: Boolean): String {
        val css = if (dark) DARK_CSS else LIGHT_CSS
        val style = "<style id=\"$STYLE_ID\">\n$css\n</style>"
        val withoutOld = removeExistingStyle(rawHtml)
        return injectBeforeHeadEnd(withoutOld, style)
    }

    /** 幂等：先摘掉上一次注入的样式块。 */
    private fun removeExistingStyle(html: String): String {
        val start = html.indexOf("<style id=\"$STYLE_ID\">")
        if (start < 0) return html
        val end = html.indexOf("</style>", start)
        if (end < 0) return html
        return html.removeRange(start, end + "</style>".length)
    }

    /** 注入到 `</head>` 之前；没有 head 就补一个；再没有就前置。 */
    private fun injectBeforeHeadEnd(html: String, style: String): String {
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        if (headEnd >= 0) return html.substring(0, headEnd) + style + "\n" + html.substring(headEnd)

        val headStart = html.indexOf("<head", ignoreCase = true)
        if (headStart >= 0) {
            val headTagEnd = html.indexOf('>', headStart)
            if (headTagEnd >= 0) {
                return html.substring(0, headTagEnd + 1) + "\n" + style + html.substring(headTagEnd + 1)
            }
        }

        val htmlStart = html.indexOf("<html", ignoreCase = true)
        if (htmlStart >= 0) {
            val htmlTagEnd = html.indexOf('>', htmlStart)
            if (htmlTagEnd >= 0) {
                return html.substring(0, htmlTagEnd + 1) +
                    "\n<head>" + style + "</head>\n" +
                    html.substring(htmlTagEnd + 1)
            }
        }
        return style + "\n" + html
    }
}
