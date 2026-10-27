package com.vvfile.app.core.archive

import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WinZip AES（AE-1 / AE-2）解密。
 *
 * ZIP 里这种条目比较特殊，参数全在中央目录的额外字段 `0x9901` 里：
 * `版本(2) | 厂商"AE"(2) | 强度(1) | 真实压缩方式(2)`，而压缩方式字段写的是 99。
 * 数据区结构是：`盐(8/12/16 字节) | 密码校验值(2 字节) | 密文 | HMAC-SHA1 前 10 字节`。
 *
 * 密钥用 PBKDF2-HMAC-SHA1、1000 轮、从同一个盐派生 `2*密钥长+2` 字节：
 * 前段是 AES 密钥，中段是 HMAC 密钥，末 2 字节是密码校验值。
 *
 * 加密是 AES-CTR，但**计数器是小端、从 1 开始**（Java 自带 CTR 是大端递增，
 * 直接拿来用会解出乱码），所以这里用 AES/ECB 手工走计数器。
 */
object WinZipAes {

    const val METHOD = 99
    const val EXTRA_ID = 0x9901
    const val AUTH_CODE_SIZE = 10
    private const val ITERATIONS = 1000

    /** 额外字段 `0x9901` 解出来的参数。 */
    data class Info(val version: Int, val strength: Int, val actualMethod: Int) {
        /** AE-2 不写 CRC（置 0），完整性完全靠 HMAC。 */
        val checksCrc: Boolean get() = version < 2
    }

    fun keyLength(strength: Int): Int = when (strength) {
        1 -> 16
        2 -> 24
        3 -> 32
        else -> throw ArchiveEncryptionUnsupportedException("不支持的 AES 强度：$strength")
    }

    /**
     * 盐的长度是密钥长度的一半：AES-128 → 8、AES-192 → 12、AES-256 → 16。
     *
     * 这里一开始直接拿密钥长度当盐长度，症状很迷惑：正确的密码会被判成错误，
     * 因为 PBKDF2 的输入盐多吃了 16 字节。
     */
    fun saltLength(strength: Int): Int = keyLength(strength) / 2

    class Keys(val aesKey: ByteArray, val macKey: ByteArray, val verifier: ByteArray)

    /** PBKDF2 派生密钥；[salt] 是条目数据区开头那几个字节。 */
    fun derive(password: CharArray, salt: ByteArray, strength: Int): Keys {
        val keyLength = keyLength(strength)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec = PBEKeySpec(password, salt, ITERATIONS, (keyLength * 2 + 2) * 8)
        val material = factory.generateSecret(spec).encoded
        spec.clearPassword()
        if (material.size < keyLength * 2 + 2) {
            throw ArchiveEncryptionUnsupportedException("AES 密钥派生失败")
        }
        return Keys(
            aesKey = material.copyOfRange(0, keyLength),
            macKey = material.copyOfRange(keyLength, keyLength * 2),
            verifier = material.copyOfRange(keyLength * 2, keyLength * 2 + 2),
        )
    }

    /**
     * AES-CTR 解密流。
     *
     * 边解密边把**密文**喂给 HMAC；读完密文后由 [verify] 比对尾部 10 字节认证码。
     */
    internal class CtrStream(
        private val raw: InputStream,
        private val ciphertextSize: Long,
        keys: Keys,
    ) : InputStream() {

        private val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.aesKey, "AES"))
        }
        private val mac: Mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(keys.macKey, "HmacSHA1"))
        }
        private val counter = ByteArray(16).also { it[0] = 1 } // 小端计数器，从 1 开始
        private val keyStream = ByteArray(16)
        private var keyStreamPos = 16
        private var remaining = ciphertextSize

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val want = minOf(length.toLong(), remaining).toInt()
            val read = raw.read(buffer, offset, want)
            if (read <= 0) return -1
            mac.update(buffer, offset, read)
            for (index in offset until offset + read) {
                if (keyStreamPos >= 16) {
                    cipher.doFinal(counter, 0, 16, keyStream, 0)
                    incrementCounter()
                    keyStreamPos = 0
                }
                buffer[index] = (buffer[index].toInt() xor keyStream[keyStreamPos].toInt()).toByte()
                keyStreamPos++
            }
            remaining -= read
            return read
        }

        private fun incrementCounter() {
            for (index in 0 until 16) {
                counter[index] = (counter[index] + 1).toByte()
                if (counter[index] != 0.toByte()) return
            }
        }

        /**
         * 收尾：把没读完的密文吃掉（HMAC 要覆盖整段密文），
         * 再读 10 字节认证码比对。密码错、文件被改都会在这里暴露。
         */
        fun verify() {
            val scratch = ByteArray(8 * 1024)
            while (remaining > 0L) {
                if (read(scratch, 0, scratch.size) < 0) break
            }
            val expected = ByteArray(AUTH_CODE_SIZE)
            var filled = 0
            while (filled < AUTH_CODE_SIZE) {
                val read = raw.read(expected, filled, AUTH_CODE_SIZE - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled < AUTH_CODE_SIZE) throw ArchivePasswordWrongException()
            val actual = mac.doFinal()
            if (actual.size < AUTH_CODE_SIZE) throw ArchivePasswordWrongException()
            var diff = 0
            for (index in 0 until AUTH_CODE_SIZE) diff = diff or (actual[index].toInt() xor expected[index].toInt())
            if (diff != 0) throw ArchivePasswordWrongException()
        }
    }
}
