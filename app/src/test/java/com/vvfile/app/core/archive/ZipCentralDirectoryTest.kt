package com.vvfile.app.core.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ZIP 加密标记探测。
 *
 * 造样本的方式：先用 `ZipOutputStream` 写一个正常包，再把某个条目在**中央目录**里的
 * 通用标志位 bit0 打开——真做密码压缩需要第三方库，而这里要验的只是「读不读得出这个位」。
 */
class ZipCentralDirectoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg names: String): File {
        val file = temp.newFile("sample.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("content of $name".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    /** 把 [target] 条目在中央目录里的「加密」位打开。 */
    private fun markEncrypted(file: File, target: String) {
        val bytes = file.readBytes()
        val nameBytes = target.toByteArray(Charsets.UTF_8)
        var index = 0
        while (index <= bytes.size - 4) {
            val signature = (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                ((bytes[index + 3].toInt() and 0xFF) shl 24)
            if (signature == 0x02014b50) {
                val nameStart = index + 46
                val matches = bytes.size >= nameStart + nameBytes.size &&
                    (nameBytes.indices).all { bytes[nameStart + it] == nameBytes[it] }
                if (matches) {
                    val flags = (bytes[index + 8].toInt() and 0xFF) or
                        ((bytes[index + 9].toInt() and 0xFF) shl 8)
                    val patched = flags or 0x1
                    bytes[index + 8] = (patched and 0xFF).toByte()
                    bytes[index + 9] = ((patched shr 8) and 0xFF).toByte()
                    file.writeBytes(bytes)
                    return
                }
            }
            index++
        }
        throw IllegalArgumentException("包里没有 $target")
    }

    @Test
    fun `普通 zip 没有加密条目`() {
        val file = zip("a.txt", "b.txt")
        assertTrue(ZipCentralDirectory.encryptedNames(file).isEmpty())
        assertFalse(ZipCentralDirectory.hasEncryptedEntry(file))
    }

    @Test
    fun `能读出被标记为加密的条目`() {
        val file = zip("a.txt", "secret.txt")
        markEncrypted(file, "secret.txt")

        assertEquals(setOf("secret.txt"), ZipCentralDirectory.encryptedNames(file))
        assertTrue(ZipCentralDirectory.hasEncryptedEntry(file))
    }

    @Test
    fun `不是 zip 的文件返回空集`() {
        val file = temp.newFile("plain.txt").apply { writeText("not a zip") }
        assertTrue(ZipCentralDirectory.encryptedNames(file).isEmpty())
    }

    @Test
    fun `空文件不会抛异常`() {
        val file = temp.newFile("empty.zip")
        assertTrue(ZipCentralDirectory.encryptedNames(file).isEmpty())
    }

    @Test
    fun `中央目录能读出条目名与大小`() {
        val file = zip("a.txt", "docs/b.md")
        val entries = ZipCentralDirectory.read(file)

        assertNotNull(entries)
        assertEquals(listOf("a.txt", "docs/b.md"), entries!!.map { it.name })
        assertTrue(entries.all { it.size > 0L && !it.encrypted })
        assertTrue(entries.none { it.isDirectory })
    }

    @Test
    fun `加密标记跟着条目走`() {
        val file = zip("a.txt", "secret.txt")
        markEncrypted(file, "secret.txt")

        val entries = ZipCentralDirectory.read(file)!!
        assertTrue(entries.first { it.name == "secret.txt" }.encrypted)
        assertFalse(entries.first { it.name == "a.txt" }.encrypted)
    }
}
