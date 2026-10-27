package com.vvfile.app.core.archive

import java.io.File
import java.io.RandomAccessFile

/**
 * 压缩包的真实类型探测（看字节，不看扩展名）。
 *
 * 需要它的原因是**自解压包**：WinRAR 生成的 `.rar.exe` / `.zip.exe` 前面挂了一段
 * Windows 可执行 stub，扩展名是 `.exe`，光看名字永远认不出来。这里先看文件头，
 * 认不出再看 `MZ` 之后的前 2 MiB 里有没有归档签名（stub 通常不到 1 MiB），
 * 最后兜底扫尾部找 ZIP 的 EOCD。
 */
object ArchiveSignature {

    enum class Container { ZIP, RAR, SEVEN_Z }

    private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    private const val SCAN_LIMIT = 2 * 1024 * 1024
    private const val CHUNK = 64 * 1024
    private const val TAIL_LIMIT = 128 * 1024

    /** 探测失败（普通文本、图片、真 PE 程序）返回 null。 */
    fun of(file: File): Container? {
        if (!file.isFile || file.length() < 8) return null
        val head = readPrefix(file, 16)
        atStart(head)?.let { return it }
        // 自解压：`MZ` 开头，归档体在后面
        if (head.size >= 2 && head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()) {
            scanBody(file)?.let { return it }
            if (hasZipTail(file)) return Container.ZIP
        }
        return null
    }

    private fun atStart(head: ByteArray): Container? = when {
        head.startsWith(SEVEN_Z) -> Container.SEVEN_Z
        head.startsWith(RAR4) || head.startsWith(RAR5) -> Container.RAR
        head.startsWith(ZIP_LOCAL) || head.startsWith(ZIP_EMPTY) -> Container.ZIP
        else -> null
    }

    private fun scanBody(file: File): Container? {
        RandomAccessFile(file, "r").use { raf ->
            val limit = minOf(raf.length(), SCAN_LIMIT.toLong())
            val buffer = ByteArray(CHUNK + 8)
            var position = 0L
            var carry = 0
            while (position < limit) {
                val want = minOf(CHUNK.toLong(), limit - position).toInt()
                raf.seek(position)
                raf.readFully(buffer, carry, want)
                val size = carry + want
                for (index in 0..size - ZIP_LOCAL.size) {
                    if (matchesAt(buffer, index, RAR4) || matchesAt(buffer, index, RAR5)) {
                        return Container.RAR
                    }
                    if (matchesAt(buffer, index, ZIP_LOCAL)) return Container.ZIP
                }
                // 留 8 字节重叠，避免签名被切在块边界
                carry = minOf(8, size)
                System.arraycopy(buffer, size - carry, buffer, 0, carry)
                position += want
            }
        }
        return null
    }

    private fun hasZipTail(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val window = minOf(length, TAIL_LIMIT.toLong()).toInt()
            val tail = ByteArray(window)
            raf.seek(length - window)
            raf.readFully(tail)
            for (index in 0..window - ZIP_EMPTY.size) {
                if (matchesAt(tail, index, ZIP_EMPTY)) return true
            }
        }
        return false
    }

    private fun readPrefix(file: File, size: Int): ByteArray {
        val buffer = ByteArray(size)
        file.inputStream().use { input ->
            var filled = 0
            while (filled < size) {
                val read = input.read(buffer, filled, size - filled)
                if (read < 0) break
                filled += read
            }
            return buffer.copyOf(filled)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private fun matchesAt(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > bytes.size) return false
        for (index in pattern.indices) if (bytes[offset + index] != pattern[index]) return false
        return true
    }
}
