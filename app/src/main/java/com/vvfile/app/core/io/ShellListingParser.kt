package com.vvfile.app.core.io

import com.vvfile.app.data.model.FileEntry
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 解析 toybox `ls -la` 与 `stat -c` 的输出。
 *
 * 抽成纯函数便于单测：root 通道在不同设备/ROM 上输出略有差异，
 * 解析失败时宁可丢行，也不要把错误条目塞进列表。
 */
object ShellListingParser {

    // 权限 链接数 属主 属组 大小 日期 时间 名称（名称可含空格）
    private val LIST_LINE = Regex(
        "^(\\S{10})\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$",
    )
    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun parseList(output: String, directory: String, showHidden: Boolean): List<FileEntry> =
        output.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("total ")) return@mapNotNull null
            val match = LIST_LINE.find(line) ?: return@mapNotNull null
            val groups = match.groupValues
            val permissions = groups[1]
            // 字符/块设备等行的大小字段是 major minor 两个数字，结构不同，直接跳过
            if (permissions.firstOrNull() !in listOf('-', 'd', 'l')) return@mapNotNull null
            val size = groups[5].toLongOrNull() ?: 0L
            var name = groups[8]
            val symlink = permissions.startsWith("l")
            if (symlink) name = name.substringBefore(" -> ")
            if (name == "." || name == ".." || name.isEmpty()) return@mapNotNull null
            if (!showHidden && name.startsWith(".")) return@mapNotNull null
            val isDirectory = permissions.startsWith("d")
            FileEntry(
                name = name,
                path = joinPath(directory, name),
                isDirectory = isDirectory,
                size = if (isDirectory) 0L else size,
                lastModified = parseTime(groups[6], groups[7]),
                isSymlink = symlink,
            )
        }.toList()

    /** `stat -c '%F|%s|%Y'`：类型|字节数|秒级时间戳。 */
    fun parseStat(output: String, path: String): FileEntry? {
        val line = output.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val parts = line.trim().split('|')
        if (parts.size < 3) return null
        val type = parts[0]
        val size = parts[1].toLongOrNull() ?: 0L
        val modified = parts[2].toLongOrNull()?.times(1000L) ?: 0L
        val isDirectory = type.contains("directory", ignoreCase = true)
        return FileEntry(
            name = path.substringAfterLast('/').ifEmpty { "/" },
            path = path,
            isDirectory = isDirectory,
            size = if (isDirectory) 0L else size,
            lastModified = modified,
            isSymlink = type.contains("link", ignoreCase = true),
        )
    }

    private fun joinPath(directory: String, name: String): String =
        if (directory == "/") "/$name" else "$directory/$name"

    private fun parseTime(date: String, time: String): Long = runCatching {
        DATE_FORMAT.parse("$date $time")?.time ?: 0L
    }.getOrDefault(0L)
}
