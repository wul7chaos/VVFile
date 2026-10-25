package com.vvfile.app.feature.terminal

import java.io.File

/**
 * 一个 PTY 子进程的启动参数。
 *
 * 这些参数原样交给 `JNI.createSubprocess()`：`fork()` 之后子进程 `chdir(cwd)`、
 * `clearenv()` 再逐个 `putenv()`，最后 `execvp(command, args)`。
 *
 * 关键点：这里 exec 的是**系统分区**里的 `/system/bin/sh`，不是 App 数据目录里的可写文件，
 * 所以 targetSdk 36 的 W^X 限制（Android 10+ 禁止 exec 应用私有目录下的可执行文件）不适用，
 * 不需要像 Termux 那样把 targetSdk 钉在 28。
 */
data class PtyShellSpec(
    val command: String,
    val cwd: String,
    val args: Array<String>,
    val env: Array<String>,
    /** 回滚历史行数。 */
    val transcriptRows: Int = 5000,
)

object PtyShell {

    /** Android 自带的 mksh；系统分区、永不过期、不需要 bootstrap。 */
    const val SHELL = "/system/bin/sh"

    /**
     * 与上一版假终端保持一致的 PATH。
     *
     * 不包含任何 App 私有目录：既避免 exec 私有目录里的东西，也避免污染用户的 `which` 结果。
     */
    const val SYSTEM_PATH = "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin"

    /**
     * MT 风格的提示符 `:/storage/emulated/0 $`。
     *
     * mksh 在每次打印提示符前都会重新展开 PS1 里的 `${PWD}`，`\$` 在 root 下变成 `#`、
     * 普通用户下是 `$`，所以不用我们参与，`su` 之后提示符自动变成 `#`。
     *
     * 注意必须用 `sh`（非 login）启动：login shell 会读 `/etc/mkshrc` 把 PS1 覆盖成
     * `shell@host:/path $`。
     */
    val PS1: String = ":" + "\${PWD}" + " \\$ "

    /** 启动脚本文件名，落在 `$HOME` 下，通过 `ENV` 交给交互式 mksh source。 */
    const val RC_NAME = ".vvfile_env"

    /**
     * 启动脚本内容。
     *
     * 1. `PS1`：MT 风格提示符。写在这里而不是只靠环境变量，是因为 `su` 之后
     *    root shell 可能不继承环境，但会继承 `$ENV`（KernelSU / Magisk 都保留）。
     * 2. `sudo`：Android 没有 sudo 二进制，用 mksh 函数补一个，语义与 MT 一致
     *    （只让这一条命令以 root 执行，会话身份不变）。
     *
     * 注意：这是**被 source 的脚本**，不是被 exec 的可执行文件，所以不受 W^X 限制。
     */
    val RC: String = listOf(
        "# VVFile 终端启动脚本（交互式 mksh 通过 \$ENV 加载）",
        "PS1=':${'$'}{PWD} \\${'$'} '",
        "sudo() {",
        "  if [ ${'$'}# -eq 0 ]; then",
        "    echo '用法：sudo <命令>' >&2",
        "    return 1",
        "  fi",
        "  su -c \"${'$'}*\"",
        "}",
        "",
    ).joinToString("\n")

    /**
     * 把 [RC] 写进 [dir]，返回绝对路径。
     *
     * 不需要可执行权限：mksh 只读它。
     */
    fun writeRc(dir: File): String {
        runCatching { dir.mkdirs() }
        val file = File(dir, RC_NAME)
        runCatching {
            if (!file.exists() || file.readText() != RC) {
                file.writeText(RC)
            }
        }
        return file.absolutePath
    }

    /**
     * 构造普通用户会话。
     *
     * @param home    `$HOME`，同时作为 `~` 的展开目标。
     * @param tmpDir  `$TMPDIR`，mksh / 常见 CLI 会用到。
     * @param cwd     初始工作目录；不存在时调用方应回退到 [home]。
     * @param rcFile  [writeRc] 的返回值；为 null 时不设 `$ENV`。
     */
    fun spec(home: String, tmpDir: String, cwd: String = home, rcFile: String? = null): PtyShellSpec {
        val workDir = if (cwd.isNotBlank() && File(cwd).isDirectory) cwd else home
        val env = mutableListOf(
            "HOME=$home",
            "PWD=$workDir",
            "TMPDIR=$tmpDir",
            "PATH=$SYSTEM_PATH",
            "SHELL=$SHELL",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=en_US.UTF-8",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "PS1=$PS1",
        )
        if (!rcFile.isNullOrBlank()) env += "ENV=$rcFile"
        return PtyShellSpec(
            command = SHELL,
            cwd = workDir,
            // 不带 -l：见 PS1 的说明。
            args = emptyArray(),
            env = env.toTypedArray(),
            transcriptRows = 5000,
        )
    }
}

/**
 * 判断一行文本是不是「干净的提示符」。
 *
 * @return `true` 表示 root（`#`），`false` 表示普通用户（`$`），`null` 表示这行不是提示符。
 *
 * 只认行尾的 `#` / `$`，所以 `:/sdcard # echo hi` 这类「提示符 + 已输入命令」不算，
 * 光标的行在命令敲下去之前一定是纯提示符。
 */
internal fun rootFromPromptLine(line: String): Boolean? {
    val match = PROMPT_TAIL.find(line) ?: return null
    return when (match.groupValues[1]) {
        "#" -> true
        "$" -> false
        else -> null
    }
}

/** `:/some/path #` / `:/some/path $`，允许行尾有空白。 */
private val PROMPT_TAIL = Regex("^:.*\\s([#$])\\s*$")
