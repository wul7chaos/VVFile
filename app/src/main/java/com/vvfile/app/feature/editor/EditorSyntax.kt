package com.vvfile.app.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * 语法高亮：用 `dev.snipme:highlights` 做词法着色，输出 Compose 的 SpanStyle。
 *
 * 只对常见代码文件生效；纯文本 / 超大文件直接返回空列表，避免拖慢输入。
 */
object EditorSyntax {

    /** 超过这个字符数就不高亮了：逐键解析会明显拖慢输入。 */
    const val MAX_HIGHLIGHT_CHARS = 60_000

    /** 扩展名 → 语言。没命中返回 null（不做高亮）。 */
    fun languageFor(path: String): SyntaxLanguage? {
        val name = path.substringAfterLast('/').lowercase()
        val ext = name.substringAfterLast('.', "")
        return when (ext) {
            "kt", "kts" -> SyntaxLanguage.KOTLIN
            "java" -> SyntaxLanguage.JAVA
            "js", "mjs", "cjs" -> SyntaxLanguage.JAVASCRIPT
            "ts" -> SyntaxLanguage.TYPESCRIPT
            "c", "h" -> SyntaxLanguage.C
            "cpp", "cc", "cxx", "hpp" -> SyntaxLanguage.CPP
            "cs" -> SyntaxLanguage.CSHARP
            "py" -> SyntaxLanguage.PYTHON
            "rb" -> SyntaxLanguage.RUBY
            "pl" -> SyntaxLanguage.PERL
            "php" -> SyntaxLanguage.PHP
            "go" -> SyntaxLanguage.GO
            "rs" -> SyntaxLanguage.RUST
            "swift" -> SyntaxLanguage.SWIFT
            "dart" -> SyntaxLanguage.DART
            "sh", "bash", "zsh" -> SyntaxLanguage.SHELL
            "coffee" -> SyntaxLanguage.COFFEESCRIPT
            "json", "xml", "yaml", "yml", "toml", "ini", "properties",
            "gradle", "md", "markdown", "txt", "log", "csv",
            -> SyntaxLanguage.DEFAULT
            else -> null
        }
    }

    /**
     * 计算高亮区间。
     *
     * @param dark 深色主题用 dark 主题表，浅色用 light，保证对比度。
     */
    fun spans(text: String, path: String, dark: Boolean): List<AnnotatedString.Range<SpanStyle>> {
        val language = languageFor(path) ?: return emptyList()
        if (text.isEmpty() || text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        return runCatching { compute(text, language, dark) }.getOrDefault(emptyList())
    }

    private fun compute(
        text: String,
        language: SyntaxLanguage,
        dark: Boolean,
    ): List<AnnotatedString.Range<SpanStyle>> {
        val result = Highlights.Builder()
            .code(text)
            .language(language)
            .theme(SyntaxThemes.default(dark))
            .build()
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        result.getHighlights().forEach { highlight ->
            if (highlight !is ColorHighlight) return@forEach
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || end > text.length || start >= end) return@forEach
            spans.add(
                AnnotatedString.Range(
                    item = SpanStyle(color = Color(0xFF000000.toInt() or highlight.rgb)),
                    start = start,
                    end = end,
                ),
            )
        }
        return spans
    }
}
