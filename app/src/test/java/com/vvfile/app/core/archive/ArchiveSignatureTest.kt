package com.vvfile.app.core.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 按字节认容器：ZIP / RAR / 7z，以及前面挂着 PE stub 的自解压包。
 *
 * 自解压包的签名不在文件开头，所以这里既测「开头就能认」，也测「扫到 2 MiB 以内能认」。
 */
class ArchiveSignatureTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/archives/$name") ?: error("缺少测试样本 $name")
        val target = temp.newFile(name)
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun sfx(name: String, payload: ByteArray, stub: Int = 4096): File {
        val target = temp.newFile(name)
        target.outputStream().use { out ->
            out.write("MZ".toByteArray())
            out.write(ByteArray(stub))
            out.write(payload)
        }
        return target
    }

    @Test
    fun `开头的签名直接认`() {
        assertEquals(ArchiveSignature.Container.RAR, ArchiveSignature.of(fixture("rar5.rar")))
        assertEquals(ArchiveSignature.Container.ZIP, ArchiveSignature.of(fixture("aes.zip")))
        assertEquals(ArchiveSignature.Container.SEVEN_Z, ArchiveSignature.of(fixture("plain.7z")))
    }

    @Test
    fun `自解压包能扫出归档体`() {
        val rar = fixture("rar5.rar").readBytes()
        assertEquals(ArchiveSignature.Container.RAR, ArchiveSignature.of(sfx("a.rar.exe", rar)))

        val zip = ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { z ->
                z.putNextEntry(ZipEntry("a.txt"))
                z.write("hello".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        assertEquals(ArchiveSignature.Container.ZIP, ArchiveSignature.of(sfx("a.zip.exe", zip)))
    }

    @Test
    fun `普通文件和普通程序返回 null`() {
        val text = temp.newFile("note.txt").apply { writeText("plain text") }
        assertNull(ArchiveSignature.of(text))

        val program = temp.newFile("tool.exe").apply {
            outputStream().use { out ->
                out.write("MZ".toByteArray())
                out.write(ByteArray(2048) { (it % 251).toByte() })
            }
        }
        assertNull(ArchiveSignature.of(program))
    }

    @Test
    fun `空文件不抛异常`() {
        assertNull(ArchiveSignature.of(temp.newFile("empty.bin")))
    }
}
