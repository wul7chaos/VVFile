package com.vvfile.app.core.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellListingParserTest {

    private val listing = """
        total 24
        drwxr-xr-x 2 root root 4096 2024-05-01 10:20 data
        -rw-r--r-- 1 root root 123 2024-05-01 10:21 build.prop
        -rw-r--r-- 1 root root 0 2024-05-01 10:22 .hidden
        drwxr-xr-x 3 root root 4096 2024-05-01 10:23 中文 目录
        lrwxrwxrwx 1 root root 8 2024-05-01 10:24 sdcard -> /storage/self/primary
        crw-rw-rw- 1 root root 1 3 1970-01-01 08:00 null
    """.trimIndent()

    @Test
    fun `解析目录 文件 中文 符号链接并过滤隐藏`() {
        val entries = ShellListingParser.parseList(listing, "/system", showHidden = false)
        assertEquals(
            listOf("data", "build.prop", "中文 目录", "sdcard"),
            entries.map { it.name },
        )
        assertTrue(entries[0].isDirectory)
        assertEquals("/system/data", entries[0].path)
        assertEquals(123L, entries[1].size)
        assertEquals("/system/中文 目录", entries[2].path)
        assertTrue(entries[2].isDirectory)
        assertTrue(entries[3].isSymlink)
        assertEquals("sdcard", entries[3].name)
    }

    @Test
    fun `showHidden 为 true 时保留点文件`() {
        val entries = ShellListingParser.parseList(listing, "/system", showHidden = true)
        assertTrue(entries.any { it.name == ".hidden" })
    }

    @Test
    fun `根目录拼接不产生双斜杠`() {
        val entries = ShellListingParser.parseList(
            "-rw-r--r-- 1 root root 5 2024-05-01 10:21 default.prop",
            "/",
            showHidden = false,
        )
        assertEquals("/default.prop", entries.single().path)
    }

    @Test
    fun `无法识别的行被丢弃`() {
        val entries = ShellListingParser.parseList("garbage line\n\ntotal 0", "/x", showHidden = false)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `stat 解析普通文件`() {
        val entry = ShellListingParser.parseStat("regular file|2048|1714550000", "/data/test.bin")
        assertEquals("test.bin", entry?.name)
        assertEquals(2048L, entry?.size)
        assertFalse(entry?.isDirectory ?: true)
        assertEquals(1714550000000L, entry?.lastModified)
    }

    @Test
    fun `stat 解析目录与符号链接`() {
        val dir = ShellListingParser.parseStat("directory|4096|1714550000", "/data")
        assertTrue(dir?.isDirectory ?: false)

        val link = ShellListingParser.parseStat("symbolic link|9|1714550000", "/data/link")
        assertTrue(link?.isSymlink ?: false)
    }

    @Test
    fun `stat 输出非法时返回 null`() {
        assertNull(ShellListingParser.parseStat("", "/data"))
        assertNull(ShellListingParser.parseStat("not-a-stat-line", "/data"))
    }
}
