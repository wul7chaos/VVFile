package com.vvfile.app.core.archive

import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * 加密 ZIP 条目的解密读取。
 *
 * 两条加密路线：
 * - 传统 ZipCrypto：12 字节加密头，最后 1 字节是校验字节；
 * - WinZip AES：盐 + 2 字节校验值 + AES-CTR 密文 + 10 字节 HMAC-SHA1。
 *
 * 解出来的明文再按真实压缩方式（stored / deflate）解压，读完做 CRC 校验。
 * 密码不对时在**打开阶段**就抛 [ArchivePasswordWrongException]，不等到读内容时
 * 才蹦一个 `invalid entry CRC`。
 */
object ZipEntryReader {

    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val LOCAL_FIXED_SIZE = 30

    /**
     * 打开条目的流；调用方负责关闭。
     *
     * [password] 为 null 且条目加密时抛 [ArchivePasswordRequiredException]。
     */
    fun open(archive: File, entry: ZipCdEntry, password: String?): InputStream {
        if (entry.isDirectory) throw ZipException("目录条目没有数据")
        val raw = openRawData(archive, entry)
        var aesStream: WinZipAes.CtrStream? = null
        try {
            val plain: InputStream = when {
                !entry.encrypted -> raw
                entry.aes != null -> {
                    val secret = password ?: throw ArchivePasswordRequiredException()
                    aesStream = openAes(raw, entry, secret)
                    aesStream
                }
                else -> openZipCrypto(raw, entry, password)
            }
            val inflated: InputStream = when (entry.dataMethod) {
                0 -> plain
                8 -> InflaterInputStream(plain, Inflater(true), 16 * 1024)
                else -> throw ArchiveEncryptionUnsupportedException(
                    "不支持的压缩方式：${entry.dataMethod}",
                )
            }
            return VerifiedStream(inflated, entry, aesStream)
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    /** 把条目解到 [target]；大小与条目声明不符时按损坏处理。 */
    fun extract(archive: File, entry: ZipCdEntry, target: File, password: String?): Long {
        target.parentFile?.mkdirs()
        var written = 0L
        open(archive, entry, password).use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            }
        }
        return written
    }

    /** 整条读进内存；只用于小条目（预览、单测）。 */
    fun readBytes(archive: File, entry: ZipCdEntry, password: String?, limit: Int = 8 * 1024 * 1024): ByteArray {
        open(archive, entry, password).use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(entry.size, 64 * 1024L).toInt().coerceAtLeast(64))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("条目过大")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    // ---------------------------------------------------------------- 解密层

    private fun openZipCrypto(raw: InputStream, entry: ZipCdEntry, password: String?): InputStream {
        val secret = password ?: throw ArchivePasswordRequiredException()
        val header = ByteArray(ZipCrypto.HEADER_SIZE)
        readFully(raw, header)
        // 密钥流要一路带到数据区，不能解完头就丢掉
        val (crypto, checkByte) = ZipCrypto.openHeader(header, secret.toCharArray())
            ?: throw ZipException("加密头损坏")
        // 中央目录里的 CRC 高字节就是校验字节；条目没写 CRC（数据描述符）时
        // 没法提前验证，交给后面的 CRC 校验兜底
        if (entry.crc32 != 0L) {
            val expected = ((entry.crc32 ushr 24) and 0xFF).toInt()
            if (checkByte != expected) throw ArchivePasswordWrongException()
        }
        return DecryptingStream(raw, crypto)
    }

    private fun openAes(raw: InputStream, entry: ZipCdEntry, password: String): WinZipAes.CtrStream {
        val info = entry.aes ?: throw ArchiveEncryptionUnsupportedException("缺少 AES 参数")
        val saltLength = WinZipAes.saltLength(info.strength)
        val salt = ByteArray(saltLength)
        readFully(raw, salt)
        val verifier = ByteArray(2)
        readFully(raw, verifier)
        val keys = WinZipAes.derive(password.toCharArray(), salt, info.strength)
        if (keys.verifier[0] != verifier[0] || keys.verifier[1] != verifier[1]) {
            throw ArchivePasswordWrongException()
        }
        val ciphertext = entry.compressedSize - saltLength - 2 - WinZipAes.AUTH_CODE_SIZE
        if (ciphertext < 0) throw ZipException("AES 条目长度异常")
        return WinZipAes.CtrStream(raw, ciphertext, keys)
    }

    /** ZipCrypto 的字节流解密（就地异或）。 */
    private class DecryptingStream(
        source: InputStream,
        private val crypto: ZipCrypto,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) crypto.decrypt(buffer, offset, read)
            return read
        }
    }

    // ---------------------------------------------------------------- 校验层

    /**
     * 读完时校验：AES 条目验 HMAC（AE-1 还验 CRC），ZipCrypto 只验 CRC。
     * 校验失败统一翻译成「密码不正确 / 文件损坏」，不让底层英文异常漏出去。
     */
    private class VerifiedStream(
        source: InputStream,
        private val entry: ZipCdEntry,
        private val aes: WinZipAes.CtrStream?,
    ) : FilterInputStream(source) {

        private val crc = CRC32()
        private var verified = false

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                crc.update(buffer, offset, read)
            } else if (read < 0) {
                verify()
            }
            return read
        }

        override fun close() {
            try {
                // 没读到底就关（预览只取前几 KB）时不做完整性校验，避免误报；
                // 但 AES 的 HMAC 必须验，否则密码错会悄悄通过
                if (aes != null && !verified) verifyAes()
            } finally {
                super.close()
            }
        }

        private fun verify() {
            if (verified) return
            verified = true
            if (aes != null) verifyAes()
            val expectCrc = entry.crc32
            val checksCrc = aes == null || entry.aes?.checksCrc != false
            if (checksCrc && expectCrc != 0L && crc.value != expectCrc) {
                throw ZipException("条目校验失败（密码错误或文件已损坏）")
            }
        }

        private fun verifyAes() {
            val stream = aes ?: return
            try {
                stream.verify()
            } catch (error: ArchivePasswordWrongException) {
                throw error
            } catch (error: IOException) {
                throw ZipException("条目校验失败（密码错误或文件已损坏）")
            }
        }
    }

    // ---------------------------------------------------------------- 定位数据区

    /** 按本地头算数据区起点，再用限长的原始流包住，防止越界读到下一个条目。 */
    private fun openRawData(archive: File, entry: ZipCdEntry): InputStream {
        val dataStart: Long
        RandomAccessFile(archive, "r").use { raf ->
            if (entry.localHeaderOffset < 0 || entry.localHeaderOffset + LOCAL_FIXED_SIZE > raf.length()) {
                throw ZipException("条目偏移越界")
            }
            raf.seek(entry.localHeaderOffset)
            if (readU32(raf) != LOCAL_SIGNATURE) throw ZipException("本地文件头损坏")
            raf.skipBytes(22) // 版本/标志/方式/时间/日期/CRC/两个大小
            val nameLength = readU16(raf)
            val extraLength = readU16(raf)
            dataStart = entry.localHeaderOffset + LOCAL_FIXED_SIZE + nameLength + extraLength
        }
        if (dataStart > archive.length()) throw ZipException("条目数据越界")
        val file = FileInputStream(archive)
        val channel = file.channel
        channel.position(dataStart)
        val limit = minOf(entry.compressedSize, archive.length() - dataStart)
        return BoundedInputStream(Channels.newInputStream(channel), limit)
    }

    private class BoundedInputStream(
        source: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val want = minOf(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, want)
            if (read > 0) remaining -= read
            return read
        }

        override fun available(): Int = minOf(remaining, super.available().toLong()).toInt()
    }

    private fun readFully(source: InputStream, target: ByteArray) {
        var filled = 0
        while (filled < target.size) {
            val read = source.read(target, filled, target.size - filled)
            if (read < 0) throw ZipException("压缩包数据不完整")
            filled += read
        }
    }

    private fun readU16(raf: RandomAccessFile): Int = raf.read() or (raf.read() shl 8)

    private fun readU32(raf: RandomAccessFile): Long =
        (raf.read().toLong() and 0xFF) or
            ((raf.read().toLong() and 0xFF) shl 8) or
            ((raf.read().toLong() and 0xFF) shl 16) or
            ((raf.read().toLong() and 0xFF) shl 24)
}
