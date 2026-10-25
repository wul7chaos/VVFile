package com.vvfile.app.feature.terminal

/**
 * 终端页面状态。
 *
 * 提示符不再由 App 拼：真 PTY 之后提示符是 shell 的 PS1（见 [PtyShell.PS1]），
 * 屏幕内容是 VT 仿真的结果，所以这里只剩「会话本身」的信息。
 */
data class TerminalUiState(
    /** 会话进程是否还活着。 */
    val running: Boolean = false,
    /** 当前 shell 是否已经是 uid 0。 */
    val root: Boolean = false,
    /** 进程退出码；仅在 [running] 为 false 时有意义。 */
    val exitStatus: Int? = null,
    /** OSC 0/2 设置的窗口标题。 */
    val title: String? = null,
    /** 会话级错误（PTY 创建失败等）。 */
    val error: String? = null,
) {
    /** 顶栏右边那行小字。 */
    val subtitle: String
        get() = when {
            !running && exitStatus != null -> "已结束（$exitStatus）"
            !running -> "已结束"
            root -> "root"
            else -> ""
        }
}
