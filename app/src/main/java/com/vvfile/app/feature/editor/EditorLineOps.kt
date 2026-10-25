package com.vvfile.app.feature.editor

/**
 * 编辑器「行操作」：纯函数，输入原文与光标位置，输出新文本与新光标。
 *
 * MT 的编辑菜单里这些都是按「当前行」操作的；有选区时按选区覆盖的整行范围处理。
 * 行范围一律是左闭右开，不含行尾的 `\n`。
 */
object EditorLineOps {

    /** 光标所在行的区间，不含行尾换行符。 */
    fun lineRange(text: String, cursor: Int): IntRange {
        val safe = cursor.coerceIn(0, text.length)
        val start = startOfLine(text, safe)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return start until end
    }

    private fun startOfLine(text: String, cursor: Int): Int {
        if (cursor <= 0) return 0
        val nl = text.lastIndexOf('\n', cursor - 1)
        return if (nl < 0) 0 else nl + 1
    }

    /** 整行文本（不含换行符）。 */
    fun lineText(text: String, cursor: Int): String {
        val range = lineRange(text, cursor)
        return text.substring(range.first, range.last + 1)
    }

    /** 删除整行（连同行尾换行）。 */
    fun deleteLine(text: String, cursor: Int): Pair<String, Int> {
        val range = lineRange(text, cursor)
        val endWithNewline = if (range.last + 1 < text.length) range.last + 2 else range.last + 1
        val updated = text.removeRange(range.first, endWithNewline.coerceAtMost(text.length))
        return updated to range.first.coerceAtMost(updated.length)
    }

    /** 清空行内容，保留该行（只留换行）。 */
    fun clearLine(text: String, cursor: Int): Pair<String, Int> {
        val range = lineRange(text, cursor)
        val updated = text.removeRange(range.first, range.last + 1)
        return updated to range.first.coerceAtMost(updated.length)
    }

    /** 在当前行下面复制一份同样内容。 */
    fun duplicateLine(text: String, cursor: Int): Pair<String, Int> {
        val range = lineRange(text, cursor)
        val line = text.substring(range.first, range.last + 1)
        val insertAt = range.last + 1
        return if (insertAt >= text.length) {
            // 最后一行且没有行尾换行：补一个换行再复制
            val updated = text + "\n" + line
            updated to updated.length
        } else {
            val insertPos = insertAt + 1
            val updated = text.substring(0, insertPos) + line + "\n" + text.substring(insertPos)
            updated to (insertPos + line.length)
        }
    }

    /** 用给定内容替换当前行。 */
    fun replaceLine(text: String, cursor: Int, replacement: String): Pair<String, Int> {
        val range = lineRange(text, cursor)
        val updated = text.replaceRange(range.first, range.last + 1, replacement)
        return updated to (range.first + replacement.length).coerceIn(0, updated.length)
    }

    /** 选区（或当前行）转大小写。 */
    fun toCase(text: String, selectionStart: Int, selectionEnd: Int, upper: Boolean): Pair<String, Int> {
        val (start, end) = normalizeRange(selectionStart, selectionEnd, text.length)
        val target = if (start != end) {
            text.substring(start, end)
        } else {
            val range = lineRange(text, start)
            text.substring(range.first, range.last + 1)
        }
        val replaced = if (upper) target.uppercase() else target.lowercase()
        val from = if (start != end) start else lineRange(text, start).first
        val updated = text.replaceRange(from, from + target.length, replaced)
        return updated to (from + replaced.length).coerceIn(0, updated.length)
    }

    /** 缩进：每个覆盖行加/减 [spaces] 个空格。 */
    fun indent(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        increase: Boolean,
        spaces: Int = 4,
    ): Pair<String, Int> {
        val (start, end) = normalizeRange(selectionStart, selectionEnd, text.length)
        val firstLine = lineRange(text, start).first
        val lastLine = lineRange(text, end).first
        val blockEnd = lineRange(text, lastLine).last + 1
        val block = text.substring(firstLine, blockEnd)
        val pad = " ".repeat(spaces.coerceAtLeast(1))
        val lines = block.split('\n')
        val replaced = lines.joinToString("\n") { line ->
            if (increase) {
                pad + line
            } else {
                when {
                    line.startsWith(pad) -> line.removePrefix(pad)
                    line.startsWith("\t") -> line.removePrefix("\t")
                    else -> line.trimStart(' ')
                }
            }
        }
        val updated = text.replaceRange(firstLine, blockEnd, replaced)
        return updated to (firstLine + replaced.length).coerceIn(0, updated.length)
    }

    fun normalizeRange(start: Int, end: Int, length: Int): Pair<Int, Int> {
        val a = start.coerceIn(0, length)
        val b = end.coerceIn(0, length)
        return if (a <= b) a to b else b to a
    }

    data class TextStats(
        val lines: Int,
        val chars: Int,
        val charsNoSpace: Int,
        val words: Int,
        val bytesUtf8: Int,
    )

    fun stats(text: String): TextStats {
        val words = text.split(Regex("[\\s\\p{Punct}\\u3000-\\u303f]+"))
            .count { it.isNotBlank() }
        return TextStats(
            lines = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1,
            chars = text.length,
            charsNoSpace = text.count { !it.isWhitespace() },
            words = words,
            bytesUtf8 = text.toByteArray().size,
        )
    }
}
