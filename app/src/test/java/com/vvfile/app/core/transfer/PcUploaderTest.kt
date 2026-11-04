package com.vvfile.app.core.transfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议对端用本地 socket 手搓一个，验证的是「真发出去的字节长什么样」，
 * 而不是自己的实现和自己的想法一致。
 */
class PcUploaderTest {

    /** 抓一次完整请求：请求行、头、正文。 */
    private class Captured(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private class FakeReceiver(
        private val status: Int,
        private val responseBody: String,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val worker: Thread
        var captured: Captured? = null
            private set

        val port: Int get() = server.localPort

        init {
            worker = thread(isDaemon = true, name = "fake-receiver") {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val head = ByteArrayOutputStream()
                        var tail = 0
                        while (true) {
                            val b = input.read()
                            if (b < 0) break
                            head.write(b)
                            tail = (tail shl 8) or b
                            if (tail == 0x0D0A0D0A) break
                        }
                        val text = String(head.toByteArray(), Charsets.ISO_8859_1)
                        val lines = text.split("\r\n").filter { it.isNotEmpty() }
                        val headers = lines.drop(1).mapNotNull { line ->
                            val colon = line.indexOf(':')
                            if (colon <= 0) null
                            else line.substring(0, colon).trim().lowercase() to
                                line.substring(colon + 1).trim()
                        }.toMap()
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n <= 0) break
                            read += n
                        }
                        captured = Captured(lines.first(), headers, body)

                        val payload = responseBody.toByteArray(Charsets.UTF_8)
                        val response = buildString {
                            append("HTTP/1.1 ").append(status).append(" X\r\n")
                            append("Content-Type: application/json; charset=utf-8\r\n")
                            append("Content-Length: ").append(payload.size).append("\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(response.toByteArray(Charsets.ISO_8859_1))
                            write(payload)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    private fun tempFile(name: String, content: ByteArray): File {
        val dir = Files.createTempDirectory("vvfile-pc").toFile()
        val file = File(dir, name)
        file.writeBytes(content)
        return file
    }

    @Test
    fun `文件名按 UTF-8 百分号编码，空格不会变成加号`() {
        assertEquals("a-b_c.d~e", PcUploader.encodeName("a-b_c.d~e"))
        assertEquals("a%20b.txt", PcUploader.encodeName("a b.txt"))
        assertEquals(
            "%E6%B5%8B%E8%AF%95.txt",
            PcUploader.encodeName("测试.txt"),
        )
        assertEquals("%21%23%25", PcUploader.encodeName("!#%"))
    }

    @Test
    fun `相对路径保留斜杠、逐段编码`() {
        assertEquals(
            "%E7%85%A7%E7%89%87/2024/a%20b.jpg",
            PcUploader.encodePath("照片/2024/a b.jpg"),
        )
        assertEquals("a.txt", PcUploader.encodePath("a.txt"))
        assertEquals("a/b", PcUploader.encodePath("/a//b/"))
    }

    @Test
    fun `上传按协议发 PUT、文件名头与原始字节`() = runBlocking {
        val content = "hello 电脑".toByteArray(Charsets.UTF_8)
        val file = tempFile("测试 文件.txt", content)
        FakeReceiver(200, "{\"ok\":true,\"path\":\"E:\\\\VVFile\\\\测试 文件.txt\"}").use { receiver ->
            var lastSent = 0L
            var lastTotal = 0L
            val result = PcUploader.upload(
                target = PcTarget("127.0.0.1", receiver.port),
                file = file,
                onProgress = { sent, total ->
                    lastSent = sent
                    lastTotal = total
                },
            )
            receiver.worker.join(3000)

            val captured = receiver.captured
            assertTrue("应当收到请求", captured != null)
            assertTrue(captured!!.requestLine.startsWith("PUT /upload"))
            assertEquals(
                PcUploader.encodeName("测试 文件.txt"),
                captured!!.headers["x-vv-name"],
            )
            assertEquals(
                PcUploader.encodePath("测试 文件.txt"),
                captured!!.headers["x-vv-path"],
            )
            assertEquals(content.size.toString(), captured!!.headers["x-vv-size"])
            assertEquals(content.size.toString(), captured!!.headers["content-length"])
            assertTrue(captured!!.body.contentEquals(content))

            assertTrue(result.ok)
            assertEquals("已发送", result.message)
            assertEquals("E:\\VVFile\\测试 文件.txt", result.savedPath)
            assertEquals(content.size.toLong(), lastSent)
            assertEquals(content.size.toLong(), lastTotal)
        }
    }

    @Test
    fun `电脑端报错时把原因透出来`() = runBlocking {
        val file = tempFile("a.bin", ByteArray(4))
        FakeReceiver(500, "{\"ok\":false,\"error\":\"磁盘满了\"}").use { receiver ->
            val result = PcUploader.upload(
                target = PcTarget("127.0.0.1", receiver.port),
                file = file,
            )
            receiver.worker.join(3000)
            assertFalse(result.ok)
            assertTrue(result.message.contains("磁盘满了"))
        }
    }

    @Test
    fun `连不上时给出可读提示`() = runBlocking {
        // 先占个端口再放开，基本可以确认没人监听
        val port = ServerSocket(0).use { it.localPort }
        val file = tempFile("a.bin", ByteArray(4))
        val result = PcUploader.upload(
            target = PcTarget("127.0.0.1", port),
            file = file,
            timeoutMs = 1500,
        )
        assertFalse(result.ok)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `建目录只发路径不发正文`() = runBlocking {
        FakeReceiver(200, "{\"ok\":true,\"path\":\"E:\\\\VVFile\\\\照片\\\\2024\"}").use { receiver ->
            val result = PcUploader.mkdir(
                target = PcTarget("127.0.0.1", receiver.port),
                relativePath = "照片/2024",
            )
            receiver.worker.join(3000)
            assertTrue(result.ok)
            val captured = receiver.captured!!
            assertTrue(captured.requestLine.startsWith("PUT /dir"))
            assertEquals("0", captured.headers["content-length"])
            assertEquals(
                PcUploader.encodePath("照片/2024"),
                captured.headers["x-vv-path"],
            )
        }
    }

    @Test
    fun `ping 读回电脑端保存目录`() = runBlocking {
        FakeReceiver(200, "{\"ok\":true,\"app\":\"vvfile-receiver\",\"dir\":\"E:\\\\VVFile\"}").use { receiver ->
            val result = PcUploader.ping(PcTarget("127.0.0.1", receiver.port))
            receiver.worker.join(3000)
            assertTrue(result.ok)
            assertTrue(result.message.contains("E:\\VVFile"))
            assertTrue(receiver.captured!!.requestLine.startsWith("GET /ping"))
        }
    }

    @Test
    fun `JSON 里的反斜杠转义会还原`() {
        assertEquals("E:\\VVFile\\a.apk", PcUploader.unescape("E:\\\\VVFile\\\\a.apk"))
        assertEquals("没有转义", PcUploader.unescape("没有转义"))
    }
}
