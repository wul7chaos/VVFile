package com.vvfile.app.core.archive

import java.io.File
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.TimeZone

/** 中央目录里的一个条目（只读元数据，不解压）。 */
data class ZipCdEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val encrypted: Boolean,
)

/**
 * ZIP 中央目录读取器。
 *
 * 解决两件事：
 * 1. `java.util.zip` 不暴露「条目有没有密码」，读加密条目只会抛
 *    `invalid entry CRC`；这里直接读通用标志位 bit0（加密）。
 * 2. **Android 14+ 的 `ZipFile` 干脆打不开带加密条目的包**，抛
 *    `invalid CEN header (encrypted entry)`——连列表都进不去。这里自己解析，
 *    至少能把包内结构和「哪些条目带锁」显示出来。
 *
 * 只处理普通 ZIP；遇到 Zip64 或结构异常返回 null（调用方退回 `ZipFile` 的实现）。
 */
object ZipCentralDirectory {

    private const val FLAG_ENCRYPTED = 0x1
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF

    /** 解析失败（Zip64、损坏、非 ZIP）返回 null。 */
    fun read(file: File): List<ZipCdEntry>? = runCatching { parse(file) }.getOrNull()

    fun encryptedNames(file: File): Set<String> =
        read(file).orEmpty().filter { it.encrypted }.map { it.name }.toSet()

    fun hasEncryptedEntry(file: File): Boolean = encryptedNames(file).isNotEmpty()

    private fun parse(file: File): List<ZipCdEntry>? {
        if (!file.isFile || file.length() < EOCD_SIZE) return null
        RandomAccessFile(file, "r").use { raf ->
            val eocdOffset = findEocd(raf) ?: return null
            raf.seek(eocdOffset + 10)
            val entryCount = readU16(raf)
            raf.seek(eocdOffset + 12)
            val centralSize = readU32(raf)
            val centralOffset = readU32(raf)
            // Zip64：这里不展开，交给 ZipFile 那一路
            if (entryCount == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
                return null
            }
            if (centralOffset + centralSize > file.length()) return null

            val entries = mutableListOf<ZipCdEntry>()
            raf.seek(centralOffset)
            repeat(entryCount) {
                if (readU32(raf) != CENTRAL_SIGNATURE.toLong()) return entries
                raf.skipBytes(4) // version made by / version needed
                val flags = readU16(raf)
                raf.skipBytes(2) // method
                val dosTime = readU16(raf)
                val dosDate = readU16(raf)
                raf.skipBytes(4) // crc
                raf.skipBytes(4) // compressed size
                val size = readU32(raf)
                val nameLength = readU16(raf)
                val extraLength = readU16(raf)
                val commentLength = readU16(raf)
                raf.skipBytes(2 + 2 + 4 + 4) // disk / attrs / local offset
                val nameBytes = ByteArray(nameLength)
                raf.readFully(nameBytes)
                raf.skipBytes(extraLength + commentLength)

                val name = String(nameBytes, Charsets.UTF_8)
                val isDirectory = name.endsWith("/")
                entries += ZipCdEntry(
                    name = name,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else size,
                    modifiedAt = dosToEpochMillis(dosDate, dosTime),
                    encrypted = flags and FLAG_ENCRYPTED != 0,
                )
            }
            return entries
        }
    }

    /** DOS 的日期/时间（本地时区，2 秒精度）转 epoch 毫秒。 */
    internal fun dosToEpochMillis(date: Int, time: Int): Long {
        if (date == 0) return 0L
        val year = 1980 + (date shr 9)
        val month = (date shr 5) and 0x0F
        val day = date and 0x1F
        if (month !in 1..12 || day !in 1..31) return 0L
        val calendar = Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(year, month - 1, day, (time shr 11) and 0x1F, (time shr 5) and 0x3F, (time and 0x1F) * 2)
        }
        return calendar.timeInMillis
    }

    private fun findEocd(raf: RandomAccessFile): Long? {
        val length = raf.length()
        val window = minOf(length, (MAX_COMMENT + EOCD_SIZE).toLong()).toInt()
        val tail = ByteArray(window)
        raf.seek(length - window)
        raf.readFully(tail)
        for (index in window - EOCD_SIZE downTo 0) {
            if (readIntLe(tail, index) == EOCD_SIGNATURE) return length - window + index
        }
        return null
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readU16(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        return low or (high shl 8)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b0 = raf.read().toLong() and 0xFF
        val b1 = raf.read().toLong() and 0xFF
        val b2 = raf.read().toLong() and 0xFF
        val b3 = raf.read().toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
