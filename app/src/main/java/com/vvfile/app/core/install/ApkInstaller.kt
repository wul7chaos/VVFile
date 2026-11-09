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
 *
 * **共享存储里的包要先搬到 `/data/local/tmp`**：`/storage/emulated/0` 是 FUSE 挂载，
 * 文件标签是 `u:object_r:fuse:s0`，而 system_server 跑在 `u:r:system_server:s0`，
 * 读不了这份文件——`pm install` 会直接报
 * `System server has no access to read file context ... Consider using a file under /data/local/tmp/`。
 * 所以安装前用 root 复制一份过去，装完删掉。
 */
object ApkInstaller {

    const val INSTALL_TIMEOUT_MS: Long = 300_000L

    /** 复制大包可能慢一些，给足时间。 */
    private const val STAGE_TIMEOUT_MS: Long = 180_000L
    private const val CLEANUP_TIMEOUT_MS: Long = 60_000L

    private const val PM_FALLBACK = "/system/bin/pm"
    private const val STAGE_DIR = "/data/local/tmp"
    private const val STAGE_PREFIX = "vvfile-install-"

    private const val STAGE_FAILED = "安装包没法复制到 /data/local/tmp（root 未授权、空间不足或只读）"

    /** 扩展名判断；大小写不敏感。 */
    fun isApkFile(path: String): Boolean = path.endsWith(".apk", ignoreCase = true)

    /**
     * 路径是否落在 FUSE 共享存储上（系统服务读不了这些位置）。
     *
     * `/data/...`、`/system/...` 这类系统服务本来就能读，不必复制。
     */
    fun needsStaging(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard/") || path.startsWith("/mnt/")

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
        var staged: String? = null
        try {
            var target = path
            if (needsStaging(path)) {
                staged = stage(path, runner) ?: return InstallResult(success = false, message = STAGE_FAILED)
                target = staged
            }

            var result = install(target, runner, timeoutMs)
            // 少数 ROM 上 /data 里的包也会被拒；那种情况同样搬一次再来
            if (staged == null && unreadablePackage(result)) {
                staged = stage(path, runner) ?: return InstallResult(success = false, message = STAGE_FAILED)
                result = install(staged, runner, timeoutMs)
            }
            return parse(result)
        } finally {
            staged?.let { cleanup(it, runner) }
        }
    }

    /** `pm install`；root 会话的 PATH 里未必有 pm，缺了就换绝对路径再来一次。 */
    private suspend fun install(
        target: String,
        runner: CommandRunner,
        timeoutMs: Long,
    ): ShellResult {
        var result = runner.exec(buildCommand(target), asRoot = true, timeoutMs = timeoutMs)
        if (pmMissing(result)) {
            result = runner.exec(
                buildCommand(target, pmBinary = PM_FALLBACK),
                asRoot = true,
                timeoutMs = timeoutMs,
            )
        }
        return result
    }

    /** 复制到 `/data/local/tmp` 并放开读权限，装完由调用方删掉。 */
    private suspend fun stage(path: String, runner: CommandRunner): String? {
        val target = "$STAGE_DIR/$STAGE_PREFIX${System.currentTimeMillis()}.apk"
        val command = "cp ${ShellExecutor.shellQuote(path)} ${ShellExecutor.shellQuote(target)}" +
            " && chmod 644 ${ShellExecutor.shellQuote(target)}"
        val result = runner.exec(command, asRoot = true, timeoutMs = STAGE_TIMEOUT_MS)
        return if (result.isSuccess) target else null
    }

    private suspend fun cleanup(staged: String, runner: CommandRunner) {
        runCatching {
            runner.exec(
                "rm -f ${ShellExecutor.shellQuote(staged)}",
                asRoot = true,
                timeoutMs = CLEANUP_TIMEOUT_MS,
            )
        }
    }

    /** system_server 打不开安装包时的报错特征。 */
    fun unreadablePackage(result: ShellResult): Boolean {
        val text = result.combinedOutput.lowercase()
        return text.contains("no access to read file") ||
            text.contains("can't open file") ||
            text.contains("cannot open file") ||
            text.contains("unable to open file")
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
