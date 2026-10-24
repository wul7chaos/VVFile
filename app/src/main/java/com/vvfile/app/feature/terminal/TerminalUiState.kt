package com.vvfile.app.feature.terminal

/** 终端页面状态。 */
data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val sessionType: TerminalSessionType = TerminalSessionType.SH,
    val running: Boolean = false,
    val error: String? = null,
    val historyIndex: Int = -1,
)
