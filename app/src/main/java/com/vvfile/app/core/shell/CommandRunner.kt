package com.vvfile.app.core.shell

/**
 * 命令执行抽象，便于对 root 文件通道做单元测试。
 * 生产实现为 [ShellExecutor]，测试可注入返回固定 [ShellResult] 的假实现。
 */
interface CommandRunner {
    suspend fun exec(
        command: String,
        asRoot: Boolean = false,
        timeoutMs: Long = ShellExecutor.DEFAULT_TIMEOUT_MS,
    ): ShellResult
}
