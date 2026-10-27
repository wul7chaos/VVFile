package com.vvfile.app.core.syntax

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * 对外唯一入口：路径 + 文本 + 主题 → Compose 的 span 列表。
 *
 * 真正的分词在 [TextMateTokenizer]，这里只做三件事：长度闸门、scope 到样式的翻译、
 * 相邻同样式区间的合并（少几万个 span，`BasicTextField` 排版不至于被拖死）。
 */
object SyntaxHighlighter {

    /** 超过这个字符数就不着色：手机上一次全文分词不划算。 */
    const val MAX_CHARS = 40_000

    private val tokenizer = TextMateTokenizer(GrammarRegistry)

    fun scopeFor(path: String): String? = GrammarRegistry.scopeFor(path)

    fun spans(text: String, path: String, dark: Boolean): List<AnnotatedString.Range<SpanStyle>> {
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        val scope = GrammarRegistry.scopeFor(path) ?: return emptyList()
        val tokens = runCatching { tokenizer.tokenize(scope, text) }.getOrElse { return emptyList() }
        if (tokens.isEmpty()) return emptyList()

        val out = ArrayList<AnnotatedString.Range<SpanStyle>>(tokens.size / 2 + 8)
        var runStart = -1
        var runEnd = -1
        var runStyle: SpanStyle? = null

        fun flush() {
            val style = runStyle
            if (style != null && runStart >= 0 && runEnd > runStart) {
                out.add(AnnotatedString.Range(style, runStart, runEnd))
            }
            runStart = -1
            runEnd = -1
            runStyle = null
        }

        for (token in tokens) {
            val style = ScopeTheme.style(token.scope, dark)
            if (style == null) {
                flush()
                continue
            }
            if (runStyle == style && runEnd == token.start) {
                runEnd = token.end
            } else {
                flush()
                runStart = token.start
                runEnd = token.end
                runStyle = style
            }
        }
        flush()
        return out
    }
}
