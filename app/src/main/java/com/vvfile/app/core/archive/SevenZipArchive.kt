package com.vvfile.app.core.archive

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * 7-Zip（`.7z`）读取。
 *
 * 容器解析、LZMA / LZMA2 / BCJ / Delta 过滤器、AES-256-CBC 解密都交给
 * `commons-compress` + `xz`：这部分是纯字节流水线，照着 7-Zip 的 C 源码
 * 重写一遍再调 bug 不划算。VVFile 只做三件事——列条目、解条目、
 * 把密码异常翻译成人话。
 *
 * 7z 有两种加密形态：
 * - **只加密数据**（默认 `-p`）：目录照常列出来，读某一条时才要密码；
 * - **连文件头一起加密**（`-mhe=on`）：连列表都要密码。
 *
 * 7z 的 AES 没有校验字节，密码错只能等解压失败才发现，所以错误信息写成
 * 「密码不正确或压缩包已损坏」，不假装能百分百分清。
 */
object SevenZipArchive {

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
    )

    /**
     * 一次打开的 7z 会话：条目列表 + 按条目取流。
     *
     * 7z 是分「文件夹（folder）」顺序解压的容器，`commons-compress` 内部会自己
     * 定位到目标条目，所以这里可以按任意顺序取流；但存档必须一直开到读完为止，
     * 因此用 [Closeable] 包住，别把流漏到会话外面去用。
     */
    class Session internal constructor(
        private val archive: SevenZFile,
        val entries: List<Entry>,
        private val handles: Map<String, SevenZArchiveEntry>,
        private val hadPassword: Boolean,
    ) : Closeable {

        fun open(name: String): InputStream {
            val handle = handles[name] ?: throw IOException("压缩包里没有 $name")
            return PasswordTranslatingStream(archive.getInputStream(handle), hadPassword)
        }

        override fun close() {
            archive.close()
        }
    }

    /**
     * 解压阶段的密码异常翻译。
     *
     * 7z 在「打开」时不报密码错，是等真正读数据时抛：没给密码抛
     * `PasswordRequiredException`，给了错密码则解出乱码，再以
     * `CorruptedInputException` 之类的形式炸出来。这里统一翻译成
     * [ArchivePasswordRequiredException] / [ArchivePasswordWrongException]，
     * 上层的处理逻辑才不会看到英文异常。
     */
    private class PasswordTranslatingStream(
        source: InputStream,
        private val hadPassword: Boolean,
    ) : java.io.FilterInputStream(source) {

        override fun read(): Int = translate { super.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            translate { super.read(buffer, offset, length) }

        private inline fun translate(block: () -> Int): Int = try {
            block()
        } catch (needed: PasswordRequiredException) {
            throw ArchivePasswordRequiredException(needed.message ?: "压缩包需要密码")
        } catch (error: IOException) {
            if (hadPassword) throw ArchivePasswordWrongException("密码不正确或压缩包已损坏")
            throw error
        }
    }

    /** 文件头签名 `37 7A BC AF 27 1C`。 */
    fun matches(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(6)
            var filled = 0
            while (filled < header.size) {
                val read = input.read(header, filled, header.size - filled)
                if (read < 0) break
                filled += read
            }
            filled == header.size && SevenZFile.matches(header, header.size)
        }
    } catch (_: IOException) {
        false
    }

    /** 打开会话；需要密码时抛 [ArchivePasswordRequiredException]。 */
    fun open(file: File, password: String?): Session {
        val secret = password?.takeIf { it.isNotEmpty() }
        val archive = try {
            if (secret == null) SevenZFile(file) else SevenZFile(file, secret.toCharArray())
        } catch (needed: PasswordRequiredException) {
            throw ArchivePasswordRequiredException(needed.message ?: "压缩包需要密码")
        } catch (error: IOException) {
            // 给了密码还读不动：7z 头解出来是乱码时走这里
            if (secret != null) throw ArchivePasswordWrongException("密码不正确或压缩包已损坏")
            throw error
        }
        return try {
            val entries = mutableListOf<Entry>()
            val handles = LinkedHashMap<String, SevenZArchiveEntry>()
            for (handle in archive.entries) {
                val entry = handle.toEntry()
                if (entry.name.isEmpty()) continue
                entries += entry
                handles[entry.name] = handle
            }
            Session(archive, entries, handles, secret != null)
        } catch (error: Throwable) {
            runCatching { archive.close() }
            throw error
        }
    }

    /** 读条目表；需要密码时抛 [ArchivePasswordRequiredException]。 */
    fun list(file: File, password: String?): List<Entry> = open(file, password).use { it.entries }

    /**
     * 内容要不要密码。
     *
     * 两种加密形态都照顾到：文件头加密在 [open] 就报 Required；
     * 只加密数据时目录能列出来，得真读一个字节才会露馅（顺带解出第一块，
     * 成本比整包解压小得多，且只在这个包被点开解压时探一次）。
     */
    fun requiresPassword(file: File): Boolean {
        val session = try {
            open(file, null)
        } catch (needed: ArchivePasswordRequiredException) {
            return true
        }
        session.use { opened ->
            val first = opened.entries.firstOrNull { !it.isDirectory && it.size > 0 } ?: return false
            return try {
                opened.open(first.name).use { it.read() }
                false
            } catch (needed: ArchivePasswordRequiredException) {
                true
            } catch (error: IOException) {
                false
            }
        }
    }

    /** 把单条解到 [target]；条目不存在或解压失败返回 false。 */
    fun extract(file: File, entryName: String, target: File, password: String?): Boolean {
        target.parentFile?.mkdirs()
        return open(file, password).use { session ->
            val entry = session.entries.firstOrNull { it.name == entryName && !it.isDirectory }
                ?: return@use false
            target.outputStream().buffered().use { output ->
                session.open(entry.name).use { input -> input.copyTo(output) }
            }
            true
        }
    }

    private fun SevenZArchiveEntry.toEntry(): Entry {
        val directory = isDirectory || name.endsWith("/")
        val modified = runCatching {
            if (hasLastModifiedDate) lastModifiedTime.toMillis() else 0L
        }.getOrDefault(0L)
        return Entry(
            name = name,
            isDirectory = directory,
            size = if (directory) 0L else size.coerceAtLeast(0L),
            modifiedAt = modified,
        )
    }
}
