package com.vvfile.app.feature.browser

import com.vvfile.app.data.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤与选择的语义：过滤只影响「看得见什么」，全选/反选都必须以可见集合为准。
 *
 * 背景：`selectAll` 以前用的是 `entries`（整个目录），过滤出几个文件后点全选会把
 * 被过滤掉的也选中——列表看不见、却计入「已选 N 项」，复制/删除随之误伤。
 */
class BrowserPaneFilterTest {

    private fun file(name: String, dir: Boolean = false): FileEntry = FileEntry(
        name = name,
        path = "/storage/emulated/0/books/$name",
        isDirectory = dir,
        size = if (dir) 0L else 1024L,
        lastModified = 0L,
    )

    private val entries = listOf(
        file("青云仙传.txt"),
        file("金钱兑换系统.txt"),
        file("古风图片.zip"),
        file("封面.jpg"),
        file("书签", dir = true),
    )

    @Test
    fun `没有过滤时可见集合就是全部条目`() {
        val pane = PaneState(entries = entries)
        assertEquals(entries.size, pane.visibleEntries.size)
    }

    @Test
    fun `按扩展名过滤后可见集合只剩匹配项`() {
        val pane = PaneState(entries = entries, filterExtensions = "txt")
        assertEquals(2, pane.visibleEntries.size)
        assertTrue(pane.visibleEntries.all { it.extension == "txt" })
    }

    @Test
    fun `按关键字过滤忽略大小写`() {
        val pane = PaneState(entries = entries, filterQuery = "JPG")
        assertEquals(listOf("封面.jpg"), pane.visibleEntries.map { it.name })
    }

    @Test
    fun `关键字与扩展名同时生效`() {
        val pane = PaneState(entries = entries, filterQuery = "系统", filterExtensions = "txt")
        assertEquals(listOf("金钱兑换系统.txt"), pane.visibleEntries.map { it.name })
    }

    /** 全选读的就是 selectAllTargets，这里直接盯住它。 */
    @Test
    fun `过滤后全选只覆盖可见条目`() {
        val pane = PaneState(entries = entries, filterExtensions = "txt")
        val targets = pane.selectAllTargets
        assertEquals(2, targets.size)
        assertTrue(targets.all { it.endsWith(".txt") })
        // 被过滤掉的两个 zip 和那个目录不能进来
        assertEquals(entries.size - 2, entries.count { it.path !in targets })
    }

    @Test
    fun `没有过滤时全选等于全部条目`() {
        val pane = PaneState(entries = entries)
        assertEquals(entries.map { it.path }.toSet(), pane.selectAllTargets)
    }

    @Test
    fun `过滤没人匹配时全选为空`() {
        val pane = PaneState(entries = entries, filterQuery = "不存在的名字")
        assertTrue(pane.selectAllTargets.isEmpty())
    }

    @Test
    fun `过滤不改动 entries 本身`() {
        val pane = PaneState(entries = entries, filterExtensions = "zip")
        assertEquals(1, pane.visibleEntries.size)
        assertEquals(entries.size, pane.entries.size)
    }
}



