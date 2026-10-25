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
 * 「真 CLI」的两个关键点：
 * - 会话内 `su` 切换（[switchTo]）复用同一个缓冲区，屏幕上的历史不会丢；
 * - 每条命令后追加一个哨兵 `printf`，把 `$PWD` 回传，用于渲染 `:/path $` 提示符。
 *
 * 生命周期：构造即启动；[close] 关闭 stdin、销毁进程并取消读取协程。
 * 输出写入 [TerminalOutputBuffer]（5000 行 / 1 MB），并通过 [lines] 暴露快照。
 */
class TerminalSession(
    initialType: TerminalSessionType,
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

    private val _type = MutableStateFlow(initialType)
    val type: StateFlow<TerminalSessionType> = _type.asStateFlow()

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _status = MutableStateFlow(TerminalStatus.RUNNING)
    val status: StateFlow<TerminalStatus> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 当前工作目录（由哨兵回传）；还没拿到时为 null。 */
    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd.asStateFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    /** 每次启动/切换进程自增，旧进程的等待协程据此放弃打印「会话已结束」。 */
    @Volatile
    private var generation: Int = 0

    init {
        startProcess()
    }

    /** 执行一条命令：自动补换行并写入 stdin，同时记入历史。 */
    fun write(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        remember(trimmed)
        dispatch(trimmed)
    }

    /** 只记历史不执行（`su` / `sudo` 由 ViewModel 接管）。 */
    fun remember(command: String) {
        val trimmed = command.trim()
        if (trimmed.isNotEmpty()) history.record(trimmed)
    }

    /** 把「提示符 + 命令」回显到输出，模拟真终端的行内输入。 */
    fun echo(prompt: String, command: String) {
        val prefix = "$prompt "
        publish(
            listOf(
                TerminalLine(
                    text = prefix + command,
                    spans = listOf(
                        AnsiSpan(prefix, TerminalColor.BRIGHT_GREEN),
                        AnsiSpan(command, TerminalColor.DEFAULT, bold = true),
                    ),
                ),
            ),
        )
    }

    /**
     * 一次性以 root 执行（`sudo <cmd>`）：起一个 `su -c` 进程，输出并回主屏。
     * 不改变当前会话身份。
     */
    fun runOnce(command: String) {
        scope.launch {
            val started = runCatching { ProcessBuilder("su", "-c", command).start() }.getOrNull()
            if (started == null) {
                printNotice("[没有可用的 su]")
                return@launch
            }
            val outJob = launch { pump(started.inputStream, stdoutParser) }
            val errJob = launch { pump(started.errorStream, stderrParser) }
            val exitCode = runCatching { started.waitFor() }.getOrDefault(-1)
            outJob.join()
            errJob.join()
            publish(synchronized(parserLock) { stdoutParser.finish() })
            publish(synchronized(parserLock) { stderrParser.finish() })
            if (exitCode != 0) printNotice("[退出码 $exitCode]")
        }
    }

    /** 切换 sh / root 会话，复用同一个输出缓冲区（历史不丢）。 */
    fun switchTo(target: TerminalSessionType) {
        if (target == _type.value && _status.value == TerminalStatus.RUNNING) return
        _type.value = target
        restart()
    }

    /** 菜单「重置」：同一身份重启 shell，输出缓冲保留。 */
    fun reset() {
        restart()
    }

    /** 发送原始按键（Esc / Tab 等），不补换行。 */
    fun sendKey(raw: String) {
        scope.launch {
            writeMutex.withLock {
                val target = writer ?: return@withLock
                runCatching {
                    target.write(raw)
                    target.flush()
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
        generation++
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

    private fun dispatch(command: String) {
        scope.launch {
            writeMutex.withLock {
                val target = writer ?: run {
                    _error.value = "会话未启动，无法发送命令"
                    return@withLock
                }
                try {
                    target.write(command)
                    target.write("\n")
                    target.write(PWD_SENTINEL)
                    target.write("\n")
                    target.flush()
                } catch (failure: IOException) {
                    _error.value = "写入命令失败：${failure.message ?: "管道已关闭"}"
                    _status.value = TerminalStatus.ERROR
                }
            }
        }
    }

    private fun restart() {
        // 先让旧进程的等待协程失效，再关掉它
        generation++
        runCatching { writer?.close() }
        writer = null
        val current = process
        process = null
        if (current != null) {
            current.destroy()
            if (current.isAlive) current.destroyForcibly()
        }
        _status.value = TerminalStatus.RUNNING
        _error.value = null
        startProcess()
    }

    private fun startProcess() {
        val gen = ++generation
        val type = _type.value
        scope.launch {
            val started = try {
                ProcessBuilder(type.binary).start()
            } catch (failure: IOException) {
                if (gen == generation) {
                    _error.value = "无法启动 ${type.binary}：${failure.message ?: "命令不存在"}"
                    _status.value = TerminalStatus.ERROR
                    printNotice("[${type.label} 会话启动失败]")
                }
                return@launch
            } catch (failure: SecurityException) {
                if (gen == generation) {
                    _error.value = "没有权限启动 ${type.binary}：${failure.message ?: "被系统拒绝"}"
                    _status.value = TerminalStatus.ERROR
                    printNotice("[${type.label} 会话启动失败]")
                }
                return@launch
            }

            if (gen != generation) {
                started.destroy()
                return@launch
            }
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
            sendSentinel()

            val stdoutJob = launch { pump(started.inputStream, stdoutParser) }
            val stderrJob = launch { pump(started.errorStream, stderrParser) }

            val exitCode = runCatching { started.waitFor() }.getOrDefault(-1)
            stdoutJob.join()
            stderrJob.join()

            publish(synchronized(parserLock) { stdoutParser.finish() })
            publish(synchronized(parserLock) { stderrParser.finish() })

            // 已经被 switchTo / close 取代：不打印任何收尾信息
            if (gen != generation) return@launch
            if (_error.value == null && exitCode != 0) {
                _error.value = "${type.label} 会话结束，退出码 $exitCode"
            }
            if (_status.value != TerminalStatus.ERROR) {
                _status.value = TerminalStatus.EXITED
            }
        }
    }

    /** 会话启动后先切到共享存储根目录（与 MT 一致），再回报一次 $PWD。 */
    private fun sendSentinel() {
        scope.launch {
            writeMutex.withLock {
                val target = writer ?: return@withLock
                runCatching {
                    target.write(CD_HOME)
                    target.write("\n")
                    target.write(PWD_SENTINEL)
                    target.write("\n")
                    target.flush()
                }
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

    /**
     * 写入输出缓冲；顺带把哨兵行（`VVF_PWD_9F3A:/path`）拦下来更新提示符。
     * ANSI 解析器会丢控制字符，所以哨兵只剩纯文本前缀。
     */
    private fun publish(newLines: List<TerminalLine>) {
        if (newLines.isEmpty()) return
        val visible = ArrayList<TerminalLine>(newLines.size)
        for (line in newLines) {
            if (line.text.startsWith(PWD_MARKER)) {
                val path = line.text.removePrefix(PWD_MARKER).trim()
                if (path.isNotEmpty()) _cwd.value = path
                continue
            }
            visible += line
        }
        if (visible.isEmpty()) return
        synchronized(outputLock) {
            buffer.appendAll(visible)
            _lines.value = buffer.snapshot()
        }
    }

    private companion object {
        const val READ_BUFFER_CHARS = 4 * 1024

        /** 输出里的标记：ANSI 解析器会剥掉 0x01，只剩这段文字。 */
        const val PWD_MARKER = "VVF_PWD_9F3A:"

        /** 启动时进入共享存储根目录（MT 的终端也停在这里）。 */
        const val CD_HOME = "cd /storage/emulated/0 2>/dev/null"

        /** 每条命令后执行的哨兵，把 $PWD 回传给界面。 */
        const val PWD_SENTINEL =
            "printf '\u0001" + PWD_MARKER + "%s\u0001\\n' \"\$PWD\""
    }
}
