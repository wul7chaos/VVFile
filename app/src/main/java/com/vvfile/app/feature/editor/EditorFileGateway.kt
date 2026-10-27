package com.vvfile.app.feature.editor

import com.vvfile.app.core.io.RootFileChannel
import com.vvfile.app.core.io.SuRootFileChannel
import com.vvfile.app.core.path.PathUtils
import com.vvfile.app.core.shell.RootAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * [readOnly] 为 true 表示文件超过 [EditorFileGateway.MAX_EDITABLE_BYTES]，不允许保存
 * （避免把截断后的内容覆盖回原文件，也避免编辑器在超大文本上卡死）。
 *
 * [truncated] 为 true 表示文件还超过了 [EditorFileGateway.MAX_LOAD_BYTES]，
 * 只读了前 [EditorFileGateway.MAX_LOAD_BYTES] 字节。
 */
data class EditorDocument(
    val text: String,
    val encoding: TextEncoding,
    val truncated: Boolean = false,
    val readOnly: Boolean = false,
)

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
 * - 应用 uid 读不到（`/data/adb` 这类 root-only 目录）时，经 [root] 通道用 `su` 读，
 *   属性面板能看、能删的目录，点开文件不该报「没有权限」。
 *
 * 所有磁盘操作在 [dispatcher] 上执行；异常统一转成 [EditorException] 的中文文案。
 */
class EditorFileGateway(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val root: RootFileChannel = SuRootFileChannel(),
    private val rootAllowed: () -> Boolean = { RootAccess.allowed() },
    private val direct: DirectFileChannel = NioDirectFileChannel,
) {

    suspend fun read(path: String, forced: TextEncoding? = null): EditorDocument = withContext(dispatcher) {
        val file = resolveForRead(path)
        try {
            if (direct.isDirectory(file)) throw EditorException(MSG_IS_DIRECTORY)
            val size = direct.size(file)
            buildDocument(size, forced) { limit -> direct.readPrefix(file, limit) }
        } catch (known: EditorException) {
            throw known
        } catch (missing: NoSuchFileException) {
            throw EditorException(MSG_MISSING, missing)
        } catch (denied: AccessDeniedException) {
            readViaRoot(path, forced, denied)
        } catch (denied: SecurityException) {
            readViaRoot(path, forced, denied)
        } catch (io: IOException) {
            throw EditorException("读取文件失败：${io.message ?: "未知错误"}", io)
        }
    }

    /**
     * 直接通道被拒后的兜底：`stat` 拿大小，再用 `su -c cat` 流式读前若干字节。
     *
     * 目录判定也走 root，因为对被拒绝的路径 `Files.isDirectory` 只会静默返回 false。
     */
    private suspend fun readViaRoot(
        path: String,
        forced: TextEncoding?,
        cause: Throwable,
    ): EditorDocument {
        if (!rootAllowed()) throw EditorException(MSG_READ_DENIED, cause)
        val normalized = PathUtils.normalize(path)
        val meta = try {
            root.stat(normalized)
        } catch (error: Exception) {
            throw EditorException("root 读取失败：${error.message ?: "未知错误"}", error)
        } ?: throw EditorException(MSG_READ_DENIED, cause)
        if (meta.isDirectory) throw EditorException(MSG_IS_DIRECTORY)
        return try {
            buildDocument(meta.size, forced) { limit -> root.readPrefix(normalized, limit) }
        } catch (known: EditorException) {
            throw known
        } catch (error: Exception) {
            throw EditorException("root 读取失败：${error.message ?: "未知错误"}", error)
        }
    }

    /**
     * 按 [size] 决定只读/截断，再读字节解码。
     *
     * 两个门槛是分开的：
     * - readOnly：超过 5 MB 就不允许保存（编辑器不适合改这么大的文本）；
     * - truncated：超过 16 MB 只读前 16 MB，避免把整个文件读进内存。
     * 大文件用「按行虚拟化的只读查看器」显示（见 LargeTextReader），秒开、可滚动。
     */
    private suspend fun buildDocument(
        size: Long,
        forced: TextEncoding?,
        read: suspend (Long) -> ByteArray,
    ): EditorDocument {
        val readOnly = size > MAX_EDITABLE_BYTES
        val truncated = size > MAX_LOAD_BYTES
        val limit = if (truncated) MAX_LOAD_BYTES else size
        val bytes = read(limit)
        val (encoding, text) = if (forced != null) {
            forced to String(bytes, forced.charset)
        } else {
            decode(bytes, truncated)
        }
        return EditorDocument(text = text, encoding = encoding, truncated = truncated, readOnly = readOnly)
    }

    /**
     * 按打开时的 [encoding] 写回。超过 5 MB 的文件只读预览，禁止保存。
     *
     * @throws EditorException 编码不支持、权限不足、目录不存在等，message 可直出给用户。
     */
    suspend fun save(path: String, text: String, encoding: TextEncoding) = withContext(dispatcher) {
        val file = resolveForSave(path)
        val bytes = try {
            encodeStrict(text, encoding)
        } catch (coding: CharacterCodingException) {
            throw EditorException(
                "当前编码（${encoding.label}）无法表示部分字符，请改用 UTF-8 保存",
                coding,
            )
        }
        try {
            if (direct.exists(file) && direct.isDirectory(file)) throw EditorException(MSG_IS_DIRECTORY_SAVE)
            val currentSize = if (direct.exists(file)) direct.size(file) else 0L
            if (currentSize > MAX_EDITABLE_BYTES) throw EditorException(MSG_READONLY_SAVE)
            direct.write(file, bytes)
        } catch (known: EditorException) {
            throw known
        } catch (denied: AccessDeniedException) {
            writeViaRoot(path, bytes, denied)
        } catch (denied: SecurityException) {
            writeViaRoot(path, bytes, denied)
        } catch (missing: NoSuchFileException) {
            throw EditorException("保存失败：目标目录不存在", missing)
        } catch (io: IOException) {
            throw EditorException("保存文件失败：${io.message ?: "未知错误"}", io)
        }
    }

    /** 直接通道写不进去时的兜底：落缓存文件再让 root 覆盖过去（保留原文件权限位）。 */
    private suspend fun writeViaRoot(path: String, bytes: ByteArray, cause: Throwable) {
        if (!rootAllowed()) throw EditorException(MSG_WRITE_DENIED, cause)
        val normalized = PathUtils.normalize(path)
        val meta = try {
            root.stat(normalized)
        } catch (error: Exception) {
            throw EditorException("root 保存失败：${error.message ?: "未知错误"}", error)
        }
        if (meta?.isDirectory == true) throw EditorException(MSG_IS_DIRECTORY_SAVE)
        if (meta != null && meta.size > MAX_EDITABLE_BYTES) throw EditorException(MSG_READONLY_SAVE)
        try {
            root.writeAll(normalized, bytes)
        } catch (error: Exception) {
            throw EditorException("root 保存失败：${error.message ?: "未知错误"}", error)
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
        /** 超过该大小就不允许保存（只读打开）。 */
        const val MAX_EDITABLE_BYTES: Long = 5L * 1024 * 1024

        /** 真正的读取上限：超过则只读前这么多字节。 */
        const val MAX_LOAD_BYTES: Long = 16L * 1024 * 1024

        /**
         * 超过这个字符数就改用「按行虚拟化的只读查看器」。
         *
         * 单个 Text/BasicTextField 渲染几十万字符会让主线程卡住十几秒、并且随时可能 OOM，
         * 所以 200k 字符（约 400 KB 中文）以上一律走虚拟化路径。
         */
        const val LARGE_TEXT_CHARS: Int = 200_000

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
