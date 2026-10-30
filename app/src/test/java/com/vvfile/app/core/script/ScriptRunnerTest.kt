package com.vvfile.app.core.script

import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerTest {

    /** 记录每次调用用的是不是 root，并按 asRoot 返回预设结果。 */
    private class FakeRunner(
        private val respond: (asRoot: Boolean, callIndex: Int) -> ShellResult,
    ) : CommandRunner {
        val rootCalls = mutableListOf<Boolean>()

        override suspend fun exec(
            command: String,
            asRoot: Boolean,
            timeoutMs: Long,
        ): ShellResult {
            rootCalls += asRoot
            return respond(asRoot, rootCalls.lastIndex)
        }
    }

    private fun ok(asRoot: Boolean, output: String = "done") = ShellResult(
        command = "",
        asRoot = asRoot,
        exitCode = 0,
        stdout = output,
        stderr = "",
    )

    private fun denied(asRoot: Boolean) = ShellResult(
        command = "",
        asRoot = asRoot,
        exitCode = 2,
        stdout = "",
        stderr = "sh: cd: /data/adb/sjzesp: Permission denied",
    )

    private fun runner(
        runner: CommandRunner,
        rootAvailable: Boolean = true,
        reachable: Boolean = true,
    ) = ScriptRunner(
        runner = runner,
        rootAvailable = { rootAvailable },
        reachable = { reachable },
    )

    @Test
    fun `普通权限进不去目录时直接用 root 执行`() = runBlocking {
        val fake = FakeRunner { asRoot, _ -> ok(asRoot, "rooted") }
        val result = runner(fake, reachable = false)
            .run("/data/adb/sjzesp/start.sh", ScriptEnv.SYSTEM, "777")

        assertEquals(listOf(true), fake.rootCalls)
        assertTrue(result.success)
        assertTrue(result.asRoot)
        assertTrue(result.escalated)
    }

    @Test
    fun `系统环境报权限问题时提权重跑一次`() = runBlocking {
        val fake = FakeRunner { asRoot, index ->
            if (index == 0) denied(asRoot) else ok(asRoot, "rooted")
        }
        val result = runner(fake)
            .run("/data/adb/sjzesp/start.sh", ScriptEnv.SYSTEM, "777")

        assertEquals(listOf(false, true), fake.rootCalls)
        assertTrue(result.success)
        assertTrue(result.escalated)
    }

    @Test
    fun `普通权限够得着就不动 root`() = runBlocking {
        val fake = FakeRunner { asRoot, _ -> ok(asRoot) }
        val result = runner(fake)
            .run("/storage/emulated/0/VVFile/temp/a.sh", ScriptEnv.SYSTEM, "755")

        assertEquals(listOf(false), fake.rootCalls)
        assertFalse(result.asRoot)
        assertFalse(result.escalated)
    }

    @Test
    fun `root 策略禁用时失败也不提权`() = runBlocking {
        val fake = FakeRunner { asRoot, _ -> denied(asRoot) }
        val result = runner(fake, rootAvailable = false, reachable = false)
            .run("/data/adb/sjzesp/start.sh", ScriptEnv.SYSTEM, "777")

        assertEquals(listOf(false), fake.rootCalls)
        assertFalse(result.success)
        assertFalse(result.escalated)
        assertTrue(result.output.contains("Permission denied"))
    }

    @Test
    fun `选了 root 就直接走 su，不算自动提权`() = runBlocking {
        val fake = FakeRunner { asRoot, _ -> ok(asRoot) }
        val result = runner(fake)
            .run("/data/adb/sjzesp/start.sh", ScriptEnv.ROOT, "777")

        assertEquals(listOf(true), fake.rootCalls)
        assertTrue(result.asRoot)
        assertFalse(result.escalated)
    }

    @Test
    fun `su 起不来时保留第一次的报错`() = runBlocking {
        val fake = FakeRunner { _, index ->
            if (index == 0) {
                denied(false)
            } else {
                ShellResult(
                    command = "",
                    asRoot = true,
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    started = false,
                )
            }
        }
        val result = runner(fake)
            .run("/data/adb/sjzesp/start.sh", ScriptEnv.SYSTEM, "777")

        assertFalse(result.success)
        assertFalse(result.escalated)
        assertTrue(result.output.contains("Permission denied"))
    }

    @Test
    fun `权限问题的判定覆盖常见报错`() {
        assertTrue(ScriptRunner.looksLikePermissionDenied(denied(false)))
        assertTrue(
            ScriptRunner.looksLikePermissionDenied(
                ShellResult("", false, 1, "", "sh: ./a.sh: Operation not permitted"),
            ),
        )
        assertTrue(ScriptRunner.looksLikePermissionDenied(ShellResult("", false, 126, "", "")))
        assertFalse(
            ScriptRunner.looksLikePermissionDenied(
                ShellResult("", false, 1, "", "sh: a.sh: not found"),
            ),
        )
    }

    @Test
    fun `命令包含切目录、chmod 与 sh 执行`() {
        val command = ScriptRunner.buildCommand("/storage/emulated/0/VVFile/temp/a.sh", "777")

        assertEquals(
            "cd '/storage/emulated/0/VVFile/temp' && chmod 777 'a.sh' && sh 'a.sh'",
            command,
        )
    }

    @Test
    fun `文件名含空格与引号会被转义`() {
        val command = ScriptRunner.buildCommand("/sdcard/tmp/a b's.sh", "755")

        assertTrue(command.startsWith("cd '/sdcard/tmp' && chmod 755 "))
        assertTrue(command.contains("'a b'\\''s.sh'"))
    }

    @Test
    fun `没有目录前缀时只跑当前目录`() {
        assertEquals("chmod 755 'a.sh' && sh 'a.sh'", ScriptRunner.buildCommand("a.sh", "755"))
    }

    @Test
    fun `非法权限退回默认值`() {
        assertEquals("755", ScriptRunner.normalizePermission("abc"))
        assertEquals("755", ScriptRunner.normalizePermission("77"))
        assertEquals("777", ScriptRunner.normalizePermission("777"))
        assertEquals("555", ScriptRunner.normalizePermission("5x5x5"))
    }

    @Test
    fun `常用权限包含 777 与 555`() {
        assertTrue("777" in ScriptRunner.PERMISSIONS)
        assertTrue("555" in ScriptRunner.PERMISSIONS)
        assertTrue("755" in ScriptRunner.PERMISSIONS)
    }

    @Test
    fun `执行环境只有系统与 root`() {
        assertEquals(listOf(ScriptEnv.SYSTEM, ScriptEnv.ROOT), ScriptEnv.entries.toList())
    }
}
