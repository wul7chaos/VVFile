package com.vvfile.app.core.install

import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerTest {

    private class FakeRunner(private val respond: (callIndex: Int) -> ShellResult) : CommandRunner {
        val commands = mutableListOf<String>()
        val rootFlags = mutableListOf<Boolean>()

        override suspend fun exec(
            command: String,
            asRoot: Boolean,
            timeoutMs: Long,
        ): ShellResult {
            commands += command
            rootFlags += asRoot
            return respond(commands.lastIndex)
        }
    }

    private fun result(
        output: String,
        exitCode: Int = 0,
        started: Boolean = true,
        stderr: String = "",
    ) = ShellResult(
        command = "",
        asRoot = true,
        exitCode = exitCode,
        stdout = if (stderr.isEmpty()) output else "",
        stderr = if (stderr.isEmpty()) "" else stderr,
        started = started,
    )

    @Test
    fun `只有 apk 扩展名算安装包`() {
        assertTrue(ApkInstaller.isApkFile("/sdcard/a.apk"))
        assertTrue(ApkInstaller.isApkFile("/sdcard/Base.APK"))
        assertFalse(ApkInstaller.isApkFile("/sdcard/a.apks"))
        assertFalse(ApkInstaller.isApkFile("/sdcard/a.apk.zip"))
        assertFalse(ApkInstaller.isApkFile("/sdcard/apk"))
    }

    @Test
    fun `命令带 -r 覆盖安装并转义路径`() {
        assertEquals(
            "pm install -r '/sdcard/Download/a b.apk'",
            ApkInstaller.buildCommand("/sdcard/Download/a b.apk"),
        )
        assertEquals(
            "/system/bin/pm install -r '/sdcard/a.apk'",
            ApkInstaller.buildCommand("/sdcard/a.apk", pmBinary = "/system/bin/pm"),
        )
    }

    @Test
    fun `Success 才算装成功`() {
        val ok = ApkInstaller.parse(result("Success"))
        assertTrue(ok.success)

        val failed = ApkInstaller.parse(
            result("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.x signatures do not match]", exitCode = 1),
        )
        assertFalse(failed.success)
        assertTrue(failed.message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(failed.message.contains("signatures"))
    }

    @Test
    fun `没有输出时用退出码说话`() {
        val failed = ApkInstaller.parse(result("", exitCode = 1))
        assertFalse(failed.success)
        assertTrue(failed.message.contains("exit 1"))
    }

    @Test
    fun `root 会话里没有 pm 时换绝对路径重试`() = runBlocking {
        val fake = FakeRunner { index ->
            if (index == 0) {
                result("pm: not found", exitCode = 127, stderr = "sh: pm: not found")
            } else {
                result("Success")
            }
        }
        val outcome = ApkInstaller.installWithRoot("/sdcard/a.apk", runner = fake)

        assertTrue(outcome.success)
        assertEquals(2, fake.commands.size)
        assertEquals(listOf(true, true), fake.rootFlags)
        assertTrue(fake.commands[1].startsWith("/system/bin/pm install -r "))
    }

    @Test
    fun `su 起不来时报失败而不是假装成功`() = runBlocking {
        val fake = FakeRunner { result("", started = false) }
        val outcome = ApkInstaller.installWithRoot("/sdcard/a.apk", runner = fake)

        assertFalse(outcome.success)
    }
}
