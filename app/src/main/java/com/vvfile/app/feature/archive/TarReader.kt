package com.vvfile.app.feature.archive

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * TAR / TAR.GZ 读取器。
 *
 * 只做「列表 + 单个条目解出来」两件事，够包内浏览用；tar 是流式格式，没有中央目录，
 * 所以列表要顺序读一遍头（每个 512 字节一块），`tar.gz` 还得先解一层 gzip。
 * 条目数据按 size 跳过，不读进内存。
 *
 * 支持的写法：ustar（`prefix` 字段）、GNU 长文件名（`L`）、PAX 扩展头（`x`/`g`，
 * 只取 `path=` 与 `size=`）。软/硬链接作为普通条目列出，解出来是空内容（没有数据块）。
 */
internal object TarReader {

    private const val BLOCK = 512

    fun read(file: File, gzipped: Boolean): List<ArchiveEntryInfo> {
        val entries = mutableListOf<ArchiveEntryInfo>()
        openStream(file, gzipped).use { input ->
            var pendingName: String? = null
            var pendingSize: Long? = null
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readBlock(input, header)) break
                if (isZeroBlock(header)) break

                val typeflag = header[156].toInt().toChar()
                val rawName = cString(header, 0, 100)
                val rawSize = tarNumber(header, 124, 12)
                var size = rawSize
                var path = resolveName(header, rawName)

                when (typeflag) {
                    'x', 'g', 'L', 'K' -> {
                        // 扩展头：内容里带下一条目要用的名字/大小
                        val payload = readPayload(input, size)
                        when (typeflag) {
                            'L' -> pendingName = payload.toString(Charsets.UTF_8).trimEnd('\u0000')
                            'x', 'g' -> {
                                val pax = parsePax(payload.toString(Charsets.UTF_8))
                                pax["path"]?.let { pendingName = it }
                                pax["size"]?.toLongOrNull()?.let { pendingSize = it }
                            }
                            // 'K'：链接名，用不到
                            else -> Unit
                        }
                        continue
                    }
                    else -> Unit
                }

                pendingName?.let {
                    path = it
                    pendingName = null
                }
                pendingSize?.let {
                    size = it
                    pendingSize = null
                }

                val isDirectory = typeflag == '5' || path.endsWith("/")
                val modifiedAt = tarNumber(header, 136, 12) * 1000L
                if (path.isNotBlank() && path != "./") {
                    entries += ArchiveEntryInfo(
                        name = if (isDirectory) path.trimEnd('/') + "/" else path,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0L else size,
                        modifiedAt = modifiedAt,
                    )
                }
                if (!skipData(input, size)) break
            }
        }
        return entries
    }

    /** 把单个条目写到 [target]；条目不存在或没有数据块（软链接）返回 false。 */
    fun extract(
        file: File,
        gzipped: Boolean,
        entryName: String,
        target: File,
    ): Boolean {
        openStream(file, gzipped).use { input ->
            val header = ByteArray(BLOCK)
            var pendingName: String? = null
            var pendingSize: Long? = null
            while (true) {
                if (!readBlock(input, header)) return false
                if (isZeroBlock(header)) return false

                val typeflag = header[156].toInt().toChar()
                var size = tarNumber(header, 124, 12)
                var path = resolveName(header, cString(header, 0, 100))

                when (typeflag) {
                    'x', 'g', 'L', 'K' -> {
                        val payload = readPayload(input, size)
                        when (typeflag) {
                            'L' -> pendingName = payload.toString(Charsets.UTF_8).trimEnd('\u0000')
                            'x', 'g' -> {
                                val pax = parsePax(payload.toString(Charsets.UTF_8))
                                pax["path"]?.let { pendingName = it }
                                pax["size"]?.toLongOrNull()?.let { pendingSize = it }
                            }
                            else -> Unit
                        }
                        continue
                    }
                    else -> Unit
                }

                pendingName?.let {
                    path = it
                    pendingName = null
                }
                pendingSize?.let {
                    size = it
                    pendingSize = null
                }

                val isDirectory = typeflag == '5' || path.endsWith("/")
                val matched = !isDirectory && path == entryName
                if (matched) {
                    if (size <= 0L) {
                        target.writeBytes(ByteArray(0))
                        return true
                    }
                    target.outputStream().use { output -> copyExactly(input, output, size) }
                    return true
                }
                if (!skipData(input, size)) return false
            }
        }
    }

    private fun openStream(file: File, gzipped: Boolean): InputStream {
        val raw = BufferedInputStream(FileInputStream(file), 64 * 1024)
        return if (gzipped) GZIPInputStream(raw, 64 * 1024) else raw
    }

    private fun resolveName(header: ByteArray, name: String): String {
        val prefix = cString(header, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(block: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { block[it] == 0.toByte() } ?: (offset + length)
        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    /** tar 的数字字段：八进制 ASCII，首字节 0x80 时是 base-256（大文件）。 */
    private fun tarNumber(block: ByteArray, offset: Int, length: Int): Long {
        if (block[offset] == 0x80.toByte()) {
            var value = 0L
            for (index in offset + 1 until offset + length) {
                value = (value shl 8) or (block[index].toLong() and 0xFF)
            }
            return value
        }
        var value = 0L
        for (index in offset until offset + length) {
            val byte = block[index].toInt() and 0xFF
            if (byte == 0 || byte == ' '.code) {
                if (value != 0L || index > offset) break
                continue
            }
            if (byte < '0'.code || byte > '7'.code) break
            value = value * 8 + (byte - '0'.code)
        }
        return value
    }

    private fun isZeroBlock(block: ByteArray): Boolean {
        for (byte in block) if (byte != 0.toByte()) return false
        return true
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val count = input.read(block, read, block.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun readPayload(input: InputStream, size: Long): ByteArray {
        if (size <= 0L) return ByteArray(0)
        if (size > MAX_EXTENSION_HEADER) throw IOException("扩展头过大：$size")
        val payload = ByteArray(size.toInt())
        var read = 0
        while (read < payload.size) {
            val count = input.read(payload, read, payload.size - read)
            if (count < 0) throw EOFException("压缩包在扩展头处结束")
            read += count
        }
        skipPadding(input, size)
        return payload
    }

    private fun skipData(input: InputStream, size: Long): Boolean {
        if (!skipExactly(input, size)) return false
        return skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long): Boolean {
        val padding = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        return padding == 0 || skipExactly(input, padding.toLong())
    }

    private fun skipExactly(input: InputStream, count: Long): Boolean {
        var remaining = count
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return false
            remaining -= read
        }
        return true
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("压缩包在条目数据处结束")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    /** PAX 头：一行一条 `长度 键=值\n`，只取需要的键。 */
    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            if (length <= 0 || index + length > text.length) break
            val record = text.substring(space + 1, index + length).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    private const val MAX_EXTENSION_HEADER = 1024L * 1024
}
