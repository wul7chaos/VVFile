package com.vvfile.app.core.archive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 加密 ZIP 解密。
 *
 * 样本是 7-Zip 生成的：`-mem=ZipCrypto` 走传统加密，`-mem=AES256` 走 WinZip AES，
 * 两条路都必须能解开，且密码错要在**打开时**就被识破。
 */
class EncryptedZipTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/archives/$name") ?: error("缺少测试样本 $name")
        val target = temp.newFile(name)
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    @Test
    fun `传统加密能被中央目录识别出加密位`() {
        val entries = ZipCentralDirectory.read(fixture("zipcrypto.zip"))!!
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
        assertTrue(entries.all { it.encrypted })
        // 传统加密没有 AES 参数
        assertNull(entries.first().aes)
    }

    @Test
    fun `WinZip AES 的参数能读出来`() {
        val entries = ZipCentralDirectory.read(fixture("aes.zip"))!!
        val entry = entries.first { it.name == "docs/b.txt" }
        assertTrue(entry.encrypted)
        assertEquals(WinZipAes.METHOD, entry.compressionMethod)
        val aes = entry.aes
        assertNotNull(aes)
        assertEquals(3, aes!!.strength) // 256 位
        assertEquals(8, aes.actualMethod) // 真实方式是 deflate
        // 小文件 7-Zip 会直接存储，AES 条目里的真实方式就是 0
        assertEquals(0, entries.first { it.name == "a.txt" }.aes!!.actualMethod)
    }

    @Test
    fun `传统加密用正确密码能解密`() {
        val archive = fixture("zipcrypto.zip")
        val entry = ZipCentralDirectory.entryOf(archive, "a.txt")!!
        assertEquals("hello from VVFile", String(ZipEntryReader.readBytes(archive, entry, "123456")))
    }

    @Test
    fun `WinZip AES 用正确密码能解密`() {
        val archive = fixture("aes.zip")
        val entry = ZipCentralDirectory.entryOf(archive, "a.txt")!!
        assertEquals("hello from VVFile", String(ZipEntryReader.readBytes(archive, entry, "123456")))
    }

    @Test
    fun `WinZip AES 的大条目也能完整解出`() {
        val archive = fixture("aes.zip")
        val entry = ZipCentralDirectory.entryOf(archive, "docs/b.txt")!!
        val content = ZipEntryReader.readBytes(archive, entry, "123456")
        assertEquals(500, content.size)
        assertTrue(content.all { it == 'x'.code.toByte() })
    }

    @Test
    fun `没给密码时明确要求密码`() {
        val archive = fixture("zipcrypto.zip")
        val entry = ZipCentralDirectory.entryOf(archive, "a.txt")!!
        try {
            ZipEntryReader.open(archive, entry, null).use { it.read() }
            fail("应当要求密码")
        } catch (_: ArchivePasswordRequiredException) {
            // 预期
        }
    }

    @Test
    fun `密码不对时在打开阶段就报错`() {
        for (name in listOf("zipcrypto.zip", "aes.zip")) {
            val archive = fixture(name)
            val entry = ZipCentralDirectory.entryOf(archive, "a.txt")!!
            try {
                ZipEntryReader.open(archive, entry, "wrong-password").use { it.read() }
                fail("$name 的错误密码应当被识破")
            } catch (_: ArchivePasswordWrongException) {
                // 预期
            }
        }
    }

    @Test
    fun `解到磁盘能拿到完整文件`() {
        val archive = fixture("aes.zip")
        val entry = ZipCentralDirectory.entryOf(archive, "docs/b.txt")!!
        val target = File(temp.newFolder(), "b.txt")
        val written = ZipEntryReader.extract(archive, entry, target, "123456")
        assertEquals(500L, written)
        assertEquals(500, target.length().toInt())
    }
}
