package com.vvfile.app.feature.terminal

/**
 * 终端输出环形缓冲：同时受行数（默认 5000）与字节数（默认 1 MB）约束，
 * 超出后从最旧的行开始丢弃，保证长时间 `logcat` / `find` 不会撑爆内存。
 *
 * 线程安全：会话的 stdout/stderr 读取协程与清屏动作可能并发调用。
 */
internal class TerminalOutputBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val lines = ArrayDeque<TerminalLine>()
    private var byteCount: Int = 0

    /** 因容量限制被丢弃的行数，便于调试/提示。 */
    @Volatile
    var droppedLines: Long = 0L
        private set

    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Synchronized
    fun append(line: TerminalLine) {
        appendAll(listOf(line))
    }

    @Synchronized
    fun appendAll(newLines: List<TerminalLine>) {
        if (newLines.isEmpty()) return
        for (line in newLines) {
            lines.addLast(line)
            byteCount += estimateBytes(line)
        }
        trim()
    }

    @Synchronized
    fun snapshot(): List<TerminalLine> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        byteCount = 0
        droppedLines = 0L
    }

    @Synchronized
    fun size(): Int = lines.size

    private fun trim() {
        // 行数优先，然后按字节数继续淘汰最旧的行。
        while (lines.size > maxLines || (byteCount > maxBytes && lines.isNotEmpty())) {
            val removed = lines.removeFirst()
            byteCount -= estimateBytes(removed)
            droppedLines++
        }
        if (byteCount < 0) byteCount = 0
    }

    private fun estimateBytes(line: TerminalLine): Int =
        line.text.toByteArray(Charsets.UTF_8).size + PER_LINE_OVERHEAD_BYTES

    companion object {
        const val DEFAULT_MAX_LINES = 5_000
        const val DEFAULT_MAX_BYTES = 1 * 1024 * 1024

        private const val PER_LINE_OVERHEAD_BYTES = 24
    }
}
