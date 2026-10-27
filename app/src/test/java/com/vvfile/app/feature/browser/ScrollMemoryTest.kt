package com.vvfile.app.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollMemoryTest {

    @Test
    fun `positions are remembered per pane and path`() {
        val memory = ScrollMemory()
        memory.remember(PaneId.LEFT, "/sdcard", 12, 40)
        memory.remember(PaneId.RIGHT, "/sdcard", 3, 0)

        assertEquals(ScrollMemory.Position(12, 40), memory.position(PaneId.LEFT, "/sdcard"))
        assertEquals(ScrollMemory.Position(3, 0), memory.position(PaneId.RIGHT, "/sdcard"))
        assertNull(memory.position(PaneId.LEFT, "/sdcard/Download"))
    }

    @Test
    fun `a later visit overwrites the earlier position`() {
        val memory = ScrollMemory()
        memory.remember(PaneId.LEFT, "/sdcard", 12, 40)
        memory.remember(PaneId.LEFT, "/sdcard", 30, 8)

        assertEquals(ScrollMemory.Position(30, 8), memory.position(PaneId.LEFT, "/sdcard"))
        assertEquals(1, memory.size)
    }

    @Test
    fun `stopping at the top drops the record instead of storing zero`() {
        val memory = ScrollMemory()
        memory.remember(PaneId.LEFT, "/sdcard", 12, 40)
        memory.remember(PaneId.LEFT, "/sdcard", 0, 0)

        assertNull(memory.position(PaneId.LEFT, "/sdcard"))
        assertEquals(0, memory.size)
    }

    @Test
    fun `blank path is ignored`() {
        val memory = ScrollMemory()
        memory.remember(PaneId.LEFT, "", 5, 5)

        assertEquals(0, memory.size)
        assertNull(memory.position(PaneId.LEFT, ""))
    }

    @Test
    fun `oldest directory is evicted once the limit is reached`() {
        val memory = ScrollMemory(limit = 2)
        memory.remember(PaneId.LEFT, "/a", 1, 0)
        memory.remember(PaneId.LEFT, "/b", 2, 0)
        memory.remember(PaneId.LEFT, "/c", 3, 0)

        assertEquals(2, memory.size)
        assertNull(memory.position(PaneId.LEFT, "/a"))
        assertEquals(ScrollMemory.Position(3, 0), memory.position(PaneId.LEFT, "/c"))
    }

    @Test
    fun `recently used directory survives eviction`() {
        val memory = ScrollMemory(limit = 2)
        memory.remember(PaneId.LEFT, "/a", 1, 0)
        memory.remember(PaneId.LEFT, "/b", 2, 0)
        // 读一次 /a，把它挪到"最近使用"
        memory.position(PaneId.LEFT, "/a")
        memory.remember(PaneId.LEFT, "/c", 3, 0)

        assertEquals(ScrollMemory.Position(1, 0), memory.position(PaneId.LEFT, "/a"))
        assertNull(memory.position(PaneId.LEFT, "/b"))
    }

    @Test
    fun `clearPane keeps the other pane untouched`() {
        val memory = ScrollMemory()
        memory.remember(PaneId.LEFT, "/sdcard", 7, 0)
        memory.remember(PaneId.RIGHT, "/sdcard", 9, 0)

        memory.clearPane(PaneId.LEFT)

        assertNull(memory.position(PaneId.LEFT, "/sdcard"))
        assertEquals(ScrollMemory.Position(9, 0), memory.position(PaneId.RIGHT, "/sdcard"))
    }

    @Test
    fun `clear drops everything`() {
        val memory = ScrollMemory()
        memory.remember(PaneId.LEFT, "/a", 1, 1)
        memory.remember(PaneId.RIGHT, "/b", 2, 2)

        memory.clear()

        assertEquals(0, memory.size)
    }
}
