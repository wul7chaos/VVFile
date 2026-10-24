package com.vvfile.app.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandHistoryTest {

    @Test
    fun `record trims and ignores blank commands`() {
        val history = CommandHistory(capacity = 10)

        history.record("  ls -la  ")
        history.record("   ")
        history.record("")

        assertEquals(1, history.size)
        assertEquals("ls -la", history.get(0))
    }

    @Test
    fun `oldest entries are evicted beyond capacity`() {
        val history = CommandHistory(capacity = 3)

        history.record("a")
        history.record("b")
        history.record("c")
        history.record("d")

        assertEquals(3, history.size)
        assertEquals("b", history.get(0))
        assertEquals("d", history.get(2))
    }

    @Test
    fun `up starts from the latest entry and stops at the oldest`() {
        val history = CommandHistory(capacity = 10)
        history.record("a")
        history.record("b")

        val latest = history.upIndex(-1)
        assertEquals(1, latest)
        assertEquals("b", history.get(latest))

        val previous = history.upIndex(latest)
        assertEquals(0, previous)
        assertEquals("a", history.get(previous))

        assertEquals(0, history.upIndex(previous))
    }

    @Test
    fun `down moves forward and returns to the draft position`() {
        val history = CommandHistory(capacity = 10)
        history.record("a")
        history.record("b")

        assertEquals(1, history.downIndex(0))
        assertEquals(-1, history.downIndex(1))
        assertEquals(-1, history.downIndex(-1))
    }

    @Test
    fun `empty history stays at draft position`() {
        val history = CommandHistory(capacity = 10)

        assertEquals(-1, history.upIndex(-1))
        assertEquals(-1, history.downIndex(-1))
        assertNull(history.get(0))
    }

    @Test
    fun `duplicate commands are kept in order`() {
        val history = CommandHistory(capacity = 10)
        history.record("ls")
        history.record("ls")

        assertEquals(2, history.size)
        assertEquals("ls", history.get(1))
    }

    @Test
    fun `clear empties history`() {
        val history = CommandHistory(capacity = 10)
        history.record("ls")

        history.clear()

        assertEquals(0, history.size)
        assertNull(history.get(0))
    }
}
