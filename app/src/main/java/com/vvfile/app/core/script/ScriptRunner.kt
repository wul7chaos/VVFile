package com.vvfile.app.core.script

import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.RootAccess
import com.vvfile.app.core.shell.ShellExecutor
import com.vvfile.app.core.shell.ShellResult
import java.io.File

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
    /** 这次到底是不是用 `su` 跑的。 */
    val asRoot: Boolean = false,
    /**
     * 用户选的是系统环境，但普通权限够不着（`/data/adb` 这类 700 的 root 目录，
     * 或者 SELinux 拦着），于是自动改用 ROOT 执行了。
     */
    val escalated: Boolean = false,
)

/**
 * 执行 `.sh` 脚本。
 *
 * 命令固定为 `cd <目录> && chmod <权限> <文件名> && sh <文件名>`：
 * 先切目录，保证脚本里的相对路径按所在目录解析；chmod 只给文件本身，
 * 不去动目录，避免误伤。
 *
 * 权限上分两道闸：
 * 1. 打开时就能看出来够不着的（目录 `canExecute` 为 false），直接走 `su`，省一次必然失败的往返；
 * 2. 只有真跑一次才知道的（能被 stat、执行时被 SELinux 拦），失败且报权限问题时提权重跑一次。
 * root 策略禁用（`RootPolicy.DISABLED`）时两道都不触发。
 */
class ScriptRunner(
    private val runner: CommandRunner = ShellExecutor(),
    /** 是否允许提权；默认跟随全局 root 策略，测试可注入。 */
    private val rootAvailable: () -> Boolean = { RootAccess.allowed() },
    /** 普通权限能否进到脚本所在目录；默认问文件系统。 */
    private val reachable: (String) -> Boolean = ::canReachAsSystem,
) {

    suspend fun run(
        path: String,
        env: ScriptEnv,
        permission: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ScriptRunResult {
        val command = buildCommand(path, permission)
        val rootAllowed = rootAvailable()
        var asRoot = env == ScriptEnv.ROOT
        var escalated = false

        if (!asRoot && rootAllowed && !reachable(path)) {
            asRoot = true
            escalated = true
        }

        var result = runner.exec(command = command, asRoot = asRoot, timeoutMs = timeoutMs)

        if (!asRoot && !result.isSuccess && rootAllowed && looksLikePermissionDenied(result)) {
            val retried = runner.exec(command = command, asRoot = true, timeoutMs = timeoutMs)
            // su 没起来（未授权/没有 su）就别用它的空结果盖掉第一次的报错，原样留着更好排查
            if (retried.started && !retried.timedOut) {
                result = retried
                escalated = true
            }
        }

        return ScriptRunResult(
            command = command,
            exitCode = result.exitCode,
            output = result.combinedOutput.trim(),
            success = result.isSuccess,
            asRoot = result.asRoot,
            escalated = escalated,
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

        /**
         * 普通权限能不能进到脚本所在目录。
         *
         * `/data/adb`、`/data/data/<别的应用>` 这类目录对应用 uid 是 `canExecute = false`，
         * 提前问一句比「跑一次、报 Permission denied、再提权重跑」快。
         */
        fun canReachAsSystem(path: String): Boolean {
            val file = File(path)
            val dir = file.parentFile ?: return false
            return runCatching { dir.canExecute() && file.canRead() }.getOrDefault(false)
        }

        /** 输出看着像权限问题（权限位不够、SELinux 拒绝、不可执行）。 */
        fun looksLikePermissionDenied(result: ShellResult): Boolean {
            val text = result.combinedOutput.lowercase()
            return text.contains("permission denied") ||
                text.contains("operation not permitted") ||
                text.contains("not permitted") ||
                result.exitCode == 126
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
