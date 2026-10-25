package com.vvfile.app.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class LineIndexTest {

    @Test
    fun `空文本只有一行`() {
        val index = LineIndex("")
        assertEquals(1, index.lineCount)
        assertEquals("", index.line(0))
        assertEquals("", index.line(5))
    }

    @Test
    fun `没有结尾换行时行数等于换行数加一`() {
        val index = LineIndex("a\nbb\nccc")
        assertEquals(3, index.lineCount)
        assertEquals("a", index.line(0))
        assertEquals("bb", index.line(1))
        assertEquals("ccc", index.line(2))
    }

    @Test
    fun `结尾有换行时多出一个空行`() {
        val index = LineIndex("a\nbb\n")
        assertEquals(3, index.lineCount)
        assertEquals("a", index.line(0))
        assertEquals("bb", index.line(1))
        assertEquals("", index.line(2))
    }

    @Test
    fun `CRLF 行尾的回车不会显示出来`() {
        val index = LineIndex("第一行\r\n第二行\r\n")
        assertEquals(3, index.lineCount)
        assertEquals("第一行", index.line(0))
        assertEquals("第二行", index.line(1))
    }

    @Test
    fun `超长单行被截断并加省略号`() {
        val long = "x".repeat(LineIndex.MAX_RENDER_LINE_CHARS + 500)
        val index = LineIndex(long)
        val rendered = index.line(0)
        assertEquals(LineIndex.MAX_RENDER_LINE_CHARS + 2, rendered.length) // 尾部 " …"
        assertEquals("…", rendered.last().toString())
    }

    @Test
    fun `偏移换算行号`() {
        val index = LineIndex("a\nbb\nccc")
        assertEquals(0, index.lineOf(0))
        assertEquals(0, index.lineOf(1))
        assertEquals(1, index.lineOf(2))
        assertEquals(1, index.lineOf(4))
        assertEquals(2, index.lineOf(5))
        assertEquals(2, index.lineOf(100))
        assertEquals(0, index.lineOf(-3))
    }

    @Test
    fun `两万行文本建索引后行数正确`() {
        val text = (1..20_000).joinToString("\n") { "第${it}行" }
        val index = LineIndex(text)
        assertEquals(20_000, index.lineCount)
        assertEquals("第1行", index.line(0))
        assertEquals("第20000行", index.line(19_999))
    }

    @Test
    fun `大文件判定阈值`() {
        val big = EditorUiState(text = "x".repeat(EditorFileGateway.LARGE_TEXT_CHARS + 1))
        val small = EditorUiState(text = "x".repeat(EditorFileGateway.LARGE_TEXT_CHARS))
        assertEquals(true, big.isLargeText)
        assertEquals(false, small.isLargeText)
    }
}
