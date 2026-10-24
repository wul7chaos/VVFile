package com.vvfile.app.data.file

import android.os.StatFs
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.StorageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 本地文件系统读取。首版仅支持应用可直接访问的目录；
 * root 分区读写由 M3 的 ShellFileSystem 接管（见 docs/02-架构设计.md）。
 */
class FileRepository {

    suspend fun list(path: String, showHidden: Boolean): List<FileEntry> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists()) throw FileNotFoundException("目录不存在：$path")
        if (!dir.isDirectory) throw IOException("不是文件夹：$path")
        val children = dir.listFiles() ?: throw SecurityException("没有权限读取：$path")
        children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { it.toEntry() }
            .toList()
    }

    suspend fun storageStats(path: String): StorageStats = withContext(Dispatchers.IO) {
        val stat = StatFs(path)
        StorageStats(totalBytes = stat.totalBytes, availableBytes = stat.availableBytes)
    }

    fun exists(path: String): Boolean = File(path).exists()

    private fun File.toEntry(): FileEntry {
        val symlink = runCatching { Files.isSymbolicLink(Paths.get(absolutePath)) }.getOrDefault(false)
        return FileEntry(
            name = name,
            path = absolutePath,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else length(),
            lastModified = lastModified(),
            isSymlink = symlink,
        )
    }
}
