package com.vvfile.app.feature.browser

import com.vvfile.app.feature.archive.ArchiveEntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗格内压缩包浏览的纯数据部分：当前这一层列什么、能不能再往上退。
 *
 * 界面上的压缩包视图和目录视图共用同一套列表渲染，分层聚合交给 [ArchiveView]，
 * 所以这层逻辑可以脱开 Android 直接测。
 */
class ArchiveViewTest {

    private val entries = listOf(
        ArchiveEntryInfo("docs/", isDirectory = true, size = 0, modifiedAt = 0),
        ArchiveEntryInfo("docs/b.txt", isDirectory = false, size = 500, modifiedAt = 0),
        ArchiveEntryInfo("a.txt", isDirectory = false, size = 17, modifiedAt = 0, encrypted = true),
    )

    private fun view(dir: String = "") = ArchiveView(
        path = "/sdcard/Download/a.zip",
        name = "a.zip",
        entries = entries,
        currentDir = dir,
    )

    @Test
    fun `包根只列出第一层，目录在前`() {
        assertEquals(listOf("docs/", "a.txt"), view().visibleEntries.map { it.name })
    }

    @Test
    fun `进子目录只列该层条目`() {
        assertEquals(listOf("docs/b.txt"), view("docs").visibleEntries.map { it.name })
    }

    @Test
    fun `不存在的目录列出来是空的`() {
        assertTrue(view("nope").visibleEntries.isEmpty())
    }

    @Test
    fun `包根不能再往上退，子目录可以`() {
        assertFalse(view().canGoUp)
        assertTrue(view("docs").canGoUp)
    }

    @Test
    fun `还没读出来时不显示任何条目`() {
        val loading = ArchiveView(path = "/sdcard/Download/a.zip", name = "a.zip")
        assertTrue(loading.visibleEntries.isEmpty())
        assertTrue(loading.loading)
    }
}
