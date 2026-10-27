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
 * RAR 读取（RAR5 / 固实 / 密码 / 自解压）。
 *
 * 样本由 WinRAR 7.22 生成：`rar5.rar`、`solid5.rar`（`-s`）、`rar5-pw.rar`（`-p`，
 * 只加密数据）、`rar5-pwh.rar`（`-hp`，连文件头一起加密）。
 * 自解压样本不落库，测试里临时拼一个「stub + 归档体」——WinRAR 的 `.exe` 就是这个结构。
 */
class RarArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/archives/$name") ?: error("缺少测试样本 $name")
        val target = temp.newFile(name)
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** 在归档前面垫一段假 stub，模拟自解压包。 */
    private fun selfExtracting(source: String): File {
        val payload = fixture(source).readBytes()
        val target = temp.newFile("sfx-$source.exe")
        target.outputStream().use { output ->
            output.write("MZ".toByteArray())
            output.write(ByteArray(70_000))
            output.write(payload)
        }
        return target
    }

    @Test
    fun `RAR5 能列出条目并解出内容`() {
        val archive = fixture("rar5.rar")
        val entries = RarArchive.list(archive, null)
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
        assertEquals(17L, entries.first { it.name == "a.txt" }.size)

        val target = File(temp.newFolder(), "a.txt")
        assertTrue(RarArchive.extract(archive, "a.txt", target, null))
        assertEquals("hello from VVFile", target.readText())
    }

    @Test
    fun `固实压缩的后续条目也能解出`() {
        val archive = fixture("solid5.rar")
        val out = temp.newFolder("solid")
        assertTrue(RarArchive.extract(archive, "a.txt", File(out, "a.txt"), null))
        assertTrue(RarArchive.extract(archive, "docs/b.txt", File(out, "b.txt"), null))
        assertEquals(500, File(out, "b.txt").length().toInt())
    }

    @Test
    fun `只加密数据的 RAR 能列出条目且标出加密`() {
        val archive = fixture("rar5-pw.rar")
        val entries = RarArchive.list(archive, null)
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
        assertTrue(entries.all { it.encrypted })
    }

    @Test
    fun `只加密数据的 RAR 解压时才要密码`() {
        val archive = fixture("rar5-pw.rar")
        try {
            RarArchive.extract(archive, "a.txt", File(temp.newFolder(), "a.txt"), null)
            fail("应当要求密码")
        } catch (_: ArchivePasswordRequiredException) {
            // 预期
        }
    }

    @Test
    fun `RAR 密码正确能解开，错误会报错`() {
        val archive = fixture("rar5-pw.rar")
        val target = File(temp.newFolder(), "a.txt")
        assertTrue(RarArchive.extract(archive, "a.txt", target, "123456"))
        assertEquals("hello from VVFile", target.readText())

        try {
            RarArchive.extract(archive, "a.txt", File(temp.newFolder(), "x.txt"), "bad")
            fail("错误密码应当报错")
        } catch (_: ArchivePasswordWrongException) {
            // 预期
        }
    }

    @Test
    fun `加密文件头的 RAR 不给密码读不出列表`() {
        try {
            RarArchive.list(fixture("rar5-pwh.rar"), null)
            fail("应当要求密码")
        } catch (_: ArchivePasswordRequiredException) {
            // 预期
        }
    }

    @Test
    fun `加密文件头的 RAR 给对密码能读能解`() {
        val archive = fixture("rar5-pwh.rar")
        val entries = RarArchive.list(archive, "123456")
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
        val target = File(temp.newFolder(), "a.txt")
        assertTrue(RarArchive.extract(archive, "a.txt", target, "123456"))
        assertEquals("hello from VVFile", target.readText())
    }

    @Test
    fun `自解压 exe 能按压缩包打开`() {
        val sfx = selfExtracting("rar5.rar")
        assertEquals(ArchiveSignature.Container.RAR, ArchiveSignature.of(sfx))
        val entries = RarArchive.list(sfx, null)
        assertEquals(setOf("a.txt", "docs/b.txt"), entries.map { it.name }.toSet())
        assertTrue(entries.none { it.encrypted })
    }

    @Test
    fun `压缩包里没有的条目返回 false`() {
        assertFalse(RarArchive.extract(fixture("rar5.rar"), "nope.txt", File(temp.newFolder(), "x"), null))
    }
}
