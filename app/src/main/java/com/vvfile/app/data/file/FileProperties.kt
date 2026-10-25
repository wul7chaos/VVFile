package com.vvfile.app.data.file

import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.core.format.TimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributes

/** 属性面板要展示的字段；读取失败时对应字段为 "—"。 */
data class FileProperties(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val permissions: String,
    val owner: String,
    val readable: Boolean,
    val writable: Boolean,
) {
    val sizeText: String get() = if (isDirectory) "—" else SizeFormatter.format(sizeBytes)
    val modifiedText: String get() = TimeFormatter.format(modifiedAt)
    val typeText: String get() = if (isDirectory) "文件夹" else "文件"
}

object FilePropertyReader {

    suspend fun read(path: String): FileProperties = withContext(Dispatchers.IO) {
        val target = Paths.get(path)
        val attributes = runCatching {
            Files.readAttributes(target, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        val symlink = runCatching { Files.isSymbolicLink(target) }.getOrDefault(false)
        val size = runCatching { Files.size(target) }.getOrDefault(0L)
        val modified = runCatching { Files.getLastModifiedTime(target).toMillis() }.getOrDefault(0L)
        val permissions = symbolicMode(attributes)
        FileProperties(
            name = target.fileName?.toString() ?: path,
            path = path,
            isDirectory = runCatching { Files.isDirectory(target) }.getOrDefault(false),
            isSymlink = symlink,
            sizeBytes = size,
            modifiedAt = modified,
            permissions = permissions,
            owner = attributes?.owner()?.name ?: "—",
            readable = runCatching { Files.isReadable(target) }.getOrDefault(false),
            writable = runCatching { Files.isWritable(target) }.getOrDefault(false),
        )
    }
}

/**
 * POSIX 权限 → `rwxr-xr-x`；读不到属性时返回 `—`。
 *
 * 属性文件（属性面板、批量属性）与批量改权限共用同一套映射，
 * 否则「属性里显示的」和「改权限回填的」会不一致。
 */
internal fun symbolicMode(attributes: PosixFileAttributes?): String {
    val permissions = attributes?.permissions() ?: return "—"
    val names = permissions.mapTo(HashSet()) { it.toString() }
    fun pos(name: String, allowed: Char, denied: Char = '-'): Char =
        if (names.any { it.startsWith(name) }) allowed else denied
    return buildString {
        append(pos("OWNER_READ", 'r'))
        append(pos("OWNER_WRITE", 'w'))
        append(pos("OWNER_EXECUTE", 'x'))
        append(pos("GROUP_READ", 'r'))
        append(pos("GROUP_WRITE", 'w'))
        append(pos("GROUP_EXECUTE", 'x'))
        append(pos("OTHERS_READ", 'r'))
        append(pos("OTHERS_WRITE", 'w'))
        append(pos("OTHERS_EXECUTE", 'x'))
    }
}
