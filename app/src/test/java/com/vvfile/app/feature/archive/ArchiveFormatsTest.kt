package com.vvfile.app.feature.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TAR / TAR.GZ 支持：列表、按条目解出、以及压缩包类型判定。
 *
 * tar 是流式格式，测试里手写头块（512 字节对齐）造样本，避免引入第三方打包库。
 */
class ArchiveFormatsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `扩展名决定压缩包类型`() {
        assertEquals(ArchiveKind.ZIP, ArchiveFormats.kindOf("/sdcard/a.zip"))
        assertEquals(ArchiveKind.ZIP, ArchiveFormats.kindOf("/sdcard/base.apk"))
        assertEquals(ArchiveKind.ZIP, ArchiveFormats.kindOf("/sdcard/lib.jar"))
        assertEquals(ArchiveKind.ZIP, ArchiveFormats.kindOf("/sdcard/bundle.apks"))
        assertEquals(ArchiveKind.TAR, ArchiveFormats.kindOf("/sdcard/a.tar"))
        assertEquals(ArchiveKind.TAR_GZ, ArchiveFormats.kindOf("/sdcard/a.tar.gz"))
        assertEquals(ArchiveKind.TAR_GZ, ArchiveFormats.kindOf("/sdcard/a.TGZ"))
        assertNull(ArchiveFormats.kindOf("/sdcard/a.txt"))
        assertNull(ArchiveFormats.kindOf("/sdcard/a.tar.xz"))
        assertFalse(ArchiveFormats.isArchive("/sdcard/readme.md"))
    }

    @Test
    fun `tar 能列出多层目录`() {
        val file = writeTar("sample.tar", gzipped = false) { tar ->
            tar.file("readme.txt", "hello")
            tar.dir("docs")
            tar.file("docs/guide.md", "# guide")
            tar.dir("docs/img")
            tar.file("docs/img/pic.png", "png")
        }

        val entries = ArchiveIndex.read(file.absolutePath)
        assertEquals(
            listOf("readme.txt", "docs/", "docs/guide.md", "docs/img/", "docs/img/pic.png"),
            entries.map { it.name },
        )
        assertEquals("hello".length.toLong(), entries.first().size)

        val root = ArchiveIndex.children(entries, "")
        assertEquals(listOf("docs/", "readme.txt"), root.map { it.name })
        val docs = ArchiveIndex.children(entries, "docs")
        assertEquals(listOf("docs/img/", "docs/guide.md"), docs.map { it.name })
    }

    @Test
    fun `tar gz 与 tar 结果一致`() {
        val tar = writeTar("sample.tar", gzipped = false) { it.file("a.txt", "aaaa") }
        val tgz = writeTar("sample.tar.gz", gzipped = true) { it.file("a.txt", "aaaa") }

        assertEquals(
            ArchiveIndex.read(tar.absolutePath).map { it.name },
            ArchiveIndex.read(tgz.absolutePath).map { it.name },
        )
    }

    @Test
    fun `tar 里单个条目能解出来`() {
        val file = writeTar("sample.tar", gzipped = false) { tar ->
            tar.file("docs/guide.md", "# guide")
            tar.file("big.bin", "x".repeat(1500))
        }

        val target = ArchiveIndex.extractTo(
            path = file.absolutePath,
            entryName = "docs/guide.md",
            targetDir = temp.newFolder("out"),
        )
        assertNotNull(target)
        assertEquals("# guide", target!!.readText())
    }

    @Test
    fun `跨块的文件解压后字节数不变`() {
        val payload = "y".repeat(5000)
        val file = writeTar("big.tar", gzipped = false) { it.file("big.bin", payload) }

        val target = ArchiveIndex.extractTo(
            path = file.absolutePath,
            entryName = "big.bin",
            targetDir = temp.newFolder("out"),
        )
        assertEquals(payload.length.toLong(), target!!.length())
        assertEquals(payload, target.readText())
    }

    @Test
    fun `tar gz 里的条目也能解出来`() {
        val file = writeTar("sample.tgz", gzipped = true) { it.file("note.txt", "压缩包里的笔记") }

        val target = ArchiveIndex.extractTo(
            path = file.absolutePath,
            entryName = "note.txt",
            targetDir = temp.newFolder("out"),
        )
        assertEquals("压缩包里的笔记", target!!.readText())
    }

    @Test
    fun `不存在的条目返回 null`() {
        val file = writeTar("sample.tar", gzipped = false) { it.file("a.txt", "a") }
        assertNull(
            ArchiveIndex.extractTo(
                path = file.absolutePath,
                entryName = "missing.txt",
                targetDir = temp.newFolder("out"),
            ),
        )
    }

    @Test
    fun `GNU 长文件名通过 L 头还原`() {
        val longName = "very/" + "deep/".repeat(20) + "file.txt"
        val file = writeTar("long.tar", gzipped = false) { tar ->
            tar.longName(longName)
            tar.file("truncated-name.txt", "content")
        }

        val entries = ArchiveIndex.read(file.absolutePath)
        assertTrue(entries.any { it.name == longName })
    }

    // ---- 测试用的最小 tar 打包器 ----

    private fun writeTar(name: String, gzipped: Boolean, build: (TarBuilder) -> Unit): File {
        val builder = TarBuilder()
        build(builder)
        val raw = builder.build()
        val file = temp.newFile(name)
        if (gzipped) {
            GZIPOutputStream(file.outputStream()).use { out ->
                out.write(raw)
            }
        } else {
            file.writeBytes(raw)
        }
        return file
    }

    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun file(name: String, content: String) = add(name, content.toByteArray(Charsets.UTF_8), '0')

        fun dir(name: String) = add(name.trimEnd('/') + "/", ByteArray(0), '5')

        /** GNU 长文件名：`L` 头 + 下一项被截断的名字。 */
        fun longName(name: String) {
            val payload = name.toByteArray(Charsets.UTF_8) + 0
            add("././@LongLink", payload, 'L')
        }

        private fun add(name: String, data: ByteArray, type: Char) {
            val header = ByteArray(512)
            fun put(text: String, offset: Int) {
                val bytes = text.toByteArray(Charsets.US_ASCII)
                System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, 512 - offset))
            }
            put(name.take(99), 0)
            put("0000644\u0000", 100)
            put("0000000\u0000", 108)
            put("0000000\u0000", 116)
            put(data.size.toString(8).padStart(11, '0') + "\u0000", 124)
            put("00000000000\u0000", 136)
            header[156] = type.code.toByte()
            put("ustar\u0000", 257)
            put("00", 263)

            // 校验和：先把校验和字段填空格，再按字节求和
            for (index in 148 until 156) header[index] = ' '.code.toByte()
            var checksum = 0
            for (byte in header) checksum += byte.toInt() and 0xFF
            put(checksum.toString(8).padStart(6, '0') + "\u0000 ", 148)

            out.write(header)
            if (data.isNotEmpty()) {
                out.write(data)
                val padding = (512 - data.size % 512) % 512
                if (padding > 0) out.write(ByteArray(padding))
            }
        }

        fun build(): ByteArray {
            out.write(ByteArray(1024)) // 结束标记：两个全零块
            return out.toByteArray()
        }
    }
}
