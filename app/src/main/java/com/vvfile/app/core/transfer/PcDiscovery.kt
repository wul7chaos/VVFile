package com.vvfile.app.core.transfer

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 局域网自动发现电脑端的接收器。
 *
 * 手机往 UDP 7717 发一句 `VVFILE?DISCOVER=1`，正在跑的电脑端收到就回一串 JSON
 * （自己的接收目录、端口）。手机只认「回的包来自哪里」，地址取包的源 IP——
 * 免得电脑多网卡时报了个手机到不了的地址回来。
 *
 * 探针发三拨：
 * 1. 上次找到的电脑（进程内缓存），命中就直接用；
 * 2. 广播：`255.255.255.255` + 每块网卡的广播地址；
 * 3. 子网单播扫描：本机 IPv4 所在的 /24 逐个地址发一遍。
 *    不少路由器/AP 会过滤广播，单播才通得过，这一拨是保底。
 *
 * 不需要用户填 IP、也没有口令：同一个 WiFi 下打开就能发。
 */
object PcDiscovery {

    const val PORT: Int = PcTarget.DEFAULT_PORT
    private const val PROBE = "VVFILE?DISCOVER=1"
    private const val APP_TAG = "vvfile-receiver"
    const val DEFAULT_TIMEOUT_MS: Long = 2_200
    /** 缓存命中时只发一个 /ping，超时给短一点，连不上就立刻转扫描。 */
    private const val CACHED_PING_TIMEOUT_MS: Int = 900
    /** 预热结果的有效期：两分钟内开面板不再重新找。 */
    private const val CACHE_FRESH_MS: Long = 120_000

    /** 本次进程里上次找到的电脑，下次先试它（省掉一轮扫描）。 */
    @Volatile
    private var lastFound: PcTarget? = null

    /** 最近一次成功连接的结果与时间：应用启动时预热，面板打开就能直接用。 */
    @Volatile
    private var cachedFound: Found? = null
    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 应用启动时先在后台找一次：有缓存就 ping 一下，没有就扫一遍。
     * 结果留在 [peek] 里，等用户真正点「发送到电脑」时已经就绪，面板不用再等。
     * 找不到就安静结束，等面板打开时再试。
     */
    suspend fun warmUp(cached: PcTarget?, port: Int = PORT): Found? {
        peek()?.let { return it }
        return runCatching { connect(cached, port = port) }.getOrNull()
    }

    /** 预热结果还能用时直接拿出来，省掉面板打开时的等待。 */
    fun peek(maxAgeMs: Long = CACHE_FRESH_MS): Found? {
        val hit = cachedFound ?: return null
        return hit.takeIf { System.currentTimeMillis() - cachedAt <= maxAgeMs }
    }

    /** 记录一次成功连接（启动预热与面板里的连接都写这里）。 */
    private fun remember(found: Found) {
        lastFound = found.target
        cachedFound = found
        cachedAt = System.currentTimeMillis()
    }

    /** 发现结果；[dir] 是电脑端要保存到的目录，用来在面板上给人确认。 */
    data class Found(val target: PcTarget, val dir: String?) {
        val label: String get() = target.authority
    }

    /**
     * 先试缓存里的电脑（上次连上的那台），连不上再走广播 + 子网扫描。
     *
     * 缓存命中时只发一个 `GET /ping`，通常几十毫秒就回来了，
     * 不用每次都把整个局域网扫一遍；电脑换了 IP 也只是多走一次扫描，找到后缓存会更新。
     */
    suspend fun connect(
        cached: PcTarget?,
        port: Int = PORT,
        pingTimeoutMs: Int = CACHED_PING_TIMEOUT_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        targets: List<InetAddress>? = null,
    ): Found? {
        if (cached != null) {
            val ping = PcUploader.ping(cached, pingTimeoutMs)
            if (ping.ok) {
                val hit = Found(cached, ping.dir)
                remember(hit)
                return hit
            }
        }
        return discover(port = port, timeoutMs = timeoutMs, targets = targets)
    }

    /**
     * 广播 + 子网扫描找电脑端。[targets] 只在测试时用，正常按真实网卡地址探测。
     * 找不到返回 null（超时），由界面提示。
     */
    suspend fun discover(
        port: Int = PORT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        targets: List<InetAddress>? = null,
    ): Found? = withContext(Dispatchers.IO) {
        val destinations = targets ?: probeAddresses()
        if (destinations.isEmpty()) return@withContext null

        // 建 socket / 发广播在某些系统或网络环境下会直接抛（无 WiFi、权限、路由），一律当「没找到」
        val found = runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 200
                val payload = PROBE.toByteArray(Charsets.UTF_8)
                for (address in destinations) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, InetSocketAddress(address, port)))
                    }
                }
                await(socket, port, timeoutMs)
            }
        }.getOrNull()
        if (found != null) remember(found)
        found
    }

    /** 收第一个像样的回包；超时就 null。 */
    private fun await(socket: DatagramSocket, fallbackPort: Int, timeoutMs: Long): Found? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArray(2048)
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            val got = runCatching { socket.receive(packet); true }.getOrDefault(false)
            if (!got) continue
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (!text.contains(APP_TAG)) continue
            val host = packet.address?.hostAddress ?: continue
            val replyPort = JSON_PORT.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackPort
            val dir = JSON_DIR.find(text)?.groupValues?.get(1)?.let { PcUploader.unescape(it) }
            return Found(PcTarget(host, replyPort), dir)
        }
        return null
    }

    /** 本机各 IPv4 网卡对应的探测地址：广播 + 同 /24 的单播，缓存里的电脑排最前。 */
    internal fun probeAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        lastFound?.let { target ->
            runCatching { list += InetAddress.getByName(target.host) }
        }
        runCatching { list += InetAddress.getByName("255.255.255.255") }
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    val ip = address.address
                    if (ip !is Inet4Address) continue
                    address.broadcast?.let { list += it }
                    list += unicastSweep(ip.hostAddress, address.networkPrefixLength.toInt())
                }
            }
        }
        return list.distinctBy { it.hostAddress }
    }

    /**
     * 同 /24 内除自己以外的全部单播地址（最多 254 个）。
     *
     * 只在网段前缀 ≥ 24 时扫；前缀更短（/16 之类）也按本机所在的 /24 扫，
     * 既够用又不会一次扔出几万个包。
     */
    internal fun unicastSweep(hostAddress: String?, prefixLength: Int): List<InetAddress> {
        val local = hostAddress ?: return emptyList()
        val parts = local.split('.')
        if (parts.size != 4) return emptyList()
        val base = parts.take(3).joinToString(".")
        if (base.isEmpty()) return emptyList()
        // /25 及以上是小网段，按实际范围收窄；/24 及更短就扫整个 /24
        val range = if (prefixLength >= 25) {
            val block = parts[3].toIntOrNull()?.let { it / 128 * 128 } ?: 0
            (block + 1) until (block + 127)
        } else {
            1 until 255
        }
        return range.mapNotNull { host ->
            val text = "$base.$host"
            if (text == local) return@mapNotNull null
            runCatching { InetAddress.getByName(text) as Inet4Address }.getOrNull()
        }
    }

    private val JSON_PORT = Regex("\"port\"\\s*:\\s*(\\d+)")
    private val JSON_DIR = Regex("\"dir\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
}

