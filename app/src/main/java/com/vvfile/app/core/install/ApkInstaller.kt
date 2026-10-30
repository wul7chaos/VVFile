package com.vvfile.app.core.install

import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellExecutor
import com.vvfile.app.core.shell.ShellResult

/** 一次安装的结果；[message] 直接可展示。 */
data class InstallResult(
    val success: Boolean,
    val message: String,
    /** 原始输出，排查失败原因时有用。 */
    val raw: String = "",
)

/**
 * APK 安装：有 root 时走 `pm install`，绕开系统安装器（免点确认、能装系统目录里的包）；
 * 没 root 时由调用方交给系统安装器。
 *
 * 只处理单 APK；拆分包（`.apks` / `.xapk`）需要 `pm install-create/write/commit`，
 * 这里不做。
 */
object ApkInstaller {

    const val INSTALL_TIMEOUT_MS: Long = 300_000L

    private const val PM_FALLBACK = "/system/bin/pm"

    /** 扩展名判断；大小写不敏感。 */
    fun isApkFile(path: String): Boolean = path.endsWith(".apk", ignoreCase = true)

    /**
     * 拼安装命令。
     *
     * `-r` 覆盖安装并保留数据；不带 `-d`（降级）和 `-g`（授予全部运行时权限），
     * 那是用户明确要的才该加的开关。路径一律单引号转义。
     */
    fun buildCommand(path: String, pmBinary: String = "pm"): String =
        "$pmBinary install -r ${ShellExecutor.shellQuote(path)}"

    /** 把 `pm install` 的输出翻译成结果；`Success` 之外的都算失败并带上原因。 */
    fun parse(result: ShellResult): InstallResult {
        val text = result.combinedOutput.trim()
        val success = result.isSuccess && text.lineSequence().any { it.trim() == SUCCESS }
        if (success) {
            return InstallResult(success = true, message = "安装成功", raw = text)
        }
        return InstallResult(
            success = false,
            message = failureMessage(text, result.exitCode),
            raw = text,
        )
    }

    suspend fun installWithRoot(
        path: String,
        runner: CommandRunner = ShellExecutor(),
        timeoutMs: Long = INSTALL_TIMEOUT_MS,
    ): InstallResult {
        var result = runner.exec(buildCommand(path), asRoot = true, timeoutMs = timeoutMs)
        // root 会话的 PATH 里未必有 pm：换绝对路径再来一次
        if (pmMissing(result)) {
            result = runner.exec(
                buildCommand(path, pmBinary = PM_FALLBACK),
                asRoot = true,
                timeoutMs = timeoutMs,
            )
        }
        return parse(result)
    }

    private fun pmMissing(result: ShellResult): Boolean {
        if (!result.started) return true
        val text = result.combinedOutput.lowercase()
        return result.exitCode == 127 ||
            text.contains("pm: not found") ||
            text.contains("pm: inaccessible")
    }

    private fun failureMessage(text: String, exitCode: Int): String {
        val failure = Regex("Failure \\[([^]]+)]\\s*(?::\\s*(.*))?").find(text)
        if (failure != null) {
            val code = failure.groupValues[1].trim()
            val detail = failure.groupValues[2].trim()
            return if (detail.isBlank()) code else "$code：$detail"
        }
        return when {
            text.isBlank() -> "安装失败（exit $exitCode）"
            else -> text.lineSequence().last { it.isNotBlank() }.trim()
        }
    }

    private const val SUCCESS = "Success"
}
