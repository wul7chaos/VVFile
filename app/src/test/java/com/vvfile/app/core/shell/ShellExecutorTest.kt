package com.vvfile.app.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测试纯逻辑（引号转义 / 输出预算），不在 Windows 上真的执行 sh/su。
 */
class ShellExecutorTest {

    @Test
    fun `quotes plain path without adding escapes`() {
        assertEquals("'/storage/emulated/0/Download'", ShellExecutor.shellQuote("/storage/emulated/0/Download"))
    }

    @Test
    fun `quotes empty string`() {
        assertEquals("''", ShellExecutor.shellQuote(""))
    }

    @Test
    fun `escapes a single quote`() {
        assertEquals("'it'\\''s'", ShellExecutor.shellQuote("it's"))
    }

    @Test
    fun `escapes every single quote`() {
        assertEquals("'a'\\''b'\\''c'", ShellExecutor.shellQuote("a'b'c"))
    }

    @Test
    fun `keeps newline and tab inside the quoted value`() {
        assertEquals("'line1\nline2\tend'", ShellExecutor.shellQuote("line1\nline2\tend"))
    }

    @Test
    fun `neutralizes shell metacharacters by quoting`() {
        val value = "\$HOME; rm -rf / | `id` && echo \"x\""
        val quoted = ShellExecutor.shellQuote(value)

        assertEquals("'$value'", quoted)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
    }

    @Test
    fun `double quotes are ordinary characters inside single quotes`() {
        assertEquals("'say \"hi\"'", ShellExecutor.shellQuote("say \"hi\""))
    }

    @Test
    fun `output budget allows exactly the limit without truncation`() {
        val budget = OutputBudget(4)

        assertEquals(2, budget.take(2))
        assertEquals(2, budget.take(2))
        assertFalse(budget.truncated)
    }

    @Test
    fun `output budget is shared and truncates beyond the limit`() {
        val budget = OutputBudget(4)

        assertEquals(3, budget.take(3))
        assertEquals(1, budget.take(3))
        assertTrue(budget.truncated)
    }

    @Test
    fun `output budget stays truncated after being exhausted`() {
        val budget = OutputBudget(2)

        budget.take(5)
        assertTrue(budget.truncated)
        assertEquals(0, budget.take(1))
        assertTrue(budget.truncated)
    }

    @Test
    fun `zero length read does not count as truncation`() {
        val budget = OutputBudget(1)

        assertEquals(0, budget.take(0))
        assertFalse(budget.truncated)
        assertEquals(1, budget.take(1))
        assertFalse(budget.truncated)
    }

    @Test
    fun `default output limit is one megabyte`() {
        assertEquals(1024 * 1024, ShellExecutor.DEFAULT_MAX_OUTPUT_BYTES)
    }
}
