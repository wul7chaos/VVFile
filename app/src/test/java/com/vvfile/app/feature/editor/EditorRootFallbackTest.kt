package com.vvfile.app.feature.editor

import com.vvfile.app.core.io.RootFileChannel
import com.vvfile.app.core.io.RootMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Path

/**
 * root 回退：应用 uid 读不到时改走 `su` 通道。
 *
 * 单测跑在普通用户权限下，造不出真的 EACCES，所以把「直接通道」和「root 通道」
 * 都换成假实现——验证的是**分支选择与参数传递**，真实 su 行为在设备上验。
 */
class EditorRootFallbackTest {

    private class DeniedDirect : DirectFileChannel {
        override fun exists(path: Path): Boolean = false
        override fun isDirectory(path: Path): Boolean = false
        override fun size(path: Path): Long = throw AccessDeniedException(path.toString())
        override fun readPrefix(path: Path, limit: Long): ByteArray =
            throw AccessDeniedException(path.toString())
        override fun write(path: Path, bytes: ByteArray) = throw AccessDeniedException(path.toString())
    }

    private class FakeRoot(
        var meta: RootMeta? = RootMeta(isDirectory = false, size = 5L),
        var bytes: ByteArray = ByteArray(0),
        var readError: Exception? = null,
        var writeError: Exception? = null,
    ) : RootFileChannel {
        var written: ByteArray? = null
        var requestedLimit: Long = -1L
        var statPath: String? = null

        override suspend fun stat(path: String): RootMeta? {
            statPath = path
            return meta
        }

        override suspend fun readPrefix(path: String, limit: Long): ByteArray {
            requestedLimit = limit
            readError?.let { throw it }
            return bytes
        }

        override suspend fun writeAll(path: String, bytes: ByteArray) {
            writeError?.let { throw it }
            written = bytes
        }

        override suspend fun copyTo(path: String, target: File): Boolean = false
    }

    private fun gateway(root: FakeRoot, allowed: Boolean = true): EditorFileGateway =
        EditorFileGateway(
            root = root,
            rootAllowed = { allowed },
            direct = DeniedDirect(),
        )

    @Test
    fun `falls back to root when direct read is denied`() = runBlocking {
        val root = FakeRoot(bytes = "magisk 配置".toByteArray(Charsets.UTF_8), meta = RootMeta(false, 16L))
        val document = gateway(root).read("/data/adb/magisk/config")

        assertEquals("magisk 配置", document.text)
        assertEquals(TextEncoding.UTF_8, document.encoding)
        assertEquals("/data/adb/magisk/config", root.statPath)
    }

    @Test
    fun `keeps the friendly error when root is disabled`() = runBlocking {
        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway(FakeRoot(), allowed = false).read("/data/adb/x") }
        }
        assertEquals("没有权限读取此文件", failure.message)
    }

    @Test
    fun `reports denied when root stat also fails`() = runBlocking {
        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway(FakeRoot(meta = null)).read("/data/adb/x") }
        }
        assertEquals("没有权限读取此文件", failure.message)
    }

    @Test
    fun `root directory is rejected as text`() = runBlocking {
        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway(FakeRoot(meta = RootMeta(isDirectory = true, size = 0))).read("/data/adb") }
        }
        assertEquals("这是一个文件夹，无法作为文本打开", failure.message)
    }

    @Test
    fun `oversized root file is truncated and read-only`() = runBlocking {
        val root = FakeRoot(
            meta = RootMeta(false, EditorFileGateway.MAX_LOAD_BYTES + 10),
            bytes = "partial".toByteArray(Charsets.UTF_8),
        )
        val document = gateway(root).read("/data/adb/big.log")

        assertTrue(document.truncated)
        assertTrue(document.readOnly)
        assertEquals(EditorFileGateway.MAX_LOAD_BYTES, root.requestedLimit)
        assertEquals("partial", document.text)
    }

    @Test
    fun `root read failure surfaces its reason`() = runBlocking {
        val root = FakeRoot(readError = IllegalStateException("su: permission denied"))
        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway(root).read("/data/adb/x") }
        }
        assertTrue(failure.message.orEmpty().startsWith("root 读取失败"))
    }

    @Test
    fun `saves through root when direct write is denied`() = runBlocking {
        val root = FakeRoot(meta = RootMeta(false, 4L))
        gateway(root).save("/data/adb/config.txt", "hello", TextEncoding.UTF_8)

        assertNotNull(root.written)
        assertEquals("hello", String(root.written!!, Charsets.UTF_8))
    }

    @Test
    fun `save is refused when root is disabled`() = runBlocking {
        val failure = assertThrows(EditorException::class.java) {
            runBlocking {
                gateway(FakeRoot(), allowed = false).save("/data/adb/config.txt", "x", TextEncoding.UTF_8)
            }
        }
        assertEquals("没有权限保存此文件", failure.message)
    }

    @Test
    fun `refuses to overwrite an oversized root file`() = runBlocking {
        val root = FakeRoot(meta = RootMeta(false, EditorFileGateway.MAX_EDITABLE_BYTES + 1))
        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway(root).save("/data/adb/big.txt", "x", TextEncoding.UTF_8) }
        }
        assertEquals("文件超过 5 MB，仅支持只读预览，无法保存", failure.message)
    }
}
