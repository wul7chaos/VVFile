package com.vvfile.app.feature.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

class EditorFileGatewayTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gateway = EditorFileGateway()

    private val gbk: Charset = Charset.forName("GBK")

    private fun newFile(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `reads utf8 file with utf8 encoding`() = runBlocking {
        val content = "第一行\n第二行\nthird line"
        val file = newFile("utf8.txt", content.toByteArray(Charsets.UTF_8))

        val document = gateway.read(file.absolutePath)

        assertEquals(content, document.text)
        assertEquals(TextEncoding.UTF_8, document.encoding)
        assertFalse(document.truncated)
        assertFalse(document.readOnly)
    }

    @Test
    fun `falls back to gbk when strict utf8 decoding fails`() = runBlocking {
        val content = "中文编码测试，你好！"
        val file = newFile("gbk.txt", content.toByteArray(gbk))

        val document = gateway.read(file.absolutePath)

        assertEquals(content, document.text)
        assertEquals(TextEncoding.GBK, document.encoding)
    }

    @Test
    fun `save keeps utf8 encoding and round trips`() = runBlocking {
        val file = temp.newFile("roundtrip.txt")
        val content = "标题\nemoji 🚀\n结束"

        gateway.save(file.absolutePath, content, TextEncoding.UTF_8)
        val document = gateway.read(file.absolutePath)

        assertEquals(content, document.text)
        assertEquals(TextEncoding.UTF_8, document.encoding)
    }

    @Test
    fun `save writes gbk bytes when opened as gbk`() = runBlocking {
        val original = "中文内容"
        val file = newFile("gbk-roundtrip.txt", original.toByteArray(gbk))
        val document = gateway.read(file.absolutePath)
        assertEquals(TextEncoding.GBK, document.encoding)

        val edited = "$original\n追加一行"
        gateway.save(file.absolutePath, edited, document.encoding)

        assertEquals(edited, String(file.readBytes(), gbk))
        val reloaded = gateway.read(file.absolutePath)
        assertEquals(edited, reloaded.text)
        assertEquals(TextEncoding.GBK, reloaded.encoding)
    }

    @Test
    fun `save reports unrepresentable characters instead of replacing them`() {
        val file = temp.newFile("gbk-emoji.txt").apply { writeBytes("abc".toByteArray()) }

        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway.save(file.absolutePath, "表情 😀", TextEncoding.GBK) }
        }

        assertTrue(failure.message!!.contains("UTF-8"))
        assertEquals("abc", file.readText())
    }

    @Test
    fun `large file is read only but loaded in full`() = runBlocking {
        val total = 6 * 1024 * 1024
        val file = newFile("big.log", ByteArray(total) { 'a'.code.toByte() })

        val document = gateway.read(file.absolutePath)

        // 6 MB 超过可编辑上限（只读），但没超过读取上限（不截断）
        assertTrue(document.readOnly)
        assertFalse(document.truncated)
        assertEquals(total, document.text.length)
        assertEquals('a', document.text.first())
    }

    @Test
    fun `beyond load cap is truncated`() = runBlocking {
        val total = EditorFileGateway.MAX_LOAD_BYTES.toInt() + 4096
        val file = newFile("huge.log", ByteArray(total) { 'a'.code.toByte() })

        val document = gateway.read(file.absolutePath)

        assertTrue(document.readOnly)
        assertTrue(document.truncated)
        assertEquals(EditorFileGateway.MAX_LOAD_BYTES.toInt(), document.text.length)
    }

    @Test
    fun `truncation keeps multibyte characters intact`() = runBlocking {
        val unit = "中".toByteArray(Charsets.UTF_8)
        val limitBytes = EditorFileGateway.MAX_LOAD_BYTES.toInt()
        val count = limitBytes / unit.size + 2
        val bytes = ByteArray(count * unit.size)
        for (index in 0 until count) {
            System.arraycopy(unit, 0, bytes, index * unit.size, unit.size)
        }
        val file = newFile("big-cjk.txt", bytes)

        val document = gateway.read(file.absolutePath)

        assertEquals(TextEncoding.UTF_8, document.encoding)
        assertTrue(document.truncated)
        // 截断点落在汉字中间，末尾半个字符被丢弃，其余字符保持完整
        assertEquals(limitBytes / 3, document.text.length)
        assertTrue(document.text.all { it == '中' })
    }

    @Test
    fun `oversized file refuses save to protect the preview`() {
        val total = 6 * 1024 * 1024
        val file = newFile("readonly.txt", ByteArray(total) { 'b'.code.toByte() })

        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway.save(file.absolutePath, "覆盖", TextEncoding.UTF_8) }
        }

        assertTrue(failure.message!!.contains("5 MB"))
        assertEquals(total.toLong(), file.length())
        assertEquals('b'.code.toByte(), file.readBytes().first())
    }

    @Test
    fun `missing file produces readable message`() {
        val missing = File(temp.root, "not-here.txt").absolutePath

        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway.read(missing) }
        }

        assertEquals("文件不存在或已被删除", failure.message)
    }

    @Test
    fun `directory produces readable message`() {
        val directory = temp.newFolder("folder")

        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway.read(directory.absolutePath) }
        }

        assertTrue(failure.message!!.contains("文件夹"))
    }

    @Test
    fun `blank path produces readable message`() {
        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway.read("") }
        }

        assertEquals("路径为空，无法打开", failure.message)
    }

    @Test
    fun `empty file opens as utf8`() = runBlocking {
        val file = temp.newFile("empty.txt")

        val document = gateway.read(file.absolutePath)

        assertEquals("", document.text)
        assertEquals(TextEncoding.UTF_8, document.encoding)
        assertFalse(document.truncated)
    }

    @Test
    fun `utf8 bom is stripped from the text`() = runBlocking {
        val bodiless = "带 BOM 的文本"
        val file = newFile("bom.txt", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bodiless.toByteArray(Charsets.UTF_8))

        val document = gateway.read(file.absolutePath)

        assertEquals(bodiless, document.text)
        assertEquals(TextEncoding.UTF_8, document.encoding)
    }

    @Test
    fun `save creates a file that does not exist yet`() = runBlocking {
        val target = File(temp.root, "created.txt")

        gateway.save(target.absolutePath, "新建内容", TextEncoding.UTF_8)

        assertTrue(target.exists())
        assertEquals("新建内容", gateway.read(target.absolutePath).text)
    }

    @Test
    fun `save into missing directory reports readable message`() {
        val target = File(temp.root, "no-such-dir/file.txt").absolutePath

        val failure = assertThrows(EditorException::class.java) {
            runBlocking { gateway.save(target, "x", TextEncoding.UTF_8) }
        }

        assertTrue(failure.message!!.contains("目标目录不存在"))
    }
}
