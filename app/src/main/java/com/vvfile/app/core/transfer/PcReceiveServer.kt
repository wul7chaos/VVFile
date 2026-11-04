package com.vvfile.app.core.transfer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 手机端接收器：电脑主动把文件/文件夹发过来，落到 `Download/VVFile`。
 *
 * 协议与「手机 → 电脑」一致（一条请求一个文件），只是方向反过来：
 * - `GET /ping`：电脑用来确认「这台是 VVFile 手机端」，回设备名与接收目录
 * - `PUT /dir`：建目录（`X-VV-Path` 相对路径）
 * - `PUT /upload`：写文件（`X-VV-Path` 相对路径 + `Content-Length` 正文）
 * - UDP 7718：电脑广播 `VVFILE?SEND=1`，这里回自己的端口与名字，电脑据此找到手机
 *
 * 不需要配对码：只在同一局域网内可用，且只在用户允许（前台服务在跑）时才监听。
 */
class PcReceiveServer(
    private val root: File,
    private val port: Int = DEFAULT_PORT,
    private val deviceName: String = "VVFile",
    private val onReceived: (File) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {

    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null

    @Volatile
    private var running = false

    /** 实际监听端口；传 0 时由系统分配，测试用。 */
    @Volatile
    var boundPort: Int = port
        private set

    val isRunning: Boolean get() = running

    /** 起 TCP + UDP 两个监听；端口被占或没权限时返回 false（调用方提示一下即可）。 */
    fun start(): Boolean {
        if (running) return true
        root.mkdirs()
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            boundPort = socket.localPort
            tcp = socket
            running = true

            thread(isDaemon = true, name = "vvfile-recv-accept") { acceptLoop(socket) }
            runCatching { startUdp() }.onFailure { onLog("UDP 发现没起来：${it.message}") }
            onLog("开始监听 0.0.0.0:$boundPort，保存到 ${root.absolutePath}")
            true
        }.getOrElse {
            onLog("监听 $port 失败：${it.message}")
            stop()
            false
        }
    }

    fun stop() {
        running = false
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        tcp = null
        udp = null
    }

    private fun startUdp() {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(boundPort))
        udp = socket
        thread(isDaemon = true, name = "vvfile-recv-udp") {
            val buffer = ByteArray(512)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                val ok = runCatching { socket.receive(packet); true }.getOrDefault(false)
                if (!ok) continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!text.contains("VVFILE", ignoreCase = true)) continue
                val reply = """{"ok":true,"app":"vvfile-phone","port":$boundPort,""" +
                    """"name":${json(deviceName)},"dir":${json(root.absolutePath)}}"""
                val bytes = reply.toByteArray(Charsets.UTF_8)
                runCatching {
                    socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                }
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            thread(isDaemon = true, name = "vvfile-recv-conn") { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val request = runCatching { readRequest(input) }.getOrNull() ?: return
            val (method, path, headers, headConsumed) = request

            when {
                method == "GET" && path.startsWith("/ping") -> respondOk(
                    output,
                    """{"ok":true,"app":"vvfile-phone","port":$boundPort,""" +
                        """"name":${json(deviceName)},"dir":${json(root.absolutePath)}}""",
                )
                method == "PUT" && path.startsWith("/dir") -> {
                    val relative = PcServerPaths.safeRelative(headers["x-vv-path"], "")
                    if (relative.isEmpty()) {
                        respondJson(output, 400, """{"ok":false,"error":"缺少 X-VV-Path"}""")
                    } else {
                        val dir = File(root, relative)
                        if (dir.mkdirs() || dir.isDirectory) {
                            onLog("已建目录 $relative/")
                            respondOk(output, """{"ok":true,"path":${json(dir.absolutePath)}}""")
                        } else {
                            respondJson(output, 500, """{"ok":false,"error":"建目录失败"}""")
                        }
                    }
                }
                method == "PUT" && path.startsWith("/upload") -> receiveFile(socket, input, output, headers)
                else -> respondJson(output, 405, """{"ok":false,"error":"只支持 /ping、/dir、/upload"}""")
            }
            output.flush()
            if (headConsumed && method == "PUT") runCatching { socket.shutdownOutput() }
        }
    }

    private fun receiveFile(socket: Socket, input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val length = headers["content-length"]?.toLongOrNull()
        if (length == null || length < 0) {
            respondJson(output, 411, """{"ok":false,"error":"缺少 Content-Length"}""")
            return
        }
        val rawPath = PcServerPaths.safeRelative(headers["x-vv-path"], PcServerPaths.safeName(headers["x-vv-name"]))
        if (rawPath.isEmpty()) {
            respondJson(output, 400, """{"ok":false,"error":"文件名不合法"}""")
            return
        }
        val target = File(root, rawPath)
        target.parentFile?.mkdirs()

        val temp = File(target.parentFile ?: root, ".incoming-${System.nanoTime()}.part")
        try {
            var received = 0L
            temp.outputStream().buffered(BUFFER).use { out ->
                val buffer = ByteArray(BUFFER)
                while (received < length) {
                    val want = minOf(buffer.size.toLong(), length - received).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) throw IOException("连接中断，只收到 $received 字节")
                    out.write(buffer, 0, read)
                    received += read
                }
            }
            val final = PcServerPaths.uniqueTarget(target)
            if (!temp.renameTo(final)) {
                temp.copyTo(final, overwrite = true)
                temp.delete()
            }
            val renamed = final.name != target.name
            onLog("已保存 ${PcServerPaths.relative(root, final)}（$received B${if (renamed) "，重名改名" else ""}）")
            onReceived(final)
            respondOk(
                output,
                """{"ok":true,"path":${json(final.absolutePath)},"bytes":$received,""" +
                    """"renamed":$renamed}""",
            )
        } catch (error: Exception) {
            runCatching { temp.delete() }
            onLog("保存 ${rawPath} 失败：${error.message}")
            respondJson(output, 500, """{"ok":false,"error":${json(error.message ?: "写入失败")}}""")
        } finally {
            runCatching { socket.shutdownInput() }
        }
    }

    /** 读请求行 + 头；正文由调用方按 Content-Length 继续读。 */
    private fun readRequest(input: InputStream): Request {
        val head = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n")) break
            if (head.length > MAX_HEAD) throw IOException("请求头过长")
        }
        val lines = head.toString().split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw IOException("空请求")
        val parts = lines[0].split(' ')
        if (parts.size < 2) throw IOException("请求行不合法")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null
            else line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()
        return Request(parts[0].uppercase(Locale.ROOT), parts[1], headers, headConsumed = true)
    }

    private fun respondOk(output: OutputStream, body: String) = respondJson(output, 200, body)

    private fun respondJson(output: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(bytes)
            output.flush()
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val headConsumed: Boolean,
    )

    companion object {
        const val DEFAULT_PORT = 7718
        private const val BUFFER = 64 * 1024
        private const val MAX_HEAD = 16 * 1024
        private const val SOCKET_TIMEOUT_MS = 120_000

        private fun json(value: String): String {
            val out = StringBuilder("\"")
            for (c in value) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}
