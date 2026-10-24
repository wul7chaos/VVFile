package com.vvfile.app.core.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 统一的命令执行器：非 root 走 `sh -c`，root 走 `su -c`。
 *
 * 产品红线：特殊权限只走 root。本类**不接入 Shizuku、ADB shell、无障碍**等
 * 任何提权通道；非 root 命令只是应用自身 uid 的 `sh`，不构成提权。
 *
 * 实现要点：
 * - 固定 [ProcessBuilder] 数组形式，绝不使用 `Runtime.exec(String)` 或手工拆分命令；
 * - stdout/stderr 并发读取，避免子进程写满管道导致死锁；
 * - stdout + stderr 合计上限 [maxOutputBytes]（默认 1 MB），超出部分丢弃并标记
 *   [ShellResult.truncated]（继续读空管道，防止子进程阻塞）；
 * - 超时先 `destroy()`，宽限后仍存活则 `destroyForcibly()`，返回
 *   [ShellResult.timedOut] = true 且 exitCode = -1；
 * - 路径等动态参数拼接前必须经过 [shellQuote]。
 */
class ShellExecutor(
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) : CommandRunner {
    init {
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
    }

    /**
     * 执行一条命令。
     *
     * @param command 交给 `sh -c` / `su -c` 的完整命令串（不要把未转义的路径直接拼入）。
     * @param asRoot true 使用 `su -c`，false 使用 `sh -c`。
     * @param timeoutMs 超时毫秒数，非正值按 1 ms 处理。
     */
    override suspend fun exec(
        command: String,
        asRoot: Boolean,
        timeoutMs: Long,
    ): ShellResult = withContext(Dispatchers.IO) {
        val argv = if (asRoot) {
            arrayOf(SU_BINARY, "-c", command)
        } else {
            arrayOf(SH_BINARY, "-c", command)
        }

        val process = try {
            ProcessBuilder(*argv).start()
        } catch (e: IOException) {
            // 典型场景：设备没有 su（未 root / MuMu 未开启 root），进程根本起不来。
            return@withContext ShellResult(
                command = command,
                asRoot = asRoot,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "无法启动 ${argv[0]}",
                timedOut = false,
                truncated = false,
                started = false,
            )
        } catch (e: SecurityException) {
            return@withContext ShellResult(
                command = command,
                asRoot = asRoot,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "没有权限启动 ${argv[0]}",
                timedOut = false,
                truncated = false,
                started = false,
            )
        }

        val budget = OutputBudget(maxOutputBytes)
        try {
            coroutineScope {
                val stdoutDeferred = async(Dispatchers.IO) { drain(process.inputStream, budget) }
                val stderrDeferred = async(Dispatchers.IO) { drain(process.errorStream, budget) }

                val finished = process.waitFor(
                    timeoutMs.coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS,
                )
                if (!finished) {
                    // 先礼后兵：destroy 给 su/子进程一个退出机会，宽限期后强杀。
                    process.destroy()
                    if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
                    }
                }

                // 进程已结束（或已被杀），管道随之关闭，两个读取协程能够正常收敛。
                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                val exitCode = if (finished) process.exitValue() else -1

                ShellResult(
                    command = command,
                    asRoot = asRoot,
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    timedOut = !finished,
                    truncated = budget.truncated,
                    started = true,
                )
            }
        } finally {
            // 协程被取消等异常路径下兜底回收，避免 su 进程泄漏。
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun drain(stream: InputStream, budget: OutputBudget): String {
        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val granted = budget.take(read)
                if (granted > 0) {
                    sink.write(buffer, 0, granted)
                }
                // granted < read 时继续循环把剩余输出读掉并丢弃，防止子进程写管道阻塞。
            }
        } catch (_: IOException) {
            // 进程被超时杀掉时流会异常关闭，属于预期路径。
        }
        return String(sink.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 1 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L

        private const val KILL_GRACE_MS = 500L
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val SH_BINARY = "sh"
        private const val SU_BINARY = "su"

        /**
         * 用单引号包裹任意字符串，供拼接进 `sh -c` / `su -c` 命令。
         *
         * 单引号内除了 `'` 本身没有特殊字符，因此把 `'` 替换为 `'\''`
         * （关闭引号 + 转义单引号 + 重新开引号）即可安全承载空格、换行、
         * `$`、反引号、`;` 等。空串返回 `''`。
         */
        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/**
 * stdout/stderr 共享的字节预算：两个流合计最多 [limitBytes] 字节。
 * 超出时返回剩余额度并置 [truncated]。
 */
internal class OutputBudget(private val limitBytes: Int) {
    private var remaining: Int = limitBytes

    var truncated: Boolean = false
        private set

    init {
        require(limitBytes > 0) { "limitBytes must be positive" }
    }

    @Synchronized
    fun take(requested: Int): Int {
        if (requested <= 0) return 0
        if (remaining <= 0) {
            truncated = true
            return 0
        }
        if (requested <= remaining) {
            remaining -= requested
            return requested
        }
        val granted = remaining
        remaining = 0
        truncated = true
        return granted
    }
}
