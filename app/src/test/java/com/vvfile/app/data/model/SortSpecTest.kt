package com.vvfile.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SortSpecTest {

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long = 0,
        modified: Long = 0,
    ) = FileEntry(
        name = name,
        path = "/tmp/$name",
        isDirectory = directory,
        size = size,
        lastModified = modified,
    )

    @Test
    fun `directories always come first`() {
        val list = listOf(entry("a.txt"), entry("B", directory = true), entry("z.zip"))
        val sorted = list.sortedWith(SortSpec(SortField.NAME, ascending = true).comparator())
        assertEquals(listOf("B", "a.txt", "z.zip"), sorted.map { it.name })
    }

    @Test
    fun `name sorting is case insensitive`() {
        val list = listOf(entry("charlie"), entry("Alpha"), entry("bravo"))
        val sorted = list.sortedWith(SortSpec(SortField.NAME, ascending = true).comparator())
        assertEquals(listOf("Alpha", "bravo", "charlie"), sorted.map { it.name })
    }

    @Test
    fun `size descending keeps directories first`() {
        val list = listOf(
            entry("small.bin", size = 10),
            entry("big.bin", size = 1000),
            entry("dir", directory = true),
        )
        val sorted = list.sortedWith(SortSpec(SortField.SIZE, ascending = false).comparator())
        assertEquals(listOf("dir", "big.bin", "small.bin"), sorted.map { it.name })
    }

    @Test
    fun `type sorting groups by extension then name`() {
        val list = listOf(entry("b.zip"), entry("a.txt"), entry("a.zip"))
        val sorted = list.sortedWith(SortSpec(SortField.TYPE, ascending = true).comparator())
        assertEquals(listOf("a.txt", "a.zip", "b.zip"), sorted.map { it.name })
    }
}
