package com.vvfile.app.feature.terminal

/** 终端页面状态。 */
data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val sessionType: TerminalSessionType = TerminalSessionType.SH,
    val running: Boolean = false,
    val error: String? = null,
    val historyIndex: Int = -1,
    /** 当前目录（由会话里的哨兵回传），用于拼 `:/path $` 提示符。 */
    val cwd: String? = null,
) {
    /** MT 风格的提示符：`:/storage/emulated/0 $`（root 会话是 `#`）。 */
    val prompt: String
        get() = ":" + (cwd ?: "/") + " " + if (sessionType == TerminalSessionType.ROOT) "#" else "$"
}
