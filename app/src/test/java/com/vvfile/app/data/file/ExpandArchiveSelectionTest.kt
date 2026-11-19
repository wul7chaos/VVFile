package com.vvfile.app.data.file

import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandArchiveSelectionTest {

    private data class Entry(val name: String)

    private fun filter(entries: List<String>, wanted: List<String>?) =
        ExpandArchiveSelection.filter(entries.map(::Entry), wanted) { it.name }.map { it.name }

    @Test
    fun `null 或空选择表示整包`() {
        val all = listOf("a.txt", "b.txt")
        assertEquals(all, filter(all, null))
        assertEquals(all, filter(all, emptyList()))
        assertEquals(all, filter(all, listOf("", "/")))
    }

    @Test
    fun `只解选中的单个文件`() {
        val all = listOf("a.txt", "b.txt")
        assertEquals(listOf("b.txt"), filter(all, listOf("b.txt")))
    }

    @Test
    fun `选中目录会带出它下面的所有条目`() {
        val all = listOf("a.txt", "docs/", "docs/x.txt", "docs/deep/y.txt", "docs2/z.txt")
        assertEquals(
            listOf("docs/", "docs/x.txt", "docs/deep/y.txt"),
            filter(all, listOf("docs")),
        )
    }

    @Test
    fun `目录名是前缀但不吞相邻的同名前缀目录`() {
        val all = listOf("docs/a.txt", "docs2/b.txt")
        assertEquals(listOf("docs/a.txt"), filter(all, listOf("docs")))
    }

    @Test
    fun `包内绝对路径被当成相对路径处理`() {
        val all = listOf("a.txt", "b.txt")
        assertEquals(listOf("a.txt"), filter(all, listOf("/a.txt")))
        assertEquals(listOf("a.txt"), filter(all, listOf("a.txt/")))
    }
}
