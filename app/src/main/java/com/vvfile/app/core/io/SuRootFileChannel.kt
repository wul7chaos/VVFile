package com.vvfile.app.core.io

import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellExecutor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 `su -c` 读文件的实现。
 *
 * 与 [ShellExecutor] 的分工：命令输出要的是文本（`ls`/`stat`），走 ShellExecutor；
 * 文件内容是**字节**，1 MB 文本上限和 UTF-8 解码会把二进制文件读坏，
 * 所以 [readPrefix] 自己起 `cat` 直接读字节流。
 */
class SuRootFileChannel(
    private val runner: CommandRunner = ShellExecutor(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RootFileChannel {

    override suspend fun stat(path: String): RootMeta? = withContext(io) {
        val result = runner.exec(
            command = "stat -c '%F|%s|%Y' ${ShellExecutor.shellQuote(path)}",
            asRoot = true,
            timeoutMs = STAT_TIMEOUT_MS,
        )
        if (result.exitCode != 0) return@withContext null
        val text = result.stdout.trim()
        val fields = text.split('|')
        if (fields.size < 2) return@withContext null
        val size = fields[fields.size - 2].trim().toLongOrNull() ?: return@withContext null
        val modifiedAt = fields.last().trim().toLongOrNull() ?: 0L
        RootMeta(
            isDirectory = text.substringBefore('|').contains("directory", ignoreCase = true),
            size = size.coerceAtLeast(0L),
            modifiedAt = modifiedAt,
        )
    }

    override suspend fun readPrefix(path: String, limit: Long): ByteArray = withContext(io) {
        if (limit <= 0L) return@withContext ByteArray(0)
        val process = try {
            ProcessBuilder(SU_BINARY, "-c", "cat -- ${ShellExecutor.shellQuote(path)}").start()
        } catch (error: IOException) {
            throw IOException("无法启动 su：${error.message ?: "未知原因"}", error)
        }
        // cat 有参数时不会读 stdin，但显式关掉更保险：万一 su 不认参数，也不会卡在等输入
        runCatching { process.outputStream.close() }

        val sink = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = limit
        var overflow = false
        try {
            process.inputStream.use { input ->
                while (remaining > 0L) {
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
                // 刚好读满上限就认为是截断：后面还有内容，别继续拉
                overflow = remaining == 0L
            }
        } catch (io2: IOException) {
            // 读一半被掐断（进程被杀/管道关闭）时保留已读到的部分
            if (sink.size() == 0) throw io2
        } finally {
            if (overflow && process.isAlive) process.destroy()
        }

        val finished = process.waitFor(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IOException("读取超时（${READ_TIMEOUT_MS / 1000}s）")
        }
        val bytes = sink.toByteArray()
        if (bytes.isEmpty() && process.exitValue() != 0) {
            val detail = runCatching {
                process.errorStream.bufferedReader().use { it.readText().take(512).trim() }
            }.getOrDefault("")
            throw IOException(detail.ifBlank { "读取失败（cat 退出码 ${process.exitValue()}）" })
        }
        bytes
    }

    override suspend fun writeAll(path: String, bytes: ByteArray): Unit = withContext(io) {
        // 借道缓存文件：su 的 shell 没有 stdin 通道，先落到应用自己的临时文件再让 root 覆盖过去。
        // 用 `cat > 目标` 而不是 `cp`，目标已存在时保留原来的权限位与属主。
        val temp = File.createTempFile("vvfile-root-", ".tmp", cacheDir())
        try {
            temp.writeBytes(bytes)
            val result = runner.exec(
                command = "cat -- ${ShellExecutor.shellQuote(temp.absolutePath)} > " +
                    ShellExecutor.shellQuote(path),
                asRoot = true,
                timeoutMs = WRITE_TIMEOUT_MS,
            )
            if (result.exitCode != 0) {
                val detail = result.stderr.trim().ifBlank { "退出码 ${result.exitCode}" }
                throw IOException("写入失败：$detail")
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun cacheDir(): File {
        val tmp = System.getProperty("java.io.tmpdir")
        val dir = if (tmp.isNullOrBlank()) File("/data/local/tmp") else File(tmp)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override suspend fun copyTo(path: String, target: File): Boolean = withContext(io) {
        target.parentFile?.let { if (!it.exists() && !it.mkdirs()) return@withContext false }
        // cp 出来的属主是 root，必须显式放开读权限，否则应用自己反而打不开缓存副本
        val result = runner.exec(
            command = "cp -f -- ${ShellExecutor.shellQuote(path)} " +
                "${ShellExecutor.shellQuote(target.absolutePath)} && " +
                "chmod 0664 ${ShellExecutor.shellQuote(target.absolutePath)}",
            asRoot = true,
            timeoutMs = WRITE_TIMEOUT_MS,
        )
        result.exitCode == 0 && target.exists()
    }

    private companion object {
        private const val SU_BINARY = "su"
        private const val BUFFER_BYTES = 64 * 1024
        private const val STAT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 30_000L
    }
}
