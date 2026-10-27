package com.vvfile.app.feature.archive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「要不要密码 / 密码对不对」的判断。
 *
 * 这两件事决定 UI：前者决定是否弹框，后者决定框里显示不显示「密码不正确」，
 * 都不该让解压跑完再靠失败项去猜。
 */
class ArchiveEncryptionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/archives/$name") ?: error("缺少测试样本 $name")
        val target = temp.newFile(name)
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    @Test
    fun `普通里没有加密内容的包不需要密码`() {
        assertFalse(ArchiveEncryption.needsPassword(fixture("plain.7z").absolutePath))
    }

    @Test
    fun `加密 zip 需要密码`() {
        assertTrue(ArchiveEncryption.needsPassword(fixture("zipcrypto.zip").absolutePath))
        assertTrue(ArchiveEncryption.needsPassword(fixture("aes.zip").absolutePath))
    }

    @Test
    fun `加密的两种 7z 都需要密码`() {
        assertTrue(ArchiveEncryption.needsPassword(fixture("encrypted.7z").absolutePath))
        assertTrue(ArchiveEncryption.needsPassword(fixture("dataonly.7z").absolutePath))
    }

    @Test
    fun `zip 密码验证`() {
        val path = fixture("aes.zip").absolutePath
        assertTrue(ArchiveEncryption.verify(path, "123456"))
        assertFalse(ArchiveEncryption.verify(path, "wrong"))
    }

    @Test
    fun `传统加密 zip 密码验证`() {
        val path = fixture("zipcrypto.zip").absolutePath
        assertTrue(ArchiveEncryption.verify(path, "123456"))
        assertFalse(ArchiveEncryption.verify(path, "wrong"))
    }

    @Test
    fun `7z 密码验证`() {
        val header = fixture("encrypted.7z").absolutePath
        assertTrue(ArchiveEncryption.verify(header, "123456"))
        assertFalse(ArchiveEncryption.verify(header, "wrong"))

        val dataOnly = fixture("dataonly.7z").absolutePath
        assertTrue(ArchiveEncryption.verify(dataOnly, "123456"))
        assertFalse(ArchiveEncryption.verify(dataOnly, "wrong"))
    }

    @Test
    fun `没加密的包任何密码都算通过`() {
        assertTrue(ArchiveEncryption.verify(fixture("plain.7z").absolutePath, "whatever"))
    }
}
