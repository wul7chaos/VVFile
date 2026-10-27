package com.vvfile.app.feature.archive

import com.vvfile.app.core.archive.ArchivePasswordException
import com.vvfile.app.core.archive.RarArchive
import com.vvfile.app.core.archive.SevenZipArchive
import com.vvfile.app.core.archive.ZipCentralDirectory
import com.vvfile.app.core.archive.ZipEntryReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** 压缩包里的一个条目。[name] 是包内全路径（`a/b.txt`，目录以 `/` 结尾）。 */
data class ArchiveEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    /** 条目带密码：列表标出来，点开时先要密码。 */
    val encrypted: Boolean = false,
) {
    /** 列表里显示的短名。 */
    val displayName: String get() = name.trimEnd('/').substringAfterLast('/')
}

/**
 * 压缩包索引：把包内条目读成可逐层浏览的列表。
 *
 * ZIP（含 APK / JAR / APKS）走 `ZipFile`，加密条目走自己写的 [ZipEntryReader]；
 * TAR 与 TAR.GZ 走 [TarReader]；7z 走 [SevenZipReader]。
 * 只做纯计算 + 读包，不涉及 UI；[children] 是纯函数，单测直接跑。
 */
object ArchiveIndex {

    /**
     * 读整个条目表；损坏/不存在时抛异常，交给调用方显示错误态。
     *
     * [password] 只有加密文件头需要——7z 的 `-mhe`、RAR 的 `-hp` 才会连列表都挡住。
     */
    fun read(path: String, password: String? = null): List<ArchiveEntryInfo> {
        val file = File(path)
        return when (ArchiveFormats.of(file)) {
            ArchiveKind.TAR -> TarReader.read(file, gzipped = false)
            ArchiveKind.TAR_GZ -> TarReader.read(file, gzipped = true)
            ArchiveKind.SEVEN_Z -> SevenZipArchive.list(file, password).map { entry ->
                ArchiveEntryInfo(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modifiedAt = entry.modifiedAt,
                )
            }
            ArchiveKind.RAR -> RarArchive.list(file, password).map { entry ->
                ArchiveEntryInfo(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modifiedAt = entry.modifiedAt,
                    encrypted = entry.encrypted,
                )
            }
            // ZIP 以及「扩展名不认识、但确实是 ZIP」的情况（自解压 .exe）
            ArchiveKind.ZIP, null -> readZip(path)
        }
    }

    private fun readZip(path: String): List<ArchiveEntryInfo> {
        val file = File(path)
        // 中央目录自己读一遍：java.util.zip 不暴露加密位，Android 14+ 的 ZipFile
        // 甚至拒绝打开带加密条目的包（invalid CEN header）。
        // 它还能纠正自解压包的偏移，所以**只要读得出来就用它**，不再多走一遍 ZipFile
        val central = ZipCentralDirectory.read(file)
        if (!central.isNullOrEmpty()) {
            return central.map { entry ->
                ArchiveEntryInfo(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modifiedAt = entry.modifiedAt,
                    encrypted = entry.encrypted,
                )
            }
        }
        // Zip64 / 结构不标准：交给 java.util.zip，读不动时由它抛错
        return ZipFile(file).use { zip ->
            zip.entries().asSequence().map { entry ->
                ArchiveEntryInfo(
                    name = entry.name,
                    isDirectory = entry.isDirectory || entry.name.endsWith("/"),
                    size = entry.size.coerceAtLeast(0L),
                    modifiedAt = entry.time.coerceAtLeast(0L),
                )
            }.toList()
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
     * 条目不存在、没有数据块（软链接）、带密码但没给密码、解压失败都会让结果说清楚：
     * - 要密码 / 密码错：抛 [ArchivePasswordException]，由 UI 弹输入框；
     * - 其它（CRC 坏、条目不存在、软链接）：返回 null。
     */
    fun extractTo(
        path: String,
        entryName: String,
        targetDir: File,
        password: String? = null,
    ): File? {
        val safeName = entryName.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        val target = File(targetDir, sanitize(safeName))
        val file = File(path)
        return try {
            val ok = when (ArchiveFormats.of(file)) {
                ArchiveKind.TAR -> TarReader.extract(
                    file, gzipped = false, entryName = entryName, target = target,
                )
                ArchiveKind.TAR_GZ -> TarReader.extract(
                    file, gzipped = true, entryName = entryName, target = target,
                )
                ArchiveKind.SEVEN_Z -> SevenZipArchive.extract(
                    file, entryName = entryName, target = target, password = password,
                )
                ArchiveKind.RAR -> RarArchive.extract(
                    file, entryName = entryName, target = target, password = password,
                )
                ArchiveKind.ZIP, null -> extractZip(path, entryName, target, password)
            }
            target.takeIf { ok }
        } catch (error: ArchivePasswordException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }

    private fun extractZip(path: String, entryName: String, target: File, password: String?): Boolean {
        val file = File(path)
        val entry = ZipCentralDirectory.entryOf(file, entryName)
        if (entry != null) {
            if (entry.isDirectory) return false
            // 加密与否都走自己的读取器：它认 ZipCrypto / WinZip AES，
            // 明文条目只是少一层解密
            ZipEntryReader.extract(file, entry, target, password)
            return true
        }
        // 中央目录读不出来（Zip64 等）时退回 java.util.zip
        ZipFile(path).use { zip ->
            val zipEntry: ZipEntry = zip.getEntry(entryName) ?: return false
            if (zipEntry.isDirectory) return false
            zip.getInputStream(zipEntry).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return true
    }

    private fun sanitize(name: String): String = name.replace('/', '_').replace('\\', '_')
}
