package com.vvfile.app.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLineOpsTest {

    private val sample = "alpha\nbeta\ngamma"

    @Test
    fun `行范围不含换行符`() {
        assertEquals("beta", EditorLineOps.lineText(sample, 7))
        assertEquals(6, EditorLineOps.lineRange(sample, 7).first)
        assertEquals(9, EditorLineOps.lineRange(sample, 7).last)
    }

    @Test
    fun `光标停在行首时取当前行`() {
        assertEquals("beta", EditorLineOps.lineText(sample, 6))
    }

    @Test
    fun `删除行连同行尾换行`() {
        val (text, cursor) = EditorLineOps.deleteLine(sample, 7)
        assertEquals("alpha\ngamma", text)
        assertEquals(6, cursor)
    }

    @Test
    fun `删除最后一行`() {
        val (text, _) = EditorLineOps.deleteLine(sample, 12)
        assertEquals("alpha\nbeta\n", text)
    }

    @Test
    fun `清空行保留行结构`() {
        val (text, cursor) = EditorLineOps.clearLine(sample, 7)
        assertEquals("alpha\n\ngamma", text)
        assertEquals(6, cursor)
    }

    @Test
    fun `重复行插到下一行`() {
        val (text, _) = EditorLineOps.duplicateLine(sample, 7)
        assertEquals("alpha\nbeta\nbeta\ngamma", text)
    }

    @Test
    fun `重复最后一行的补换行`() {
        val (text, _) = EditorLineOps.duplicateLine("alpha\nbeta", 8)
        assertEquals("alpha\nbeta\nbeta", text)
    }

    @Test
    fun `替换行保留行结构`() {
        val (text, _) = EditorLineOps.replaceLine(sample, 7, "BETA")
        assertEquals("alpha\nBETA\ngamma", text)
    }

    @Test
    fun `大小写按行或选区`() {
        val (line, _) = EditorLineOps.toCase(sample, 7, 7, upper = true)
        assertEquals("alpha\nBETA\ngamma", line)
        val (part, _) = EditorLineOps.toCase(sample, 0, 5, upper = true)
        assertEquals("ALPHA\nbeta\ngamma", part)
    }

    @Test
    fun `缩进按覆盖行增减`() {
        val (increased, _) = EditorLineOps.indent(sample, 7, 7, increase = true, spaces = 2)
        assertEquals("alpha\n  beta\ngamma", increased)
        val (decreased, _) = EditorLineOps.indent(increased, 9, 9, increase = false, spaces = 2)
        assertEquals("alpha\nbeta\ngamma", decreased)
    }

    @Test
    fun `统计行字符词与字节`() {
        val stats = EditorLineOps.stats("你好 world\nabc")
        assertEquals(2, stats.lines)
        assertEquals(12, stats.chars)
        assertEquals(3, stats.words)
        assertTrue(stats.bytesUtf8 > stats.chars)
    }
}
