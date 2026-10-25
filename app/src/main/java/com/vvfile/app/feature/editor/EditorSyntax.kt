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
            "json", "xml", "html", "htm", "xhtml", "css", "scss", "less",
            "yaml", "yml", "toml", "ini", "properties",
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
        return runCatching {
            if (isMarkup(path)) markupSpans(text, dark) else compute(text, language, dark)
        }.getOrDefault(emptyList())
    }

    /** HTML / XML / SVG 这类标记语言，库没有对应语言，这里用正则补一套着色。 */
    private fun markupSpans(
        text: String,
        dark: Boolean,
    ): List<AnnotatedString.Range<SpanStyle>> {
        val tag = if (dark) 0xFF61AFEF else 0xFF1A4F8A
        val attr = if (dark) 0xFFE5C07B else 0xFF7A5A00
        val value = if (dark) 0xFF98C379 else 0xFF1B6E3C
        val comment = if (dark) 0xFF7F8794 else 0xFF5A6270
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()

        fun add(start: Int, end: Int, color: Long) {
            if (start in 0 until end && end <= text.length) {
                spans.add(
                    AnnotatedString.Range(SpanStyle(color = Color(color.toInt())), start, end),
                )
            }
        }

        Regex("<!--[\\s\\S]*?-->").findAll(text).forEach { m ->
            add(m.range.first, m.range.last + 1, comment)
        }
        // 标签名（含结束标签与自闭合）
        Regex("</?([A-Za-z][\\w:.-]*)").findAll(text).forEach { m ->
            add(m.range.first, m.range.last + 1, tag)
        }
        // 属性名（后面跟着 =）
        Regex("[A-Za-z_:][\\w:.-]*(?=\\s*=)").findAll(text).forEach { m ->
            add(m.range.first, m.range.last + 1, attr)
        }
        // 属性值
        Regex("\"[^\"]*\"|'[^']*'").findAll(text).forEach { m ->
            add(m.range.first, m.range.last + 1, value)
        }
        return spans
    }

    private fun isMarkup(path: String): Boolean {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext in setOf("html", "htm", "xhtml", "xml", "svg", "vue")
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
