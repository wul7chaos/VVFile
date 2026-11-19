package com.vvfile.app.feature.terminal

/**
 * 从别处「在终端里执行」的请求。
 *
 * 场景：在文件列表里点了「执行」脚本。以前是把输出抓成字符串塞进对话框，长输出的脚本
 * 看起来像卡死，`read` 这类交互更是没法用。现在改成把命令**打进终端**——输出实时刷，
 * 需要输入时直接敲，Ctrl+C 也能中断。
 *
 * 交接方式：调用方先 [request]，再把终端页面显示出来；`TerminalViewModel` 在建立会话时
 * 取走并清空。进程内静态持有即可，请求只在一次跳转里有效。
 */
object TerminalLaunch {

    /** 一次待执行的命令。[command] 是已经拼好、可直接回车的一整行。 */
    data class Request(
        val command: String,
        val cwd: String,
    )

    @Volatile
    private var pending: Request? = null

    fun request(command: String, cwd: String) {
        pending = Request(command, cwd)
    }

    /** 看一眼待执行的请求，不清空（建会话时要用它的 cwd）。 */
    fun peek(): Request? = pending

    /** 取走待执行的请求（取完即清空）。 */
    fun consume(): Request? {
        val current = pending ?: return null
        pending = null
        return current
    }

    /** 丢弃未处理的请求（用户从别处进终端时不该执行旧命令）。 */
    fun clear() {
        pending = null
    }
}

