package com.vvfile.app.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

class SizeFormatterTest {

    @Test
    fun `bytes below one kilobyte keep integer form`() {
        assertEquals("0 B", SizeFormatter.format(0))
        assertEquals("1023 B", SizeFormatter.format(1023))
    }

    @Test
    fun `kilobytes and megabytes use one decimal`() {
        assertEquals("1.0 KB", SizeFormatter.format(1024))
        assertEquals("1.5 KB", SizeFormatter.format(1536))
        assertEquals("1.0 MB", SizeFormatter.format(1024L * 1024))
        assertEquals("2.5 GB", SizeFormatter.format((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `large values drop decimals`() {
        assertEquals("100 MB", SizeFormatter.format(100L * 1024 * 1024))
    }
}
