package com.vvfile.app.feature.archive

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 压缩包里的一个条目。[name] 是包内全路径（`a/b.txt`，目录以 `/` 结尾）。 */
data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
) {
    /** 列表里显示的短名。 */
    val displayName: String get() = name.trimEnd('/').substringAfterLast('/')
}

/**
 * ZIP 索引：把包内条目读成可逐层浏览的列表。
 *
 * 只做纯计算 + 读 zip，不涉及 UI；[children] 是纯函数，单测直接跑。
 */
object ZipIndex {

    /** 读整个条目表；损坏/不存在时抛异常，交给调用方显示错误态。 */
    fun read(zipPath: String): List<ZipEntryInfo> = ZipFile(zipPath).use { zip ->
        zip.entries().asSequence().map { entry ->
            ZipEntryInfo(
                name = entry.name,
                isDirectory = entry.isDirectory || entry.name.endsWith("/"),
                size = entry.size.coerceAtLeast(0L),
                modifiedAt = entry.time.coerceAtLeast(0L),
            )
        }.toList()
    }

    /**
     * 列出某一层的内容。[dir] 为空表示包根；返回的是「下一层」的条目，
     * 深层文件按第一段聚合成目录项（ZIP 不一定有显式的目录条目）。
     */
    fun children(entries: List<ZipEntryInfo>, dir: String): List<ZipEntryInfo> {
        val prefix = if (dir.isEmpty()) "" else dir.trimEnd('/') + "/"
        val seen = LinkedHashMap<String, ZipEntryInfo>()
        for (entry in entries) {
            if (!entry.name.startsWith(prefix) || entry.name == prefix) continue
            val rest = entry.name.removePrefix(prefix)
            if (rest.isEmpty()) continue
            val segment = rest.substringBefore('/')
            val childName = prefix + segment
            val isDirectory = rest.contains('/') || entry.isDirectory
            val existing = seen[childName]
            if (existing == null) {
                seen[childName] = entry.copy(
                    name = if (isDirectory) "$childName/" else childName,
                    isDirectory = isDirectory,
                    // 目录的大小没有意义，置 0
                    size = if (isDirectory) 0L else entry.size,
                )
            } else if (!isDirectory && existing.isDirectory) {
                // 先遇到目录聚合、后遇到同名文件的极端情况：保留目录
                continue
            }
        }
        return seen.values.sortedWith(
            compareByDescending<ZipEntryInfo> { it.isDirectory }.thenBy { it.displayName.lowercase() },
        )
    }

    /** 包内相对路径的父级；`a/b/c.txt` → `a/b`，根返回空串。 */
    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "" else trimmed.substring(0, index)
    }

    /**
     * 把单个条目解到 [targetDir]，返回落地文件。
     *
     * 只取包内路径的最后一段做文件名，天然避免 `../` 这类穿越；
     * 条目不存在或解压失败返回 null。
     */
    fun extractTo(zipPath: String, entryName: String, targetDir: File): File? {
        val safeName = entryName.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        val target = File(targetDir, sanitize(safeName))
        return runCatching {
            ZipFile(zipPath).use { zip ->
                val entry: ZipEntry = zip.getEntry(entryName) ?: return null
                if (entry.isDirectory) return null
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target
        }.getOrNull()
    }

    private fun sanitize(name: String): String = name.replace('/', '_').replace('\\', '_')
}
