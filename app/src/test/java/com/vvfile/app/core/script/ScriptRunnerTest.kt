package com.vvfile.app.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerTest {

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
