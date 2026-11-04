package com.vvfile.app.core.transfer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹「原样发送」的摊平规则：相对路径要带上文件夹名、空目录要单独留一条。
 */
class PcOutboxTest {

    private fun tempDir(): File = Files.createTempDirectory("vvfile-outbox").toFile()

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `单个文件就是文件名本身`() {
        val dir = tempDir()
        val file = File(dir, "a.txt").apply { writeText("x") }
        val items = PcOutbox.expand(listOf(file.absolutePath))
        assertEquals(1, items.size)
        val item = items.first() as PcOutbox.Item.FileItem
        assertEquals("a.txt", item.relativePath)
        assertEquals("a.txt", item.name)
        assertEquals(1L, PcOutbox.totalBytes(items))
    }

    @Test
    fun `文件夹里的文件带上级目录，空目录也留一条`() {
        val dir = tempDir()
        val root = File(dir, "照片").apply { mkdirs() }
        write(File(root, "2024/a.jpg"), "aaa")
        write(File(root, "2024/冬/b.jpg"), "bb")
        write(File(root, "readme.txt"), "c")
        File(root, "空目录").mkdirs()

        val items = PcOutbox.expand(listOf(root.absolutePath))
        val paths = items.map { it.relativePath }

        assertTrue(paths.contains("照片"))
        assertTrue(paths.contains("照片/2024"))
        assertTrue(paths.contains("照片/2024/冬"))
        assertTrue(paths.contains("照片/空目录"))
        assertTrue(paths.contains("照片/2024/a.jpg"))
        assertTrue(paths.contains("照片/2024/冬/b.jpg"))
        assertTrue(paths.contains("照片/readme.txt"))

        // 目录排在文件前面，保证对方先建好再写
        val firstFile = items.indexOfFirst { it is PcOutbox.Item.FileItem }
        val lastDir = items.indexOfLast { it is PcOutbox.Item.DirItem }
        assertTrue(lastDir < firstFile)

        // 只统计文件字节：3 + 2 + 1
        assertEquals(6L, PcOutbox.totalBytes(items))
    }

    @Test
    fun `多选文件夹加文件一起摊平`() {
        val dir = tempDir()
        val folder = File(dir, "docs").apply { mkdirs() }
        write(File(folder, "a.md"), "12")
        val single = File(dir, "note.txt").apply { writeText("345") }

        val items = PcOutbox.expand(listOf(folder.absolutePath, single.absolutePath))
        val paths = items.map { it.relativePath }
        assertTrue(paths.contains("docs/a.md"))
        assertTrue(paths.contains("note.txt"))
        assertEquals(5L, PcOutbox.totalBytes(items))
    }

    @Test
    fun `不存在的路径直接跳过`() {
        val dir = tempDir()
        val items = PcOutbox.expand(listOf(File(dir, "nope.txt").absolutePath))
        assertTrue(items.isEmpty())
    }
}
