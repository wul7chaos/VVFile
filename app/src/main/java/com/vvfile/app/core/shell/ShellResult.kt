package com.vvfile.app.core.shell

/**
 * 一次 shell / root 命令的执行结果。
 *
 * 约定（见 docs/02 §5）：
 * - 输出总量上限由 [ShellExecutor] 控制，超出时 [truncated] 为 true；
 * - [timedOut] 为 true 时进程已被销毁，[exitCode] 为 -1；
 * - [started] 为 false 表示命令进程根本没起来（如设备上没有 `su`），
 *   此时 [exitCode] 同样为 -1，调用方应据此区分「未 root」与「授权失败」。
 *
 * 仅描述数据，不包含任何提权通道逻辑。
 */
data class ShellResult(
    val command: String,
    val asRoot: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val started: Boolean = true,
) {
    /** 进程正常结束且退出码为 0。超时、启动失败都不算成功。 */
    val isSuccess: Boolean
        get() = started && !timedOut && exitCode == 0

    /** 便于日志/错误提示的合并输出；stdout 在前，stderr 在后。 */
    val combinedOutput: String
        get() = when {
            stdout.isBlank() -> stderr
            stderr.isBlank() -> stdout
            else -> stdout.trimEnd() + "\n" + stderr
        }
}
