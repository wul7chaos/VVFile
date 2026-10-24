package com.vvfile.app.feature.terminal

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 终端会话类型。非 root 会话只是应用自身 uid 的 sh，不构成提权。 */
enum class TerminalSessionType(val binary: String, val label: String) {
    SH("sh", "sh"),
    ROOT("su", "root"),
}

/** 会话状态。 */
enum class TerminalStatus { RUNNING, EXITED, ERROR }

/**
 * 长驻 shell 会话：`sh` 或 `su` 各起一个进程，stdin/stdout/stderr 三路流。
 *
 * 产品红线：特殊权限只走 root。root 会话固定使用 `su` 二进制，
 * **不接入 Shizuku、ADB shell、无障碍**等任何提权通道。
 *
 * 无 PTY 的折中（与 docs/02 §6 一致）：命令逐行写入 stdin，输出按行回调；
 * 不模拟全屏交互程序（vim/top 等不可用）。
 *
 * 生命周期：构造即启动；[close] 关闭 stdin、销毁进程并取消读取协程。
 * 输出写入 [TerminalOutputBuffer]（5000 行 / 1 MB），并通过 [lines] 暴露快照。
 */
class TerminalSession(
    val type: TerminalSessionType,
    maxLines: Int = TerminalOutputBuffer.DEFAULT_MAX_LINES,
    maxBytes: Int = TerminalOutputBuffer.DEFAULT_MAX_BYTES,
    historyCapacity: Int = CommandHistory.DEFAULT_CAPACITY,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val parserLock = Any()
    private val outputLock = Any()
    private val stdoutParser = AnsiParser()
    private val stderrParser = AnsiParser()
    private val buffer = TerminalOutputBuffer(maxLines, maxBytes)
    private val history = CommandHistory(historyCapacity)
    private val writeMutex = Mutex()

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow(TerminalStatus.RUNNING)
    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    init {
        startProcess()
    }

    /**
     * 执行一条命令：自动补换行并写入 stdin。
     * 空白命令忽略；命令会记入历史（最多 200 条）。
     */
    fun write(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        history.record(trimmed)

        scope.launch {
            writeMutex.withLock {
                val target = writer ?: run {
                    _error.value = "会话未启动，无法发送命令"
                    return@withLock
                }
                try {
                    target.write(trimmed)
                    target.write("\n")
                    target.flush()
                } catch (failure: IOException) {
                    _error.value = "写入命令失败：${failure.message ?: "管道已关闭"}"
                    _status.value = TerminalStatus.ERROR
                }
            }
        }
    }

    /** 清空输出缓冲（不影响正在运行的进程）。 */
    fun clear() {
        synchronized(outputLock) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    /** 追加一条系统提示行（不着色、暗色显示）。 */
    fun printNotice(text: String) {
        publish(
            listOf(
                TerminalLine(
                    text = text,
                    spans = listOf(AnsiSpan(text, TerminalColor.BRIGHT_BLACK)),
                ),
            ),
        )
    }

    fun historyUpIndex(current: Int): Int = history.upIndex(current)

    fun historyDownIndex(current: Int): Int = history.downIndex(current)

    fun historyAt(index: Int): String? = history.get(index)

    val historySize: Int get() = history.size

    /** 关闭会话：先关 stdin 让 shell 正常退出，再销毁进程并取消协程。 */
    fun close() {
        runCatching { writer?.close() }
        writer = null

        val current = process
        process = null
        if (current != null) {
            current.destroy()
            if (current.isAlive) current.destroyForcibly()
        }
        scope.cancel()
        _status.value = TerminalStatus.EXITED
    }

    private fun startProcess() {
        scope.launch {
            val started = try {
                ProcessBuilder(type.binary).start()
            } catch (failure: IOException) {
                _error.value = "无法启动 ${type.binary}：${failure.message ?: "命令不存在"}"
                _status.value = TerminalStatus.ERROR
                printNotice("[${type.label} 会话启动失败]")
                return@launch
            } catch (failure: SecurityException) {
                _error.value = "没有权限启动 ${type.binary}：${failure.message ?: "被系统拒绝"}"
                _status.value = TerminalStatus.ERROR
                printNotice("[${type.label} 会话启动失败]")
                return@launch
            }

            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))

            val stdoutJob = launch { pump(started.inputStream, stdoutParser) }
            val stderrJob = launch { pump(started.errorStream, stderrParser) }

            val exitCode = started.waitFor()
            stdoutJob.join()
            stderrJob.join()

            publish(synchronized(parserLock) { stdoutParser.finish() })
            publish(synchronized(parserLock) { stderrParser.finish() })

            if (_error.value == null && exitCode != 0) {
                _error.value = "${type.label} 会话结束，退出码 $exitCode"
            }
            if (_status.value != TerminalStatus.ERROR) {
                _status.value = TerminalStatus.EXITED
                printNotice("[会话已结束，退出码 $exitCode]")
            }
        }
    }

    private fun pump(stream: InputStream, parser: AnsiParser) {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val chunk = CharArray(READ_BUFFER_CHARS)
        try {
            while (true) {
                val read = reader.read(chunk)
                if (read < 0) break
                val parsed = synchronized(parserLock) { parser.feed(String(chunk, 0, read)) }
                publish(parsed)
            }
        } catch (_: IOException) {
            // 进程被关闭/杀掉时流异常结束，属于预期路径。
        }
    }

    private fun publish(newLines: List<TerminalLine>) {
        if (newLines.isEmpty()) return
        synchronized(outputLock) {
            buffer.appendAll(newLines)
            _lines.value = buffer.snapshot()
        }
    }

    private companion object {
        const val READ_BUFFER_CHARS = 4 * 1024
    }
}
