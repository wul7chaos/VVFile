package com.vvfile.app.core.transfer

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网发现用的是真 UDP：这里在回环上起一个假接收器，验证「广播出去 → 认回包 → 拿源 IP」。
 */
class PcDiscoveryTest {

    private class FakeDiscovery(
        private val reply: String,
        bind: String = "127.0.0.1",
        port: Int = 0,
        private val rounds: Int = 1,
    ) : AutoCloseable {
        private val socket = DatagramSocket(port, InetAddress.getByName(bind))
        val port: Int get() = socket.localPort
        var probe: String? = null
            private set
        val worker = thread(isDaemon = true) {
            runCatching {
                val buffer = ByteArray(256)
                repeat(rounds) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    probe = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val bytes = reply.replace("__PORT__", socket.localPort.toString())
                        .toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
                }
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun `收到回包就算找到，地址取包的源 IP`() = runBlocking {
        val reply = "{\"ok\":true,\"app\":\"vvfile-receiver\",\"version\":1," +
            "\"port\":7717,\"dir\":\"E:\\\\VVFile\",\"host\":\"DESKTOP\"}"
        FakeDiscovery(reply).use { fake ->
            val found = PcDiscovery.discover(
                port = fake.port,
                timeoutMs = 3_000,
                targets = listOf(InetAddress.getByName("127.0.0.1")),
            )
            fake.worker.join(2_000)
            assertTrue("应该有结果", found != null)
            assertEquals("127.0.0.1", found!!.target.host)
            assertEquals(7717, found!!.target.port)
            assertEquals("E:\\VVFile", found!!.dir)
            assertTrue(fake.probe!!.contains("VVFILE"))
        }
    }

    @Test
    fun `没人回应就返回 null`() = runBlocking {
        // 占一个端口再放掉，基本可以确定上面没人监听
        val port = DatagramSocket(0).use { it.localPort }
        val found = PcDiscovery.discover(
            port = port,
            timeoutMs = 400,
            targets = listOf(InetAddress.getByName("127.0.0.1")),
        )
        assertNull(found)
    }

    @Test
    fun `不相干的应用名不算`() = runBlocking {
        FakeDiscovery("{\"ok\":true,\"app\":\"something-else\",\"port\":7717}").use { fake ->
            val found = PcDiscovery.discover(
                port = fake.port,
                timeoutMs = 600,
                targets = listOf(InetAddress.getByName("127.0.0.1")),
            )
            assertNull(found)
        }
    }

    @Test
    fun `子网扫描覆盖本网段、跳过自己和广播地址`() {
        val hosts = PcDiscovery.unicastSweep("192.168.32.119", 24).map { it.hostAddress }
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("192.168.32.200"))
        assertTrue(!hosts.contains("192.168.32.119"))
        assertTrue(!hosts.contains("192.168.32.255"))
        assertTrue(!hosts.contains("192.168.32.0"))
    }

    @Test
    fun `前缀更长时只扫自己那半个网段`() {
        // 10.0.0.200/25 → 本机在 128..254 这半边，扫 129..253
        val hosts = PcDiscovery.unicastSweep("10.0.0.200", 25).map { it.hostAddress }
        assertTrue(hosts.size in 120..130)
        assertTrue(!hosts.contains("10.0.0.128"))
        assertTrue(!hosts.contains("10.0.0.200"))
        assertTrue(hosts.all { it.startsWith("10.0.0.") })
    }

    @Test
    fun `地址不合法时给空表`() {
        assertTrue(PcDiscovery.unicastSweep("fe80::1", 64).isEmpty())
        assertTrue(PcDiscovery.unicastSweep(null, 24).isEmpty())
    }

    /** 假的 HTTP 接收器：只回一个 /ping 的 JSON，用来验证「缓存命中不去扫描」。 */
    private class FakeHttp(private val reply: String) : AutoCloseable {
        private val server = java.net.ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val buffer = ByteArray(1024)
                    runCatching { input.read(buffer) }
                    val body = reply.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(head.toByteArray(Charsets.ISO_8859_1))
                        write(body)
                        flush()
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    @Test
    fun `缓存里的电脑还能连上就直接用，不再扫描`() = runBlocking {
        FakeHttp("{\"ok\":true,\"app\":\"vvfile-receiver\",\"dir\":\"E:\\\\VVFile\"}").use { fake ->
            val cached = PcTarget("127.0.0.1", fake.port)
            val found = PcDiscovery.connect(
                cached = cached,
                pingTimeoutMs = 2_000,
                timeoutMs = 200,
                // 真扫描的话这里没人应答，能证明结果是缓存命中的
                targets = listOf(InetAddress.getByName("127.0.0.1")),
            )
            fake.worker.join(2_000)
            assertEquals(cached, found?.target)
            assertEquals("E:\\VVFile", found?.dir)
        }
    }

    @Test
    fun `缓存里的电脑连不上就退回扫描`() = runBlocking {
        // 缓存指向一个没人监听的端口，扫描打到本地假接收器
        val deadPort = java.net.ServerSocket(0).use { it.localPort }
        FakeDiscovery("{\"ok\":true,\"app\":\"vvfile-receiver\",\"port\":7717}").use { fake ->
            val found = PcDiscovery.connect(
                cached = PcTarget("127.0.0.1", deadPort),
                pingTimeoutMs = 400,
                port = fake.port,
                timeoutMs = 2_000,
                targets = listOf(InetAddress.getByName("127.0.0.1")),
            )
            assertTrue(found != null)
            assertEquals(7717, found!!.target.port)
        }
    }

    @Test
    fun `多台电脑时全部收下、按地址去重`() = runBlocking {
        val replyA = "{\"ok\":true,\"app\":\"vvfile-receiver\",\"port\":__PORT__,\"dir\":\"E:\\\\VVFile\"}"
        val replyB = "{\"ok\":true,\"app\":\"vvfile-receiver\",\"port\":__PORT__,\"dir\":\"D:\\\\收\"}"
        FakeDiscovery(replyA).use { a ->
            FakeDiscovery(replyB, bind = "127.0.0.2", port = a.port).use { b ->
                val all = PcDiscovery.discoverAll(
                    port = a.port,
                    timeoutMs = 1_200,
                    targets = listOf(
                        InetAddress.getByName("127.0.0.1"),
                        InetAddress.getByName("127.0.0.2"),
                    ),
                )
                a.worker.join(2_000)
                b.worker.join(2_000)
                assertEquals(2, all.size)
                assertEquals(all.size, all.map { it.target.authority }.distinct().size)
            }
        }
    }

    @Test
    fun `上次那台排在多设备列表最前`() = runBlocking {
        val reply = "{\"ok\":true,\"app\":\"vvfile-receiver\",\"port\":__PORT__,\"dir\":\"E:\\\\VVFile\"}"
        FakeDiscovery(reply).use { fake ->
            val cached = PcTarget("127.0.0.1", fake.port)
            val all = PcDiscovery.connectAll(
                cached = cached,
                port = fake.port,
                timeoutMs = 1_200,
                targets = listOf(InetAddress.getByName("127.0.0.1")),
            )
            fake.worker.join(2_000)
            assertTrue(all.isNotEmpty())
            assertEquals(cached.authority, all.first().target.authority)
        }
    }
}

