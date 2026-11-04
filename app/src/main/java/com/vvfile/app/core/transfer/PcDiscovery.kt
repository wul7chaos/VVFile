package com.vvfile.app.core.transfer

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 局域网自动发现电脑端的接收器。
 *
 * 手机往 UDP 7717 广播一句 `VVFILE?DISCOVER=1`，正在跑的电脑端收到就回一串 JSON
 * （自己的接收目录、端口）。手机只认「回的包来自哪里」，地址取包的源 IP——
 * 免得电脑多网卡时报了个手机到不了的地址回来。
 *
 * 不需要用户填 IP、也不需要口令：同一个 WiFi 下打开就能发。
 */
object PcDiscovery {

    const val PORT: Int = PcTarget.DEFAULT_PORT
    private const val PROBE = "VVFILE?DISCOVER=1"
    private const val APP_TAG = "vvfile-receiver"
    const val DEFAULT_TIMEOUT_MS: Long = 1_800

    /** 发现结果；[dir] 是电脑端要保存到的目录，用来在面板上给人确认。 */
    data class Found(val target: PcTarget, val dir: String?)

    /**
     * 广播找电脑端。[targets] 只在测试时用，正常走真实广播地址。
     * 找不到返回 null（超时），由界面提示。
     */
    suspend fun discover(
        port: Int = PORT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        targets: List<InetAddress>? = null,
    ): Found? = withContext(Dispatchers.IO) {
        // 回环 + 各网卡广播地址：单播回环是为了让单元测试能用，实际不影响局域网
        val destinations = targets ?: buildList {
            add(InetAddress.getByName("255.255.255.255"))
            runCatching {
                NetworkInterface.getNetworkInterfaces().toList().forEach { nic ->
                    if (!nic.isUp || nic.isLoopback) return@forEach
                    nic.interfaceAddresses.forEach { address ->
                        address.broadcast?.let { add(it) }
                    }
                }
            }
        }
        if (destinations.isEmpty()) return@withContext null

        // 某些系统/网络下广播或建 socket 会直接抛（无 WiFi、权限、路由），都当成「没找到」
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 200
                val payload = PROBE.toByteArray(Charsets.UTF_8)
                destinations.distinct().forEach { address ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(address, port)))
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val got = runCatching { socket.receive(packet); true }.getOrDefault(false)
                    if (!got) continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (!text.contains(APP_TAG)) continue
                    val host = packet.address?.hostAddress ?: continue
                    val replyPort = JSON_PORT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: port
                    val dir = JSON_DIR.find(text)?.groupValues?.get(1)
                        ?.let { PcUploader.unescape(it) }
                    return@withContext Found(PcTarget(host, replyPort), dir)
                }
            }
        }
        null
    }

    private val JSON_PORT = Regex("\"port\"\\s*:\\s*(\\d+)")
    private val JSON_DIR = Regex("\"dir\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
}
