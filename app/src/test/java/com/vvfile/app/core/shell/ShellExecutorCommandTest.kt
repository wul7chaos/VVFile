package com.vvfile.app.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 通道的命令拼装。
 *
 * 关键点：切到 init 命名空间时 `nsenter` 是**直接 exec** 的，命令里的 shell 语法
 * （`cd … && …`、管道、重定向）必须交给命名空间里的 `/system/bin/sh -c` 解析，
 * 否则会出现 `nsenter: exec cd: No such file or directory`（exit 127）。
 */
class ShellExecutorCommandTest {

    @Test
    fun `带 cd 与链式命令的脚本执行串被包进 sh -c`() {
        val wrapped = ShellExecutor.wrapInNamespace("cd '/data/adb' && chmod 755 'a.sh' && sh 'a.sh'")
        assertEquals(
            "nsenter -t 1 -m -- /system/bin/sh -c " +
                "'cd '\\''/data/adb'\\'' && chmod 755 '\\''a.sh'\\'' && sh '\\''a.sh'\\'''",
            wrapped,
        )
    }

    @Test
    fun `单引号被正确转义，命令不会提前闭合`() {
        val wrapped = ShellExecutor.wrapInNamespace("ls -la '/data/media/0/it'\\''s here'")
        // 整串必须仍以单引号开头结尾，且内部的 ' 都以 '\'' 形式出现
        assertTrue(wrapped.startsWith("nsenter -t 1 -m -- /system/bin/sh -c '"))
        assertTrue(wrapped.endsWith("'"))
        assertTrue(wrapped.contains("'\\''"))
    }

    @Test
    fun `普通命令也走 sh -c，不会被 nsenter 当可执行文件`() {
        val wrapped = ShellExecutor.wrapInNamespace("ls -la '/data/app'")
        assertTrue(wrapped.endsWith("sh -c 'ls -la '\\''/data/app'\\'''"))
    }

    @Test
    fun `shellQuote 处理空串与换行`() {
        assertEquals("''", ShellExecutor.shellQuote(""))
        assertEquals("'a\nb'", ShellExecutor.shellQuote("a\nb"))
    }
}
