package com.vvfile.app.core.archive

/**
 * 传统 ZIP 加密（PKWARE ZipCrypto，也叫「Zip 2.0 加密」）。
 *
 * 算法来自 PKWARE APPNOTE 6.1 的「Traditional PKWARE Encryption」一节，也用
 * 7-Zip 的 `C/ZipCrypto.c` 的密钥流做法交叉核对过：
 *
 * - 三个 32 位密钥由密码逐字节滚动生成；
 * - 每个明文字节与 `decryptByte()` 异或，然后把这个明文字节喂回密钥流；
 * - 条目开头有 12 字节加密头，解出来的最后一个字节必须等于 CRC 的高字节
 *   （没有 CRC 时是修改时间的高字节）——这是唯一能提前判断密码对不对的办法。
 *
 * 强度上它是「混淆」而不是真正的加密（已知明文可破），但用户手上 99% 的
 * 老压缩包就是这么加密的，能解开比讲道理重要。
 */
internal class ZipCrypto(password: CharArray) {

    private var key0 = 0x12345678
    private var key1 = 0x23456789
    private var key2 = 0x34567890

    init {
        for (char in password) updateKeys(char.code and 0xFF)
    }

    /** 就地解密 [length] 字节：`plain[i] = cipher[i] xor keyStream()`。 */
    fun decrypt(buffer: ByteArray, offset: Int, length: Int) {
        for (index in offset until offset + length) {
            val value = buffer[index].toInt() and 0xFF
            val plain = value xor decryptByte()
            buffer[index] = plain.toByte()
            updateKeys(plain)
        }
    }

    private fun decryptByte(): Int {
        val temp = (key2 or 2) and 0xFFFF
        return ((temp * (temp xor 1)) ushr 8) and 0xFF
    }

    private fun updateKeys(value: Int) {
        key0 = crc32(key0, value)
        key1 = (key1 + (key0 and 0xFF)) * 134775813 + 1
        key2 = crc32(key2, (key1 ushr 24) and 0xFF)
    }

    companion object {

        /**
         * 解开 12 字节加密头，返回**继续可用的**密钥流和校验字节。
         *
         * 关键点：数据区必须接着加密头之后的密钥状态继续解，不能拿同一密码新开一份
         * 密钥流——否则每个字节都对不上（表现出来就是 CRC 全错）。
         */
        fun openHeader(header: ByteArray, password: CharArray): Pair<ZipCrypto, Int>? {
            if (header.size != HEADER_SIZE) return null
            val crypto = ZipCrypto(password)
            crypto.decrypt(header, 0, HEADER_SIZE)
            return crypto to (header[HEADER_SIZE - 1].toInt() and 0xFF)
        }

        internal const val HEADER_SIZE = 12

        /** ZIP 用的 CRC-32 表（多项式 0xEDB88320）。 */
        private val CRC_TABLE = IntArray(256) { index ->
            var value = index
            repeat(8) {
                value = if (value and 1 != 0) (value ushr 1) xor 0xEDB88320.toInt() else value ushr 1
            }
            value
        }

        private fun crc32(crc: Int, value: Int): Int =
            CRC_TABLE[(crc xor value) and 0xFF] xor (crc ushr 8)
    }
}
