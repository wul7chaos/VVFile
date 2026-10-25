package com.vvfile.app.feature.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PtyShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `提示符是 MT 风格的冒号加 PWD`() {
        assertEquals(":" + "\${PWD}" + " " + "\\$ ", PtyShell.PS1)
        assertEquals(":${'$'}{PWD} \\${'$'} ", PtyShell.PS1)
    }

    @Test
    fun `exec 的是系统分区的 sh 而不是应用私有目录`() {
        val spec = PtyShell.spec(home = tmp.root.absolutePath, tmpDir = tmp.root.absolutePath)
        assertEquals("/system/bin/sh", spec.command)
        assertTrue(PtyShell.SHELL.startsWith("/system/"))
        // 不带 -l：login shell 会读 /etc/mkshrc 把 PS1 覆盖掉。
        assertEquals(0, spec.args.size)
    }

    @Test
    fun `PATH 只含系统目录`() {
        val spec = PtyShell.spec(home = tmp.root.absolutePath, tmpDir = tmp.root.absolutePath)
        val path = env(spec, "PATH").orEmpty()
        assertEquals(PtyShell.SYSTEM_PATH, path)
        assertFalse("PATH 不能包含应用私有目录", path.contains("/data/"))
        assertTrue(path.contains("/system/bin"))
    }

    @Test
    fun `环境变量覆盖 shell 需要的全部关键项`() {
        val home = tmp.newFolder("home").absolutePath
        val cache = tmp.newFolder("cache").absolutePath
        val spec = PtyShell.spec(home = home, tmpDir = cache)

        assertEquals(home, env(spec, "HOME"))
        assertEquals(home, env(spec, "PWD"))
        assertEquals(cache, env(spec, "TMPDIR"))
        assertEquals(PtyShell.SHELL, env(spec, "SHELL"))
        assertEquals("xterm-256color", env(spec, "TERM"))
        assertEquals(PtyShell.PS1, env(spec, "PS1"))
    }

    @Test
    fun `cwd 不存在时回退到 HOME`() {
        val home = tmp.newFolder("home").absolutePath
        val spec = PtyShell.spec(
            home = home,
            tmpDir = home,
            cwd = File(tmp.root, "does-not-exist").absolutePath,
        )
        assertEquals(home, spec.cwd)
        assertEquals(home, env(spec, "PWD"))
    }

    @Test
    fun `cwd 存在时按传入值使用`() {
        val home = tmp.newFolder("home").absolutePath
        val work = tmp.newFolder("work").absolutePath
        val spec = PtyShell.spec(home = home, tmpDir = home, cwd = work)
        assertEquals(work, spec.cwd)
        assertEquals(work, env(spec, "PWD"))
    }

    @Test
    fun `保留足够的回滚历史`() {
        val spec = PtyShell.spec(home = tmp.root.absolutePath, tmpDir = tmp.root.absolutePath)
        assertEquals(5000, spec.transcriptRows)
    }

    @Test
    fun `启动脚本设置 MT 提示符并补回 sudo`() {
        val rc = PtyShell.RC
        assertTrue(rc.contains("PS1=':" + "\${PWD}" + " \\$ '"))
        assertTrue(rc.contains("sudo()"))
        assertTrue(rc.contains("su -c"))
        // 脚本会被 source 而不是 exec，所以不需要 shebang。
        assertFalse(rc.startsWith("#!"))
    }

    @Test
    fun `启动脚本写进 HOME 并通过 ENV 交给 shell`() {
        val home = tmp.newFolder("home")
        val rcPath = PtyShell.writeRc(home)
        assertEquals(File(home, PtyShell.RC_NAME).absolutePath, rcPath)
        assertEquals(PtyShell.RC, File(rcPath).readText())

        val spec = PtyShell.spec(
            home = home.absolutePath,
            tmpDir = home.absolutePath,
            rcFile = rcPath,
        )
        assertEquals(rcPath, env(spec, "ENV"))
    }

    @Test
    fun `没有启动脚本时不设 ENV`() {
        val spec = PtyShell.spec(home = tmp.root.absolutePath, tmpDir = tmp.root.absolutePath)
        assertEquals(null, env(spec, "ENV"))
    }

    @Test
    fun `顶栏状态跟随运行中与 root`() {
        assertEquals("root", TerminalUiState(running = true, root = true).subtitle)
        assertEquals("", TerminalUiState(running = true, root = false).subtitle)
        assertEquals("已结束", TerminalUiState(running = false).subtitle)
        assertEquals("已结束（1）", TerminalUiState(running = false, exitStatus = 1).subtitle)
    }

    @Test
    fun `从提示符行识别 root`() {
        assertEquals(true, rootFromPromptLine(":/sdcard # "))
        assertEquals(false, rootFromPromptLine(":/sdcard $ "))
        assertEquals(true, rootFromPromptLine(":/ #"))
        assertEquals(false, rootFromPromptLine(":/storage/emulated/0 $"))
    }

    @Test
    fun `带命令的行不当作提示符`() {
        // 光标在这些行上时不能改判定，否则 `echo #` 会被当成 root。
        assertEquals(null, rootFromPromptLine(":/sdcard # echo hi"))
        assertEquals(null, rootFromPromptLine(":/sdcard $ ls -l"))
        assertEquals(null, rootFromPromptLine("Alarms  b1.xml"))
        assertEquals(null, rootFromPromptLine(""))
        assertEquals(null, rootFromPromptLine("/sdcard # "))
    }

    private fun env(spec: PtyShellSpec, key: String): String? =
        spec.env.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
}
