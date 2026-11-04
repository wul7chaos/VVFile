package com.vvfile.app.core.transfer

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端接收器用 `PcUploader`（手机 → 电脑用的同一个客户端）来打，
 * 等于把「电脑端将来要实现的协议」提前锁死：对得上才算过。
 */
class PcReceiveServerTest {

    private fun tempRoot(): File = Files.createTempDirectory("vvfile-recv").toFile()

    private suspend fun withServer(
        root: File,
        block: suspend (PcReceiveServer) -> Unit,
    ) {
        val server = PcReceiveServer(root = root, port = 0, deviceName = "TEST-PHONE")
        assertTrue("服务应该起来", server.start())
        try {
            block(server)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `ping 回设备名与接收目录`() = runBlocking {
        val root = tempRoot()
        withServer(root) { server ->
            val result = PcUploader.ping(PcTarget("127.0.0.1", server.boundPort))
            assertTrue(result.ok)
            assertEquals(root.absolutePath, result.dir)
        }
    }

    @Test
    fun `上传单个文件落到根目录`() = runBlocking {
        val root = tempRoot()
        val source = File(tempRoot(), "笔记 2026.txt").apply { writeText("hello phone") }
        withServer(root) { server ->
            val target = PcTarget("127.0.0.1", server.boundPort)
            val result = PcUploader.upload(target, source, name = source.name, relativePath = source.name)
            assertTrue(result.message, result.ok)
            val saved = File(root, "笔记 2026.txt")
            assertTrue(saved.isFile)
            assertEquals("hello phone", saved.readText())
        }
    }

    @Test
    fun `文件夹结构原样还原`() = runBlocking {
        val root = tempRoot()
        val a = File(tempRoot(), "a.txt").apply { writeText("aaa") }
        val b = File(tempRoot(), "b.txt").apply { writeText("bb") }
        withServer(root) { server ->
            val target = PcTarget("127.0.0.1", server.boundPort)
            assertTrue(PcUploader.mkdir(target, "相册/2024").ok)
            assertTrue(PcUploader.upload(target, a, "a.txt", "相册/2024/a.txt").ok)
            assertTrue(PcUploader.upload(target, b, "b.txt", "相册/b.txt").ok)
            assertEquals("aaa", File(root, "相册/2024/a.txt").readText())
            assertEquals("bb", File(root, "相册/b.txt").readText())
        }
    }

    @Test
    fun `重名不覆盖`() = runBlocking {
        val root = tempRoot()
        val source = File(tempRoot(), "a.txt").apply { writeText("one") }
        withServer(root) { server ->
            val target = PcTarget("127.0.0.1", server.boundPort)
            assertTrue(PcUploader.upload(target, source, "a.txt", "a.txt").ok)
            source.writeText("two")
            val second = PcUploader.upload(target, source, "a.txt", "a.txt")
            assertTrue(second.ok)
            assertEquals("one", File(root, "a.txt").readText())
            assertTrue(File(root, "a (1).txt").isFile)
        }
    }

    @Test
    fun `路径穿越被拦住`() = runBlocking {
        val root = tempRoot()
        val source = File(tempRoot(), "evil.txt").apply { writeText("x") }
        withServer(root) { server ->
            val target = PcTarget("127.0.0.1", server.boundPort)
            // 客户端不让把 .. 发出来，这里直接绕过去测服务端净化逻辑
            val result = PcUploader.upload(
                target = target,
                file = source,
                name = "evil.txt",
                relativePath = "../../etc/evil.txt",
            )
            assertTrue(result.message, result.ok)
            assertTrue(File(root, "etc/evil.txt").isFile)
            assertFalse(File(root.parentFile, "../etc/evil.txt").exists())
        }
    }

    @Test
    fun `端口被占用时返回 false 而不是抛异常`() {
        val root = tempRoot()
        val first = PcReceiveServer(root, port = 0, deviceName = "A")
        assertTrue(first.start())
        val port = first.boundPort
        val second = PcReceiveServer(root, port = port, deviceName = "B")
        assertFalse(second.start())
        first.stop()
    }
}
