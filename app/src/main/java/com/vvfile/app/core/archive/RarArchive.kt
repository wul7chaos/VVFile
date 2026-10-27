package com.vvfile.app.core.archive

import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.exception.WrongPasswordException
import com.github.junrar.rarfile.FileHeader
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * RAR（含 RAR4 / RAR5 / 自解压）读取。
 *
 * RAR 的解压算法（RAR3 的 PPMd、RAR5 的私有字典压缩）没有公开规范，自己写不现实，
 * 交给纯 Java 的 `junrar`：它同时认 RAR4、RAR5，也能处理前面挂了一段 PE stub 的
 * 自解压包（`WinRAR 生成的 .exe` 就是这么长的）。
 *
 * 密码：`-p` 只加密数据（目录照常列），`-hp` 连文件头一起加密（连列表都要密码）。
 * 两种形态 junrar 都用 [WrongPasswordException] 表达，这里按「给没给密码」翻译：
 * 没给 → 要密码，给了 → 密码不正确。
 */
object RarArchive {

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
        val encrypted: Boolean,
    )

    /** 一次打开的 RAR 会话；RAR 是顺序容器，但 junrar 会自己定位到目标条目。 */
    class Session internal constructor(
        private val archive: Archive,
        val entries: List<Entry>,
        private val handles: Map<String, FileHeader>,
        private val hadPassword: Boolean,
    ) : Closeable {

        /** 把 [name] 的内容写进 [output]；密码问题翻译成统一的异常。 */
        fun extractTo(name: String, output: OutputStream) {
            val handle = handles[name] ?: throw IOException("压缩包里没有 $name")
            try {
                archive.extractFile(handle, output)
            } catch (wrong: WrongPasswordException) {
                if (hadPassword) {
                    throw ArchivePasswordWrongException("密码不正确或压缩包已损坏")
                }
                throw ArchivePasswordRequiredException()
            } catch (error: RarException) {
                throw IOException(error.message ?: "RAR 解压失败", error)
            }
        }

        override fun close() {
            archive.close()
        }
    }

    /** 打开会话；文件头加密时抛 [ArchivePasswordRequiredException]。 */
    fun open(file: File, password: String?): Session {
        val secret = password?.takeIf { it.isNotEmpty() }
        val archive = try {
            if (secret == null) Archive(file) else Archive(file, secret)
        } catch (wrong: WrongPasswordException) {
            // 走到这里只可能是文件头加密：没密码就要密码，给了就是错的
            if (secret == null) throw ArchivePasswordRequiredException()
            throw ArchivePasswordWrongException("密码不正确或压缩包已损坏")
        } catch (error: RarException) {
            throw IOException(error.message ?: "无法读取 RAR 压缩包", error)
        }
        return try {
            val entries = mutableListOf<Entry>()
            val handles = LinkedHashMap<String, FileHeader>()
            for (header in archive.fileHeaders) {
                val entry = header.toEntry()
                if (entry.name.isEmpty()) continue
                entries += entry
                handles[entry.name] = header
            }
            Session(archive, entries, handles, secret != null)
        } catch (error: Throwable) {
            runCatching { archive.close() }
            throw error
        }
    }

    fun list(file: File, password: String?): List<Entry> = open(file, password).use { it.entries }

    /** 把单条解到 [target]；条目不存在或解压失败返回 false。 */
    fun extract(file: File, entryName: String, target: File, password: String?): Boolean {
        target.parentFile?.mkdirs()
        return open(file, password).use { session ->
            if (session.entries.none { it.name == entryName && !it.isDirectory }) return@use false
            target.outputStream().buffered().use { output -> session.extractTo(entryName, output) }
            true
        }
    }

    private fun FileHeader.toEntry(): Entry = Entry(
        // RAR 里目录也以 `/` 结尾
        name = fileName,
        isDirectory = isDirectory || fileName.endsWith("/"),
        size = if (isDirectory) 0L else fullUnpackSize.coerceAtLeast(0L),
        modifiedAt = runCatching { mTime?.time ?: 0L }.getOrDefault(0L),
        encrypted = isEncrypted,
    )
}
