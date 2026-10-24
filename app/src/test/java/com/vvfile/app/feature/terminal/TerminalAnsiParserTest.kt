package com.vvfile.app.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = "\u001B"

class TerminalAnsiParserTest {

    private fun parse(raw: String): List<TerminalLine> {
        val parser = AnsiParser()
        return parser.feed(raw) + parser.finish()
    }

    @Test
    fun `plain text becomes one line`() {
        val lines = parse("hello world\n")

        assertEquals(1, lines.size)
        assertEquals("hello world", lines[0].text)
        assertEquals(listOf(AnsiSpan("hello world", TerminalColor.DEFAULT)), lines[0].spans)
    }

    @Test
    fun `newlines split lines and crlf is normalized`() {
        val lines = parse("a\r\nb\nc")

        assertEquals(listOf("a", "b", "c"), lines.map { it.text })
    }

    @Test
    fun `trailing incomplete line is flushed by finish`() {
        val parser = AnsiParser()

        assertTrue(parser.feed("prompt$ ").isEmpty())
        assertEquals(listOf("prompt$ "), parser.finish().map { it.text })
    }

    @Test
    fun `basic sgr colors and reset`() {
        val line = parse("${ESC}[31mred${ESC}[0m plain\n").single()

        assertEquals("red plain", line.text)
        assertEquals(2, line.spans.size)
        assertEquals(AnsiSpan("red", TerminalColor.RED), line.spans[0])
        assertEquals(AnsiSpan(" plain", TerminalColor.DEFAULT), line.spans[1])
    }

    @Test
    fun `bold and color combine`() {
        val line = parse("${ESC}[1;31mX${ESC}[m\n").single()

        assertEquals(AnsiSpan("X", TerminalColor.RED, bold = true), line.spans.single())
    }

    @Test
    fun `bright colors map to bright palette`() {
        val line = parse("${ESC}[91mA${ESC}[0m${ESC}[97mB${ESC}[0m\n").single()

        assertEquals(
            listOf(
                AnsiSpan("A", TerminalColor.BRIGHT_RED),
                AnsiSpan("B", TerminalColor.BRIGHT_WHITE),
            ),
            line.spans,
        )
    }

    @Test
    fun `default foreground resets only the color`() {
        val line = parse("${ESC}[1;32mX${ESC}[39mY\n").single()

        assertEquals(AnsiSpan("X", TerminalColor.GREEN, bold = true), line.spans[0])
        assertEquals(AnsiSpan("Y", TerminalColor.DEFAULT, bold = true), line.spans[1])
    }

    @Test
    fun `extended 256 color is skipped without being treated as basic color`() {
        val line = parse("${ESC}[38;5;196mX\n").single()

        assertEquals(AnsiSpan("X", TerminalColor.DEFAULT), line.spans.single())
    }

    @Test
    fun `non sgr escapes are stripped`() {
        val line = parse("${ESC}[2J${ESC}[H${ESC}]0;window title\u0007ok\n").single()

        assertEquals("ok", line.text)
    }

    @Test
    fun `escape sequence split across feeds is parsed`() {
        val parser = AnsiParser()

        assertTrue(parser.feed("${ESC}[3").isEmpty())
        val line = parser.feed("1mred\n").single()

        assertEquals(AnsiSpan("red", TerminalColor.RED), line.spans.single())
    }

    @Test
    fun `backspace removes the previous character`() {
        assertEquals(listOf("ac"), parse("ab\bc\n").map { it.text })
    }

    @Test
    fun `empty input produces no lines`() {
        assertTrue(parse("").isEmpty())
    }
}
