package com.vvfile.app.core.transfer

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 往电脑端接收器（pc/VvFileReceiver）发文件。
 *
 * 协议刻意做得很窄：一条请求一个文件，正文就是原始字节，文件名放在
 * `X-VV-Name` 头里（UTF-8 百分号编码）。不用 multipart，两端都不用为
 * 「文件名里带引号/换行/中文」这种边界打补丁，C# 那边读 Content-Length 就够。
 *
 * 只用 `java.net`，不碰 Android API，所以能直接跑 JVM 单测（起个本地 socket 验证协议）。
 */
object PcUploader {

    private const val CHUNK = 64 * 1024
    private const val PING_TIMEOUT_MS = 4_000
    private const val UPLOAD_TIMEOUT_MS = 120_000

    /** 只保留非保留字符，其余按 UTF-8 百分号编码；不用 URLEncoder，避免空格变成 `+`。 */
    fun encodeName(name: String): String {
        val out = StringBuilder(name.length + 16)
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            val keep = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '-' || c == '_' || c == '.' || c == '~'
            if (keep) {
                out.append(c)
            } else {
                out.append('%')
                out.append(HEX[value shr 4])
                out.append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    /** 相对路径：分隔符 `/` 原样保留，每一段各自编码。 */
    fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeName(it) }

    /** 连得上就返回电脑端的接收目录。 */
    suspend fun ping(
        target: PcTarget,
        timeoutMs: Int = PING_TIMEOUT_MS,
    ): PcResult = withContext(Dispatchers.IO) {
        try {
            val connection = open(target, "/ping", "GET", timeoutMs)
            connection.connect()
            val code = connection.responseCode
            val body = readBody(connection, code)
            connection.disconnect()
            if (code in 200..299) {
                val dir = match(JSON_DIR, body)?.let { unescape(it) }
                PcResult(true, dir?.let { "连上了，电脑保存到 $it" } ?: "连上了")
            } else {
                PcResult(false, "电脑端返回 $code")
            }
        } catch (error: Exception) {
            PcResult(false, describe(error, target))
        }
    }

    /**
     * 上传一个文件。
     *
     * [relativePath] 是相对电脑接收目录的路径（文件夹里的文件带上前面的层级，
     * 电脑端据此还原目录结构）；不给就按文件名落。
     * [onProgress] 在 IO 线程上回调；`total` 为 0 表示长度未知（正常都会给）。
     */
    suspend fun upload(
        target: PcTarget,
        file: File,
        name: String = file.name,
        relativePath: String = file.name,
        timeoutMs: Int = UPLOAD_TIMEOUT_MS,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): PcResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) return@withContext PcResult(false, "文件不存在")
        val size = file.length()
        try {
            val connection = open(target, "/upload", "PUT", timeoutMs, size)
            connection.setRequestProperty("X-VV-Name", encodeName(name))
            connection.setRequestProperty("X-VV-Path", encodePath(relativePath))
            connection.setRequestProperty("X-VV-Size", size.toString())
            connection.setRequestProperty("X-VV-Mtime", file.lastModified().toString())
            connection.doOutput = true
            connection.connect()

            try {
                FileInputStream(file).use { input ->
                    ProgressOutputStream(connection.outputStream, size, onProgress).use { output ->
                        input.copyTo(output, CHUNK)
                        output.flush()
                    }
                }
            } catch (error: IOException) {
                // 写正文时对端已经断开：真正的错误在响应码里，继续往下读
                runCatching { connection.errorStream?.close() }
            }

            val code = runCatching { connection.responseCode }.getOrDefault(-1)
            val body = readBody(connection, code)
            connection.disconnect()

            when {
                code in 200..299 -> PcResult(
                    ok = true,
                    message = "已发送",
                    savedPath = match(JSON_PATH, body)?.let { unescape(it) },
                )
                code > 0 -> {
                    val error = match(JSON_ERROR, body)?.let { unescape(it) }
                    PcResult(false, error?.let { "电脑端报错：$it" } ?: "电脑端返回 $code")
                }
                else -> PcResult(false, "发送中断，检查电脑端是否还在运行")
            }
        } catch (error: Exception) {
            PcResult(false, describe(error, target))
        }
    }

    /** 在电脑端建一个目录（空文件夹也要还原）。相对路径同样编码。 */
    suspend fun mkdir(
        target: PcTarget,
        relativePath: String,
        timeoutMs: Int = PING_TIMEOUT_MS,
    ): PcResult = withContext(Dispatchers.IO) {
        try {
            val connection = open(target, "/dir", "PUT", timeoutMs, fixedLength = 0L)
            connection.setRequestProperty("X-VV-Path", encodePath(relativePath))
            connection.doOutput = true
            connection.connect()
            connection.outputStream.use { it.flush() }
            val code = connection.responseCode
            val body = readBody(connection, code)
            connection.disconnect()
            when {
                code in 200..299 -> PcResult(true, "已建目录", match(JSON_PATH, body)?.let { unescape(it) })
                else -> PcResult(false, match(JSON_ERROR, body)?.let { "电脑端报错：$it" } ?: "电脑端返回 $code")
            }
        } catch (error: Exception) {
            PcResult(false, describe(error, target))
        }
    }

    /** 把网络异常翻译成人话——这些提示会直接显示在手机上。 */
    fun describe(error: Throwable, target: PcTarget): String = when (error) {
        is UnknownHostException -> "找不到电脑 ${target.host}，检查地址"
        is ConnectException -> "连不上 ${target.authority}，确认电脑端在跑、防火墙放行"
        is NoRouteToHostException -> "网络到不了 ${target.host}，两台设备不在同一个局域网？"
        is SocketTimeoutException -> "连接超时，检查 WiFi 与电脑端"
        else -> error.message?.takeIf { it.isNotBlank() }?.let { "发送失败：$it" } ?: "发送失败"
    }

    private fun open(
        target: PcTarget,
        path: String,
        method: String,
        timeoutMs: Int,
        fixedLength: Long = -1L,
    ): HttpURLConnection {
        val connection = URL("http://${target.host}:${target.port}$path")
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs.coerceAtMost(15_000)
        connection.readTimeout = timeoutMs
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "VVFile-Android")
        connection.setRequestProperty("Accept", "application/json")
        if (fixedLength >= 0) connection.setFixedLengthStreamingMode(fixedLength)
        return connection
    }

    private fun readBody(connection: HttpURLConnection, code: Int): String = runCatching {
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
    }.getOrDefault("")

    private fun match(regex: Regex, body: String): String? =
        regex.find(body)?.groupValues?.get(1)

    /** 电脑端按 JSON 转义写的 Windows 路径，显示前把 `\\` 还原成 `\`。 */
    internal fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c == '\\' && index + 1 < raw.length) {
                when (val next = raw[index + 1]) {
                    '\\' -> { out.append('\\'); index += 2 }
                    '"' -> { out.append('"'); index += 2 }
                    '/' -> { out.append('/'); index += 2 }
                    'n' -> { out.append('\n'); index += 2 }
                    'r' -> { out.append('\r'); index += 2 }
                    't' -> { out.append('\t'); index += 2 }
                    else -> { out.append(next); index += 2 }
                }
            } else {
                out.append(c)
                index += 1
            }
        }
        return out.toString()
    }

    /** 边写边报进度。 */
    private class ProgressOutputStream(
        private val delegate: OutputStream,
        private val total: Long,
        private val onProgress: (Long, Long) -> Unit,
    ) : OutputStream() {
        private var sent = 0L
        private var lastReport = 0L

        override fun write(b: Int) {
            delegate.write(b)
            sent += 1
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            sent += length
            if (sent - lastReport >= CHUNK || sent == total) {
                lastReport = sent
                onProgress(sent, total)
            }
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
    private val JSON_PATH = Regex("\"path\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val JSON_DIR = Regex("\"dir\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val JSON_ERROR = Regex("\"error\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
}

