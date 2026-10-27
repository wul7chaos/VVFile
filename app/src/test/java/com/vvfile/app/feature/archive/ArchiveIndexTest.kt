package com.vvfile.app.feature.archive

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

class ArchiveIndexTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(name: String, size: Long = 0L, dir: Boolean = false) =
        ArchiveEntryInfo(name, dir || name.endsWith("/"), size, 0L)

    private fun sampleZip(): File {
        val file = temp.newFile("sample.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(name: String, text: String? = null) {
                zip.putNextEntry(ZipEntry(name))
                if (text != null) zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add("readme.txt", "hello")
            add("docs/")
            add("docs/guide.md", "# guide")
            add("docs/img/")
            add("docs/img/pic.png", "png")
            add("empty.txt", "")
        }
        return file
    }

    @Test
    fun `根层把深层路径聚合成目录`() {
        val entries = listOf(
            entry("readme.txt", 5),
            entry("docs/"),
            entry("docs/guide.md", 7),
            entry("docs/img/"),
            entry("docs/img/pic.png", 3),
        )
        val root = ArchiveIndex.children(entries, "")
        assertEquals(listOf("docs/", "readme.txt"), root.map { it.name })
        assertTrue(root.first().isDirectory)
        assertEquals(0L, root.first().size)
    }

    @Test
    fun `进入子目录只列下一层`() {
        val entries = listOf(
            entry("docs/"),
            entry("docs/guide.md", 7),
            entry("docs/img/"),
            entry("docs/img/pic.png", 3),
        )
        val inside = ArchiveIndex.children(entries, "docs")
        assertEquals(listOf("docs/guide.md", "docs/img/"), inside.map { it.name }.sorted())
        assertEquals(listOf("guide.md", "img"), inside.map { it.displayName }.sorted())
    }

    @Test
    fun `没有显式目录条目也能显示出目录`() {
        // 有些压缩工具不写目录项，只写完整文件路径
        val entries = listOf(entry("a/b/c.txt", 1))
        assertEquals(listOf("a/"), ArchiveIndex.children(entries, "").map { it.name })
        assertEquals(listOf("a/b/"), ArchiveIndex.children(entries, "a").map { it.name })
        assertEquals(listOf("a/b/c.txt"), ArchiveIndex.children(entries, "a/b").map { it.name })
    }

    @Test
    fun `目录排在文件前面且按名字排序`() {
        val entries = listOf(
            entry("z.txt"),
            entry("b/"),
            entry("a.txt"),
            entry("c/"),
        )
        val names = ArchiveIndex.children(entries, "").map { it.name }
        assertEquals(listOf("b/", "c/", "a.txt", "z.txt"), names)
    }

    @Test
    fun `parentOf 逐层回退`() {
        assertEquals("a/b", ArchiveIndex.parentOf("a/b/c.txt"))
        assertEquals("a", ArchiveIndex.parentOf("a/b"))
        assertEquals("", ArchiveIndex.parentOf("a"))
        assertEquals("", ArchiveIndex.parentOf(""))
    }

    @Test
    fun `读取真实压缩包`() {
        val entries = ArchiveIndex.read(sampleZip().absolutePath)
        assertTrue(entries.any { it.name == "readme.txt" && it.size == 5L })
        assertTrue(entries.any { it.name == "docs/" && it.isDirectory })
        assertEquals("readme.txt", entries.first { it.name == "readme.txt" }.displayName)
    }

    @Test
    fun `解压单个条目到目标目录`() {
        val zip = sampleZip()
        val out = temp.newFolder("out")
        val extracted = ArchiveIndex.extractTo(zip.absolutePath, "docs/guide.md", out)
        assertNotNull(extracted)
        assertEquals("guide.md", extracted!!.name)
        assertEquals("# guide", extracted.readText())
    }

    @Test
    fun `解压时只取最后一段避免路径穿越`() {
        val file = temp.newFile("evil.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../../evil.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val out = temp.newFolder("out2")
        val extracted = ArchiveIndex.extractTo(file.absolutePath, "../../evil.txt", out)
        assertNotNull(extracted)
        // 落在目标目录里，名字被压成最后一段
        assertEquals(out.absolutePath, extracted!!.parentFile?.absolutePath)
        assertEquals("evil.txt", extracted.name)
    }

    @Test
    fun `目录条目不会被解压成文件`() {
        val zip = sampleZip()
        val out = temp.newFolder("out3")
        assertEquals(null, ArchiveIndex.extractTo(zip.absolutePath, "docs/", out))
        assertFalse(File(out, "docs").exists())
    }

    @Test
    fun `损坏的压缩包读取时抛异常`() {
        val broken = temp.newFile("broken.zip")
        broken.writeText("not a zip")
        var failed = false
        runCatching { ArchiveIndex.read(broken.absolutePath) }.onFailure { failed = true }
        assertTrue(failed)
    }
}
