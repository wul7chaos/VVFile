package com.vvfile.app.core.script

import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellExecutor

/** 脚本执行环境；按需求只提供系统（sh）与 root（su）两种，不做扩展包环境。 */
enum class ScriptEnv(val label: String) {
    SYSTEM("系统环境"),
    ROOT("ROOT 权限"),
}

/** 记忆上次选择，进程内有效即可（不写 DataStore，避免为一个开关加迁移）。 */
object ScriptExecutionPrefs {
    var env: ScriptEnv = ScriptEnv.SYSTEM
    var permission: String = ScriptRunner.DEFAULT_PERMISSION
}

data class ScriptRunResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val success: Boolean,
)

/**
 * 执行 `.sh` 脚本。
 *
 * 命令固定为 `cd <目录> && chmod <权限> <文件名> && sh <文件名>`：
 * 先切目录，保证脚本里的相对路径按所在目录解析；chmod 只给文件本身，
 * 不去动目录，避免误伤。
 */
class ScriptRunner(
    private val runner: CommandRunner = ShellExecutor(),
) {

    suspend fun run(
        path: String,
        env: ScriptEnv,
        permission: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ScriptRunResult {
        val command = buildCommand(path, permission)
        val result = runner.exec(
            command = command,
            asRoot = env == ScriptEnv.ROOT,
            timeoutMs = timeoutMs,
        )
        return ScriptRunResult(
            command = command,
            exitCode = result.exitCode,
            output = result.combinedOutput.trim(),
            success = result.isSuccess,
        )
    }

    companion object {
        const val DEFAULT_PERMISSION: String = "755"
        const val DEFAULT_TIMEOUT_MS: Long = 60_000L

        /** 常用执行权限，与 MT 的属性页一致。 */
        val PERMISSIONS: List<String> = listOf("777", "755", "555", "644", "444")

        /** 归一化权限串：只保留 0-7，长度不对时退回默认值。 */
        fun normalizePermission(raw: String): String {
            val digits = raw.filter { it in '0'..'7' }
            return if (digits.length == 3) digits else DEFAULT_PERMISSION
        }

        /** 拼出执行命令；目录与文件名都经过单引号转义。 */
        fun buildCommand(path: String, permission: String): String {
            val normalized = path.trim()
            val dir = normalized.substringBeforeLast('/', "")
            val name = normalized.substringAfterLast('/')
            val mode = normalizePermission(permission)
            val prefix = if (dir.isEmpty()) "" else "cd ${ShellExecutor.shellQuote(dir)} && "
            return prefix +
                "chmod $mode ${ShellExecutor.shellQuote(name)} && " +
                "sh ${ShellExecutor.shellQuote(name)}"
        }
    }
}
