package com.vvfile.app.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputBufferTest {

    private fun line(text: String) = TerminalLine(text, listOf(AnsiSpan(text)))

    @Test
    fun `keeps only the most recent lines when line limit is exceeded`() {
        val buffer = TerminalOutputBuffer(maxLines = 3, maxBytes = 1 shl 20)

        repeat(5) { index -> buffer.append(line("l$index")) }

        assertEquals(listOf("l2", "l3", "l4"), buffer.snapshot().map { it.text })
        assertEquals(2L, buffer.droppedLines)
    }

    @Test
    fun `byte limit evicts oldest lines`() {
        // 每行 "aaaa" 估算 4 + 24 = 28 字节，60 字节只能容纳两行。
        val buffer = TerminalOutputBuffer(maxLines = 10, maxBytes = 60)

        repeat(3) { buffer.append(line("aaaa")) }

        assertEquals(2, buffer.snapshot().size)
        assertEquals(1L, buffer.droppedLines)
    }

    @Test
    fun `appendAll adds every line at once`() {
        val buffer = TerminalOutputBuffer(maxLines = 10, maxBytes = 1 shl 20)

        buffer.appendAll(listOf(line("a"), line("b")))

        assertEquals(listOf("a", "b"), buffer.snapshot().map { it.text })
    }

    @Test
    fun `empty append does not change the buffer`() {
        val buffer = TerminalOutputBuffer(maxLines = 10, maxBytes = 1 shl 20)

        buffer.appendAll(emptyList())

        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun `clear resets lines and dropped counter`() {
        val buffer = TerminalOutputBuffer(maxLines = 2, maxBytes = 1 shl 20)
        repeat(3) { buffer.append(line("l$it")) }

        buffer.clear()

        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0L, buffer.droppedLines)
        assertEquals(0, buffer.size())
    }

    @Test
    fun `defaults match terminal specification`() {
        assertEquals(5000, TerminalOutputBuffer.DEFAULT_MAX_LINES)
        assertEquals(1024 * 1024, TerminalOutputBuffer.DEFAULT_MAX_BYTES)
    }
}
