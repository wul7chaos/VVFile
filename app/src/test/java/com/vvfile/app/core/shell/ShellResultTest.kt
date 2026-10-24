package com.vvfile.app.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellResultTest {

    private fun result(
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        truncated: Boolean = false,
        started: Boolean = true,
    ) = ShellResult(
        command = "id -u",
        asRoot = true,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        timedOut = timedOut,
        truncated = truncated,
        started = started,
    )

    @Test
    fun `zero exit code means success`() {
        assertTrue(result(exitCode = 0).isSuccess)
    }

    @Test
    fun `non zero exit code is failure`() {
        assertFalse(result(exitCode = 1).isSuccess)
        assertFalse(result(exitCode = 127).isSuccess)
    }

    @Test
    fun `timed out is failure even with zero exit code`() {
        assertFalse(result(exitCode = 0, timedOut = true).isSuccess)
    }

    @Test
    fun `process that never started is failure`() {
        assertFalse(result(exitCode = -1, started = false).isSuccess)
    }

    @Test
    fun `truncation flag does not change success semantics`() {
        val truncated = result(exitCode = 0, stdout = "x", truncated = true)

        assertTrue(truncated.isSuccess)
        assertTrue(truncated.truncated)
    }

    @Test
    fun `defaults keep backward compatible flags`() {
        val minimal = ShellResult(
            command = "ls",
            asRoot = false,
            exitCode = 0,
            stdout = "a",
            stderr = "",
        )

        assertFalse(minimal.timedOut)
        assertFalse(minimal.truncated)
        assertTrue(minimal.started)
        assertTrue(minimal.isSuccess)
    }

    @Test
    fun `combined output prefers stdout then stderr`() {
        assertEquals("out\nerr", result(stdout = "out", stderr = "err").combinedOutput)
        assertEquals("out", result(stdout = "out", stderr = "").combinedOutput)
        assertEquals("err", result(stdout = "", stderr = "err").combinedOutput)
        assertEquals("", result().combinedOutput)
    }

    @Test
    fun `combined output trims trailing newline before appending stderr`() {
        assertEquals("out\nerr", result(stdout = "out\n", stderr = "err").combinedOutput)
    }
}
