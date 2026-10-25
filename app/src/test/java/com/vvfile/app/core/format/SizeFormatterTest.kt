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

    @Test
    fun `compact form matches MT app list`() {
        assertEquals("512 B", SizeFormatter.formatCompact(512))
        assertEquals("1.5K", SizeFormatter.formatCompact(1536))
        assertEquals("23.14M", SizeFormatter.formatCompact((23.14 * 1024 * 1024).toLong()))
        assertEquals("1.20G", SizeFormatter.formatCompact((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("-", SizeFormatter.formatCompact(-1))
    }
}
