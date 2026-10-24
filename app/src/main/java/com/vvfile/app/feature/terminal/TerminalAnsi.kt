package com.vvfile.app.feature.terminal

/** 终端基础前景色（SGR 30-37 / 90-97）。 */
enum class TerminalColor {
    DEFAULT,
    BLACK,
    RED,
    GREEN,
    YELLOW,
    BLUE,
    MAGENTA,
    CYAN,
    WHITE,
    BRIGHT_BLACK,
    BRIGHT_RED,
    BRIGHT_GREEN,
    BRIGHT_YELLOW,
    BRIGHT_BLUE,
    BRIGHT_MAGENTA,
    BRIGHT_CYAN,
    BRIGHT_WHITE,
}

/** 一段同样式文本。 */
data class AnsiSpan(
    val text: String,
    val color: TerminalColor = TerminalColor.DEFAULT,
    val bold: Boolean = false,
)

/**
 * 终端输出的一行：纯文本 + 样式分段。
 * 纯文本用于选择/复制，分段用于 Compose 着色。
 */
data class TerminalLine(
    val text: String,
    val spans: List<AnsiSpan> = emptyList(),
)

/**
 * 增量 ANSI 解析器：把 shell 原始输出转成 [TerminalLine]。
 *
 * 支持范围（够用即可）：
 * - SGR：`0` 重置、`1` 加粗、`22` 取消加粗、`30-37` 基本色、`90-97` 亮色、`39` 默认色；
 * - `38/48;5;n` 与 `38/48;2;r;g;b` 扩展色整体跳过（不误判为基色）；
 * - 其余 CSI（如 `ESC[2J`）、OSC（窗口标题）、双字符转义一律剥离。
 *
 * 解析器保留跨 chunk 的状态：一个转义序列或半行被拆到两次 read 中也能正确处理。
 * 纯 Kotlin 逻辑，便于单测。
 */
internal class AnsiParser {

    private enum class State { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    private var state = State.TEXT
    private val csi = StringBuilder()
    private val osc = StringBuilder()
    private val line = StringBuilder()
    private val segments = mutableListOf<AnsiSpan>()
    private val pendingText = StringBuilder()
    private var color = TerminalColor.DEFAULT
    private var bold = false

    /** 喂入一段输出，返回其中已经完整结束的行。 */
    fun feed(chunk: String): List<TerminalLine> = parse(chunk, finish = false)

    /** 流结束时调用：把没有换行结尾的最后一行也吐出来。 */
    fun finish(): List<TerminalLine> = parse("", finish = true)

    private fun parse(chunk: String, finish: Boolean): List<TerminalLine> {
        val emitted = mutableListOf<TerminalLine>()
        for (char in chunk) {
            handle(char, emitted)
        }
        if (finish) {
            state = State.TEXT
            csi.clear()
            osc.clear()
            if (line.isNotEmpty()) flushLine(emitted)
        }
        return emitted
    }

    private fun handle(char: Char, emitted: MutableList<TerminalLine>) {
        when (state) {
            State.TEXT -> when {
                char == ESCAPE -> state = State.ESCAPE
                char == '\n' -> flushLine(emitted)
                char == '\r' -> Unit
                char == '\b' -> backspace()
                char == '\t' -> append(char, emitted)
                char.code < 0x20 || char.code == 0x7F -> Unit
                else -> append(char, emitted)
            }

            State.ESCAPE -> when (char) {
                '[' -> {
                    state = State.CSI
                    csi.clear()
                }

                ']' -> {
                    state = State.OSC
                    osc.clear()
                }

                else -> state = State.TEXT
            }

            State.CSI -> {
                csi.append(char)
                if (char in '\u0040'..'\u007E') {
                    if (char == 'm') applySgr(csi.substring(0, csi.length - 1))
                    csi.clear()
                    state = State.TEXT
                } else if (csi.length > MAX_SEQUENCE_CHARS) {
                    // 异常/超长序列，直接丢弃，避免污染后续文本。
                    csi.clear()
                    state = State.TEXT
                }
            }

            State.OSC -> when (char) {
                '\u0007' -> state = State.TEXT
                ESCAPE -> state = State.OSC_ESCAPE
                else -> {
                    osc.append(char)
                    if (osc.length > MAX_SEQUENCE_CHARS) {
                        osc.clear()
                        state = State.TEXT
                    }
                }
            }

            State.OSC_ESCAPE -> {
                if (char == '\\') {
                    state = State.TEXT
                } else {
                    osc.append(char)
                    state = State.OSC
                }
            }
        }
    }

    private fun append(char: Char, emitted: MutableList<TerminalLine>) {
        // 防御超长单行（例如 cat 二进制文件），超出后自动换行。
        if (line.length >= MAX_LINE_CHARS) flushLine(emitted)
        line.append(char)
        pendingText.append(char)
    }

    private fun backspace() {
        if (line.isEmpty()) return
        line.deleteCharAt(line.length - 1)
        if (pendingText.isNotEmpty()) pendingText.deleteCharAt(pendingText.length - 1)
    }

    private fun flushLine(emitted: MutableList<TerminalLine>) {
        flushSpan()
        emitted += TerminalLine(text = line.toString(), spans = segments.toList())
        line.clear()
        segments.clear()
    }

    private fun flushSpan() {
        if (pendingText.isEmpty()) return
        segments += AnsiSpan(text = pendingText.toString(), color = color, bold = bold)
        pendingText.clear()
    }

    private fun applySgr(rawParams: String) {
        // 样式只影响其后的文本：先把已积累的片段按旧样式封口。
        flushSpan()
        val parts = rawParams.split(';')
        var index = 0
        while (index < parts.size) {
            val code = parts[index].toIntOrNull() ?: 0
            when {
                code == 0 -> resetStyle()
                code == 1 -> bold = true
                code == 22 -> bold = false
                code == 39 -> color = TerminalColor.DEFAULT
                code in 30..37 -> color = BASIC_COLORS[code - 30]
                code in 90..97 -> color = BRIGHT_COLORS[code - 90]
                code == 38 || code == 48 -> {
                    // 扩展色：38;5;n / 38;2;r;g;b，整体跳过，避免把 n 当成基色。
                    index += when (parts.getOrNull(index + 1)?.toIntOrNull()) {
                        5 -> 2
                        2 -> 4
                        else -> 1
                    }
                }

                else -> Unit
            }
            index++
        }
    }

    private fun resetStyle() {
        color = TerminalColor.DEFAULT
        bold = false
    }

    private companion object {
        const val ESCAPE = '\u001B'
        const val MAX_SEQUENCE_CHARS = 64
        const val MAX_LINE_CHARS = 8 * 1024

        val BASIC_COLORS = listOf(
            TerminalColor.BLACK,
            TerminalColor.RED,
            TerminalColor.GREEN,
            TerminalColor.YELLOW,
            TerminalColor.BLUE,
            TerminalColor.MAGENTA,
            TerminalColor.CYAN,
            TerminalColor.WHITE,
        )

        val BRIGHT_COLORS = listOf(
            TerminalColor.BRIGHT_BLACK,
            TerminalColor.BRIGHT_RED,
            TerminalColor.BRIGHT_GREEN,
            TerminalColor.BRIGHT_YELLOW,
            TerminalColor.BRIGHT_BLUE,
            TerminalColor.BRIGHT_MAGENTA,
            TerminalColor.BRIGHT_CYAN,
            TerminalColor.BRIGHT_WHITE,
        )
    }
}
