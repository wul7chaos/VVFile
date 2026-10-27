package com.vvfile.app.feature.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.vvfile.app.core.syntax.SyntaxHighlighter

/**
 * 编辑器的语法高亮入口：语法规则来自 `assets/syntax/`（抄的 VS Code TextMate 语法），
 * 分词与配色在 [com.vvfile.app.core.syntax] 里，这里只做一层转发。
 */
object EditorSyntax {

    /** 超过这个字符数就不着色：手机上一次全文分词不划算。 */
    const val MAX_HIGHLIGHT_CHARS = SyntaxHighlighter.MAX_CHARS

    /** 扩展名 → TextMate scopeName；没命中返回 null（不做高亮）。 */
    fun languageFor(path: String): String? = SyntaxHighlighter.scopeFor(path)

    fun spans(text: String, path: String, dark: Boolean): List<AnnotatedString.Range<SpanStyle>> =
        SyntaxHighlighter.spans(text, path, dark)
}
