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

    private class FakeDiscovery(private val reply: String) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        var probe: String? = null
            private set
        val worker = thread(isDaemon = true) {
            runCatching {
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                probe = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val bytes = reply.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
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
}
