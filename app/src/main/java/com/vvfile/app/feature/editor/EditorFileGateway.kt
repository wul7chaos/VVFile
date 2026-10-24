package com.vvfile.app.feature.editor

import com.vvfile.app.core.path.PathUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/** 编辑器支持的文本编码，识别顺序固定为 UTF-8 → GBK。 */
enum class TextEncoding(
    val label: String,
    val charset: Charset,
) {
    UTF_8("UTF-8", Charsets.UTF_8),
    GBK("GBK", Charset.forName("GBK")),
}

/**
 * 一次打开的结果。
 *
 * [truncated] 为 true 表示文件超过 [EditorFileGateway.MAX_EDITABLE_BYTES]，
 * 只读取了前 [EditorFileGateway.PREVIEW_BYTES] 作为预览，此时 [readOnly] 为 true，
 * 保存必须被拒绝，避免把截断后的内容覆盖回原文件。
 */
data class EditorDocument(
    val text: String,
    val encoding: TextEncoding,
    val truncated: Boolean = false,
) {
    val readOnly: Boolean get() = truncated
}

/** 编辑器领域异常：message 直接是给用户看的文案。 */
class EditorException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * 文本读写的唯一入口。
 *
 * - 读取：严格 UTF-8 解码，失败回退 GBK；> 5 MB 只读预览（截断到 2 MB）。
 * - 保存：沿用打开时的编码，严格编码（不可表示的字符直接报错，不做静默替换）。
 *
 * 所有磁盘操作在 [dispatcher] 上执行；异常统一转成 [EditorException] 的中文文案。
 */
class EditorFileGateway(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun read(path: String): EditorDocument = withContext(dispatcher) {
        val file = resolveForRead(path)
        try {
            if (Files.isDirectory(file)) throw EditorException(MSG_IS_DIRECTORY)
            val size = Files.size(file)
            val truncated = size > MAX_EDITABLE_BYTES
            val limit = if (truncated) PREVIEW_BYTES else size
            val bytes = readPrefix(file, limit)
            val (encoding, text) = decode(bytes, truncated)
            EditorDocument(text = text, encoding = encoding, truncated = truncated)
        } catch (known: EditorException) {
            throw known
        } catch (missing: NoSuchFileException) {
            throw EditorException(MSG_MISSING, missing)
        } catch (denied: AccessDeniedException) {
            throw EditorException(MSG_READ_DENIED, denied)
        } catch (denied: SecurityException) {
            throw EditorException(MSG_READ_DENIED, denied)
        } catch (io: IOException) {
            throw EditorException("读取文件失败：${io.message ?: "未知错误"}", io)
        }
    }

    /**
     * 按打开时的 [encoding] 写回。超过 5 MB 的文件只读预览，禁止保存。
     *
     * @throws EditorException 编码不支持、权限不足、目录不存在等，message 可直出给用户。
     */
    suspend fun save(path: String, text: String, encoding: TextEncoding) = withContext(dispatcher) {
        val file = resolveForSave(path)
        try {
            if (Files.exists(file) && Files.isDirectory(file)) throw EditorException(MSG_IS_DIRECTORY_SAVE)
            val currentSize = if (Files.exists(file)) Files.size(file) else 0L
            if (currentSize > MAX_EDITABLE_BYTES) throw EditorException(MSG_READONLY_SAVE)
            val bytes = try {
                encodeStrict(text, encoding)
            } catch (coding: CharacterCodingException) {
                throw EditorException(
                    "当前编码（${encoding.label}）无法表示部分字符，请改用 UTF-8 保存",
                    coding,
                )
            }
            Files.write(
                file,
                bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        } catch (known: EditorException) {
            throw known
        } catch (denied: AccessDeniedException) {
            throw EditorException(MSG_WRITE_DENIED, denied)
        } catch (denied: SecurityException) {
            throw EditorException(MSG_WRITE_DENIED, denied)
        } catch (missing: NoSuchFileException) {
            throw EditorException("保存失败：目标目录不存在", missing)
        } catch (io: IOException) {
            throw EditorException("保存文件失败：${io.message ?: "未知错误"}", io)
        }
    }

    private fun resolveForRead(raw: String): Path = resolve(raw, MSG_EMPTY_PATH)

    private fun resolveForSave(raw: String): Path = resolve(raw, "路径为空，无法保存")

    private fun resolve(raw: String, emptyMessage: String): Path {
        if (raw.isEmpty()) throw EditorException(emptyMessage)
        return try {
            Paths.get(PathUtils.normalize(raw))
        } catch (invalid: InvalidPathException) {
            throw EditorException(MSG_INVALID_PATH, invalid)
        }
    }

    /** 精确读取前 [limit] 字节，避免把大文件整个读进内存。 */
    private fun readPrefix(file: Path, limit: Long): ByteArray {
        if (limit <= 0L) return ByteArray(0)
        Files.newInputStream(file).use { input ->
            val output = ByteArrayOutputStream(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = limit
            while (remaining > 0L) {
                val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(chunk, 0, read)
                remaining -= read
            }
            return output.toByteArray()
        }
    }

    /**
     * 识别编码并解码。
     *
     * 严格 UTF-8 解码（`CodingErrorAction.REPORT`）成功即 UTF-8，否则回退 GBK；
     * 截断预览时允许末尾留下半个多字节字符，此时按"忽略尾部残字"判定 UTF-8，
     * 避免 2 MB 截断点正好切在汉字中间时被误判成 GBK。
     */
    private fun decode(bytes: ByteArray, truncated: Boolean): Pair<TextEncoding, String> {
        val hasBom = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        val offset = if (hasBom) 3 else 0
        val length = bytes.size - offset
        if (length == 0) return TextEncoding.UTF_8 to ""

        val encoding = when {
            hasBom -> TextEncoding.UTF_8
            decodesAsUtf8(bytes, offset, length, allowPartialTail = false) -> TextEncoding.UTF_8
            truncated && decodesAsUtf8(bytes, offset, length, allowPartialTail = true) -> TextEncoding.UTF_8
            else -> TextEncoding.GBK
        }
        return encoding to decodeAs(encoding, bytes, offset, length, truncated)
    }

    private fun decodesAsUtf8(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        allowPartialTail: Boolean,
    ): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            // 容量按字节数开，足够容纳任意 UTF-8 解码结果
            val chars = CharBuffer.allocate(length + 1)
            // 注意：三参数 decode 在 REPORT 模式下只返回错误结果，不抛异常，必须显式检查
            val decoded = decoder.decode(ByteBuffer.wrap(bytes, offset, length), chars, !allowPartialTail)
            when {
                decoded.isError -> false
                allowPartialTail -> true
                else -> !decoder.flush(chars).isError
            }
        } catch (coding: CharacterCodingException) {
            false
        }
    }

    private fun decodeAs(
        encoding: TextEncoding,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        truncated: Boolean,
    ): String = try {
        when (encoding) {
            TextEncoding.UTF_8 -> {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val chars = CharBuffer.allocate(length + 1)
                // 截断预览时 endOfInput=false，尾部的半个字符直接丢弃
                val decoded = decoder.decode(ByteBuffer.wrap(bytes, offset, length), chars, !truncated)
                if (decoded.isError) throw CharacterCodingException()
                if (!truncated && decoder.flush(chars).isError) throw CharacterCodingException()
                chars.flip()
                chars.toString()
            }

            TextEncoding.GBK -> String(bytes, offset, length, TextEncoding.GBK.charset)
        }
    } catch (coding: CharacterCodingException) {
        // 识别阶段已排除，这里只做兜底：宽松解码，保证文件仍能打开
        String(bytes, offset, length, TextEncoding.GBK.charset)
    }

    private fun encodeStrict(text: String, encoding: TextEncoding): ByteArray {
        val encoder = encoding.charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    companion object {
        /** 超过该大小只做只读预览。 */
        const val MAX_EDITABLE_BYTES: Long = 5L * 1024 * 1024

        /** 只读预览时实际读取的字节数。 */
        const val PREVIEW_BYTES: Long = 2L * 1024 * 1024

        private const val DEFAULT_BUFFER_SIZE = 64 * 1024

        private const val MSG_EMPTY_PATH = "路径为空，无法打开"
        private const val MSG_INVALID_PATH = "路径无效，无法打开"
        private const val MSG_MISSING = "文件不存在或已被删除"
        private const val MSG_READ_DENIED = "没有权限读取此文件"
        private const val MSG_WRITE_DENIED = "没有权限保存此文件"
        private const val MSG_IS_DIRECTORY = "这是一个文件夹，无法作为文本打开"
        private const val MSG_IS_DIRECTORY_SAVE = "这是一个文件夹，无法保存"
        private const val MSG_READONLY_SAVE = "文件超过 5 MB，仅支持只读预览，无法保存"
    }
}
