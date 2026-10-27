package com.vvfile.app.core.syntax

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * scope → 样式。走的是 TextMate 的约定：`keyword.control.java` 这类层级名，
 * 这里按「包含哪一段」自上而下匹配，命中第一条就用那条的颜色。
 *
 * 颜色和应用其它地方保持一致：紫关键字、蓝函数、青类型/属性、绿字符串、
 * 琥珀数字、灰斜体注释、压暗标点；深/浅主题各一份。
 */
object ScopeTheme {

    private class Style(
        val color: Int,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    private class Palette(
        val text: Int,
        val keyword: Int,
        val function: Int,
        val type: Int,
        val string: Int,
        val number: Int,
        val comment: Int,
        val attribute: Int,
        val punctuation: Int,
        val error: Int,
    )

    private val DARK = Palette(
        text = 0xFFE9EDF5.toInt(),
        keyword = 0xFFC792EA.toInt(),
        function = 0xFF82AAFF.toInt(),
        type = 0xFF7FD6E8.toInt(),
        string = 0xFF9FD87A.toInt(),
        number = 0xFFF2B36B.toInt(),
        comment = 0xFF7E8CA3.toInt(),
        attribute = 0xFF7FD6E8.toInt(),
        punctuation = 0xFF8792A6.toInt(),
        error = 0xFFE06C75.toInt(),
    )

    private val LIGHT = Palette(
        text = 0xFF1B2330.toInt(),
        keyword = 0xFF7A3FBF.toInt(),
        function = 0xFF1F5FA8.toInt(),
        type = 0xFF0E7490.toInt(),
        string = 0xFF2E7D32.toInt(),
        number = 0xFFB25B00.toInt(),
        comment = 0xFF7B8496.toInt(),
        attribute = 0xFF0E7490.toInt(),
        punctuation = 0xFF6B7280.toInt(),
        error = 0xFFC62828.toInt(),
    )

    // 越靠前越特殊，命中就停
    private val SELECTORS: List<Pair<String, (Palette) -> Style>> = listOf(
        "invalid" to { p -> Style(p.error) },
        "comment" to { p -> Style(p.comment, italic = true) },
        "keyword.operator" to { p -> Style(p.punctuation) },
        "keyword" to { p -> Style(p.keyword, bold = true) },
        "storage.type.annotation" to { p -> Style(p.attribute) },
        "storage" to { p -> Style(p.keyword, bold = true) },
        // JSON 的键、JS 的对象键：给个区别于字符串的颜色，跟 VS Code 观感一致
        "support.type.property-name" to { p -> Style(p.type) },
        "meta.object-literal.key" to { p -> Style(p.type) },
        "string.regexp" to { p -> Style(p.string) },
        "string" to { p -> Style(p.string) },
        "constant.numeric" to { p -> Style(p.number) },
        "constant.character" to { p -> Style(p.number) },
        "constant.language" to { p -> Style(p.number) },
        "constant" to { p -> Style(p.number) },
        "entity.name.tag" to { p -> Style(p.keyword, bold = true) },
        "entity.other.attribute-name" to { p -> Style(p.attribute) },
        "entity.name.function" to { p -> Style(p.function) },
        "support.function" to { p -> Style(p.function) },
        "entity.name.type" to { p -> Style(p.type) },
        "entity.name.class" to { p -> Style(p.type) },
        "entity.name.namespace" to { p -> Style(p.type) },
        "support.type" to { p -> Style(p.type) },
        "support.class" to { p -> Style(p.type) },
        "variable.language" to { p -> Style(p.keyword) },
        "punctuation" to { p -> Style(p.punctuation) },
        "markup.heading" to { p -> Style(p.keyword, bold = true) },
        "markup.quote" to { p -> Style(p.comment, italic = true) },
        "markup.raw" to { p -> Style(p.string) },
        "markup.bold" to { p -> Style(p.text, bold = true) },
        "markup.italic" to { p -> Style(p.text, italic = true) },
        "markup.underline" to { p -> Style(p.attribute) },
        "meta.package" to { p -> Style(p.text) },
        "meta.import" to { p -> Style(p.text) },
        "meta" to { p -> Style(p.text) },
    )

    /** scope 是空格分隔的多段式名字，任一段命中选择器就算命中。 */
    fun style(scope: String?, dark: Boolean): SpanStyle? {
        if (scope.isNullOrBlank()) return null
        val palette = if (dark) DARK else LIGHT
        val atoms = scope.split(' ')
        for ((prefix, make) in SELECTORS) {
            for (atom in atoms) {
                if (atom.startsWith(prefix)) {
                    val s = make(palette)
                    return SpanStyle(
                        color = Color(s.color),
                        fontWeight = if (s.bold) FontWeight.SemiBold else null,
                        fontStyle = if (s.italic) FontStyle.Italic else null,
                    )
                }
            }
        }
        return null
    }
}
