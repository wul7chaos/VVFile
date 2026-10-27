package com.vvfile.app.feature.archive

import com.vvfile.app.core.archive.ZipCentralDirectory
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 压缩包里的一个条目。[name] 是包内全路径（`a/b.txt`，目录以 `/` 结尾）。 */
data class ArchiveEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    /** 条目带密码：列表标出来，点开时也直说「不支持输密码」，而不是抛 CRC 错误。 */
    val encrypted: Boolean = false,
) {
    /** 列表里显示的短名。 */
    val displayName: String get() = name.trimEnd('/').substringAfterLast('/')
}

/**
 * 压缩包索引：把包内条目读成可逐层浏览的列表。
 *
 * ZIP（含 APK / JAR / APKS）走 `ZipFile`；TAR 与 TAR.GZ 走 [TarReader]。
 * 只做纯计算 + 读包，不涉及 UI；[children] 是纯函数，单测直接跑。
 */
object ArchiveIndex {

    /** 读整个条目表；损坏/不存在时抛异常，交给调用方显示错误态。 */
    fun read(path: String): List<ArchiveEntryInfo> = when (ArchiveFormats.kindOf(path)) {
        ArchiveKind.TAR -> TarReader.read(File(path), gzipped = false)
        ArchiveKind.TAR_GZ -> TarReader.read(File(path), gzipped = true)
        // ZIP 以及「扩展名不认识」的情况都交给 ZipFile：读不动时由它抛错
        ArchiveKind.ZIP, null -> readZip(path)
    }

    private fun readZip(path: String): List<ArchiveEntryInfo> {
        val file = File(path)
        // 中央目录自己读一遍：java.util.zip 不暴露加密位，Android 14+ 的 ZipFile
        // 甚至拒绝打开带加密条目的包（invalid CEN header）
        val central = ZipCentralDirectory.read(file)
        val encrypted = central.orEmpty().filter { it.encrypted }.map { it.name }.toSet()
        return try {
            ZipFile(file).use { zip ->
                zip.entries().asSequence().map { entry ->
                    ArchiveEntryInfo(
                        name = entry.name,
                        isDirectory = entry.isDirectory || entry.name.endsWith("/"),
                        size = entry.size.coerceAtLeast(0L),
                        modifiedAt = entry.time.coerceAtLeast(0L),
                        encrypted = entry.name in encrypted,
                    )
                }.toList()
            }
        } catch (error: Exception) {
            // 带密码的包在 Android 14+ 上根本打不开，用中央目录顶上：
            // 至少能看到包内结构和每项的加密标记
            if (!central.isNullOrEmpty() && encrypted.isNotEmpty()) {
                central.map { entry ->
                    ArchiveEntryInfo(
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        modifiedAt = entry.modifiedAt,
                        encrypted = entry.encrypted,
                    )
                }
            } else {
                throw error
            }
        }
    }

    /**
     * 列出某一层的内容。[dir] 为空表示包根；返回的是「下一层」的条目，
     * 深层文件按第一段聚合成目录项（ZIP 不一定有显式的目录条目，TAR 也可能缺）。
     */
    fun children(entries: List<ArchiveEntryInfo>, dir: String): List<ArchiveEntryInfo> {
        val prefix = if (dir.isEmpty()) "" else dir.trimEnd('/') + "/"
        val seen = LinkedHashMap<String, ArchiveEntryInfo>()
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
            compareByDescending<ArchiveEntryInfo> { it.isDirectory }.thenBy { it.displayName.lowercase() },
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
     * 条目不存在、没有数据块（软链接）、带密码或解压失败返回 null。
     */
    fun extractTo(path: String, entryName: String, targetDir: File): File? {
        val safeName = entryName.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        val target = File(targetDir, sanitize(safeName))
        return runCatching {
            when (ArchiveFormats.kindOf(path)) {
                ArchiveKind.TAR -> {
                    if (!TarReader.extract(File(path), gzipped = false, entryName = entryName, target = target)) {
                        return null
                    }
                }
                ArchiveKind.TAR_GZ -> {
                    if (!TarReader.extract(File(path), gzipped = true, entryName = entryName, target = target)) {
                        return null
                    }
                }
                ArchiveKind.ZIP, null -> {
                    // 带密码的条目解不出来：先按中央目录判定，别去撞一堆 CRC 报错
                    val encrypted = ZipCentralDirectory.read(File(path))
                        .orEmpty()
                        .any { it.name == entryName && it.encrypted }
                    if (encrypted) return null
                    ZipFile(path).use { zip ->
                        val entry: ZipEntry = zip.getEntry(entryName) ?: return null
                        if (entry.isDirectory) return null
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            target
        }.getOrNull()
    }

    private fun sanitize(name: String): String = name.replace('/', '_').replace('\\', '_')
}
