package com.vvfile.app.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPromptTest {

    @Test
    fun `默认提示符是根目录加美元符`() {
        assertEquals(":/ $", TerminalUiState().prompt)
    }

    @Test
    fun `提示符跟随当前目录`() {
        assertEquals(":/sdcard $", TerminalUiState(cwd = "/sdcard").prompt)
        assertEquals(
            ":/storage/emulated/0 $",
            TerminalUiState(cwd = "/storage/emulated/0").prompt,
        )
    }

    @Test
    fun `root 会话用井号`() {
        assertEquals(
            ":/sdcard #",
            TerminalUiState(cwd = "/sdcard", sessionType = TerminalSessionType.ROOT).prompt,
        )
    }
}
