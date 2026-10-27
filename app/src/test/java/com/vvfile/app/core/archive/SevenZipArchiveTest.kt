package com.vvfile.app.core.archive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 7z 容器读取（含加密）。
 *
 * 样本同样由 7-Zip 生成：`plain.7z` 无密码、`encrypted.7z` 连文件头都加密
 * （`-mhe=on`）、`dataonly.7z` 只加密数据。后两者的区别决定密码框什么时候弹。
 */
class SevenZipArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/archives/$name") ?: error("缺少测试样本 $name")
        val target = temp.newFile(name)
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    @Test
    fun `普通 7z 能列出条目`() {
        val entries = SevenZipArchive.list(fixture("plain.7z"), null)
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
        assertEquals(17L, entries.first { it.name == "a.txt" }.size)
    }

    @Test
    fun `普通 7z 能解出内容`() {
        val target = File(temp.newFolder(), "a.txt")
        assertTrue(SevenZipArchive.extract(fixture("plain.7z"), "a.txt", target, null))
        assertEquals("hello from VVFile", target.readText())
    }

    @Test
    fun `加密文件头的包不给密码读不出列表`() {
        try {
            SevenZipArchive.list(fixture("encrypted.7z"), null)
            fail("应当要求密码")
        } catch (_: ArchivePasswordRequiredException) {
            // 预期
        }
    }

    @Test
    fun `加密文件头的包给对密码就能读`() {
        val archive = fixture("encrypted.7z")
        val entries = SevenZipArchive.list(archive, "123456")
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())

        val target = File(temp.newFolder(), "a.txt")
        assertTrue(SevenZipArchive.extract(archive, "a.txt", target, "123456"))
        assertEquals("hello from VVFile", target.readText())
    }

    @Test
    fun `加密文件头的包密码错了会报错`() {
        try {
            SevenZipArchive.list(fixture("encrypted.7z"), "bad")
            fail("错误密码应当报错")
        } catch (_: ArchivePasswordWrongException) {
            // 预期
        }
    }

    @Test
    fun `只加密数据的包不给密码也能列出条目`() {
        val archive = fixture("dataonly.7z")
        val entries = SevenZipArchive.list(archive, null)
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
    }

    @Test
    fun `只加密数据的包解压时才要密码`() {
        val archive = fixture("dataonly.7z")
        val target = File(temp.newFolder(), "a.txt")
        try {
            SevenZipArchive.extract(archive, "a.txt", target, null)
            fail("应当要求密码")
        } catch (_: ArchivePasswordRequiredException) {
            // 预期
        }
    }

    @Test
    fun `只加密数据的包给对密码能解出`() {
        val archive = fixture("dataonly.7z")
        val target = File(temp.newFolder(), "a.txt")
        assertTrue(SevenZipArchive.extract(archive, "a.txt", target, "123456"))
        assertEquals("hello from VVFile", target.readText())
    }

    @Test
    fun `要不要密码的探测结果符合实际`() {
        assertFalse(SevenZipArchive.requiresPassword(fixture("plain.7z")))
        assertTrue(SevenZipArchive.requiresPassword(fixture("encrypted.7z")))
        assertTrue(SevenZipArchive.requiresPassword(fixture("dataonly.7z")))
    }

    @Test
    fun `签名判断能认出 7z`() {
        assertTrue(SevenZipArchive.matches(fixture("plain.7z")))
        assertFalse(SevenZipArchive.matches(fixture("zipcrypto.zip")))
    }
}
