package com.vvfile.app.feature.editor

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** 编辑区 / 预览区的排布方式。 */
enum class PreviewLayout { SPLIT, EDIT_ONLY, PREVIEW_ONLY }

/** 预览类型，由扩展名决定。 */
enum class PreviewKind {
    NONE,
    MARKDOWN,
    HTML,
    ;

    companion object {
        fun of(path: String): PreviewKind {
            val name = path.substringAfterLast('/').lowercase()
            return when (name.substringAfterLast('.', "")) {
                "md", "markdown", "mdown", "mkd" -> MARKDOWN
                "html", "htm", "xhtml" -> HTML
                else -> NONE
            }
        }
    }
}

/**
 * Markdown / HTML 渲染。
 *
 * Markdown 走 commonmark（GFM 扩展：删除线、任务列表）转成 HTML，再交给 WebView 显示；
 * HTML 直接交给 WebView。样式内联，跟随主题深浅。
 */
object PreviewRenderer {


    private val parser: Parser = Parser.builder().build()

    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()
    fun markdownToHtml(markdown: String): String = renderer.render(parser.parse(markdown))

    /** 统一套一层样式与 viewport，保证手机上排版正常。 */
    fun wrap(html: String, dark: Boolean): String {
        val bg = if (dark) "#12161E" else "#FFFFFF"
        val fg = if (dark) "#D7DEE8" else "#1B1F27"
        val muted = if (dark) "#8B94A6" else "#5A6270"
        val border = if (dark) "#2A323F" else "#DCE0E8"
        val codeBg = if (dark) "#1A2029" else "#F2F4F8"
        val accent = if (dark) "#34D399" else "#0B8A5F"
        return """
            <!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=3">
            <style>
              body { background: $bg; color: $fg; margin: 12px; font-size: 15px; line-height: 1.65;
                     word-wrap: break-word; font-family: -apple-system, 'Noto Sans SC', sans-serif; }
              h1, h2, h3, h4 { line-height: 1.3; margin: 1.1em 0 .5em; }
              h1 { font-size: 1.5em; border-bottom: 1px solid $border; padding-bottom: .3em; }
              h2 { font-size: 1.28em; border-bottom: 1px solid $border; padding-bottom: .25em; }
              h3 { font-size: 1.12em; }
              a { color: $accent; text-decoration: none; }
              code { background: $codeBg; padding: 1px 4px; border-radius: 2px;
                     font-family: ui-monospace, Menlo, monospace; font-size: .92em; }
              pre { background: $codeBg; padding: 10px; overflow-x: auto; border-radius: 2px; }
              pre code { padding: 0; background: transparent; }
              blockquote { margin: .6em 0; padding: .2em .9em; border-left: 3px solid $border; color: $muted; }
              table { border-collapse: collapse; width: 100%; }
              th, td { border: 1px solid $border; padding: 5px 8px; }
              img { max-width: 100%; }
              hr { border: none; border-top: 1px solid $border; margin: 1.2em 0; }
              ul.task-list-item { list-style: none; margin-left: -1.4em; }
            </style></head><body>
            $html
            </body></html>
        """.trimIndent()
    }
}

/**
 * 右侧预览窗格。Markdown 实时转换；输入做 250ms 防抖，避免每敲一个字就重排 WebView。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewPane(
    kind: PreviewKind,
    text: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    var rendered by remember { mutableStateOf("") }
    LaunchedEffect(kind, text, dark) {
        if (kind == PreviewKind.NONE) return@LaunchedEffect
        delay(250)
        rendered = when (kind) {
            PreviewKind.MARKDOWN -> PreviewRenderer.wrap(PreviewRenderer.markdownToHtml(text), dark)
            PreviewKind.HTML -> PreviewRenderer.wrap(text, dark)
            PreviewKind.NONE -> ""
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(if (dark) 0xFF12161E.toInt() else 0xFFFFFFFF.toInt())
            }
        },
        update = { webView ->
            if (rendered.isNotEmpty() && webView.tag != rendered) {
                webView.tag = rendered
                webView.loadDataWithBaseURL(null, rendered, "text/html", "utf-8", null)
            }
        },
    )
}