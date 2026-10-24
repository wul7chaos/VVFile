package com.vvfile.app.data.file

import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationResult
import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellFileOperatorTest {

    private class FakeRunner(
        private val handler: (String) -> ShellResult,
    ) : CommandRunner {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, asRoot: Boolean, timeoutMs: Long): ShellResult {
            commands += command
            return handler(command)
        }
    }

    private fun ok(command: String) = ShellResult(
        command = command,
        asRoot = true,
        exitCode = 0,
        stdout = "",
        stderr = "",
    )

    private fun fail(command: String) = ShellResult(
        command = command,
        asRoot = true,
        exitCode = 1,
        stdout = "",
        stderr = "Permission denied",
    )

    private fun collect(flow: Flow<OperationEvent>): OperationResult {
        val events = runBlocking { flow.toList() }
        return (events.last() as OperationEvent.Finished).result
    }

    @Test
    fun `copy skip 时不执行 cp`() {
        val runner = FakeRunner { command ->
            if (command.startsWith("test -e")) ok(command) else ok(command)
        }
        val operator = ShellFileOperator(runner)
        val result = collect(
            operator.copy(listOf("/system/a.txt"), "/system/b", ConflictPolicy.SKIP),
        )
        assertEquals(1, result.skipped.size)
        assertFalse(runner.commands.any { it.startsWith("cp ") })
    }

    @Test
    fun `copy rename 生成带序号的目标`() {
        val runner = FakeRunner { command ->
            when {
                command == "test -e '/system/b/a.txt'" -> ok(command)
                command == "test -e '/system/b/a (1).txt'" -> fail(command)
                else -> ok(command)
            }
        }
        val operator = ShellFileOperator(runner)
        val result = collect(
            operator.copy(listOf("/system/a.txt"), "/system/b", ConflictPolicy.RENAME),
        )
        assertTrue(result.isSuccess)
        assertTrue(runner.commands.any { it == "cp -a '/system/a.txt' '/system/b/a (1).txt'" })
    }

    @Test
    fun `delete 使用 rm -rf 并正确转义路径`() {
        val runner = FakeRunner { ok(it) }
        val operator = ShellFileOperator(runner)
        val result = collect(operator.delete(listOf("/system/a b")))
        assertTrue(result.isSuccess)
        assertEquals("rm -rf '/system/a b'", runner.commands.single())
    }

    @Test
    fun `createDirectory 遇到同名直接失败`() {
        val runner = FakeRunner { ok(it) }
        val operator = ShellFileOperator(runner)
        val result = runBlocking { operator.createDirectory("/system", "app") }
        assertFalse(result.isSuccess)
        assertFalse(runner.commands.any { it.startsWith("mkdir") })
    }

    @Test
    fun `root 分区压缩返回失败而不是崩溃`() {
        val operator = ShellFileOperator(FakeRunner { ok(it) })
        val result = collect(operator.zip(listOf("/system/a"), "/system/a.zip"))
        assertFalse(result.isSuccess)
        assertTrue(result.failures.single().reason.contains("不支持"))
    }
}
