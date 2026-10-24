package com.vvfile.app.core.io

import com.vvfile.app.core.path.PathUtils
import com.vvfile.app.core.result.NotADirectory
import com.vvfile.app.core.result.PathAccessDenied
import com.vvfile.app.core.result.PathNotFound
import com.vvfile.app.data.model.FileEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * 直接 IO 实现（应用自身权限）。所有磁盘访问在 [dispatcher] 上执行，
 * 失败统一转成领域异常。root/shell 通道不在这里，见 `data/file/ShellFileSystem.kt`（M3）。
 *
 * 刻意不在访问前用 `Files.exists` 做门禁：该 API 会把权限错误吞成 false，
 * 导致无权限目录被误报成"不存在"。这里直接发起访问，按异常类型区分领域错误。
 */
class NioFileSystem(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileSystem {

    override suspend fun list(path: String, showHidden: Boolean): List<FileEntry> = withContext(dispatcher) {
        if (path.isEmpty()) throw PathNotFound(path)
        readEntries(toPath(path), path, showHidden)
    }

    override suspend fun stat(path: String): FileEntry? = withContext(dispatcher) {
        // 空路径会被 JVM 解析为"当前目录"，语义上应视为不存在
        if (path.isEmpty()) return@withContext null
        val target = toPath(path)
        try {
            target.toEntry()
        } catch (denied: AccessDeniedException) {
            throw PathAccessDenied(path)
        } catch (denied: SecurityException) {
            throw PathAccessDenied(path)
        } catch (missing: NoSuchFileException) {
            // 路径不存在，或探测与读取之间被删除
            null
        }
    }

    override fun exists(path: String): Boolean =
        if (path.isEmpty()) {
            false
        } else {
            try {
                Files.exists(toPath(path))
            } catch (invalid: InvalidPathException) {
                false
            } catch (denied: SecurityException) {
                false
            }
        }

    private fun toPath(raw: String): Path = Paths.get(PathUtils.normalize(raw))

    private fun readEntries(dir: Path, rawPath: String, showHidden: Boolean): List<FileEntry> {
        val entries = ArrayList<FileEntry>()
        try {
            Files.newDirectoryStream(dir).use { stream ->
                for (child in stream) {
                    val name = child.fileName?.toString() ?: continue
                    if (!showHidden && PathUtils.isHidden(name)) continue
                    entries += child.readEntryOrNull() ?: continue
                }
            }
        } catch (denied: AccessDeniedException) {
            throw PathAccessDenied(rawPath)
        } catch (denied: SecurityException) {
            throw PathAccessDenied(rawPath)
        } catch (missing: NoSuchFileException) {
            throw PathNotFound(rawPath)
        } catch (notDir: NotDirectoryException) {
            throw NotADirectory(rawPath)
        } catch (other: FileSystemException) {
            // 某些 provider 对"传了文件路径"只给通用错误码，这里补回语义
            if (Files.exists(dir) && !Files.isDirectory(dir)) throw NotADirectory(rawPath)
            throw other
        }
        return entries
    }

    /** 单个子项读元数据失败（断链、子项无权限）时跳过，避免一个坏条目毁掉整个目录列表。 */
    private fun Path.readEntryOrNull(): FileEntry? =
        try {
            toEntry()
        } catch (denied: AccessDeniedException) {
            null
        } catch (missing: NoSuchFileException) {
            null
        }

    private fun Path.toEntry(): FileEntry {
        val symbolicLink = Files.isSymbolicLink(this)
        val attributes = attributesFollowingLinks()
        val directory = attributes.isDirectory
        val (permissions, owner) = posixInfo()
        return FileEntry(
            name = fileName?.toString().orEmpty(),
            path = toAbsolutePath().toString(),
            isDirectory = directory,
            size = if (directory) 0L else attributes.size(),
            lastModified = attributes.lastModifiedTime().toMillis(),
            isSymlink = symbolicLink,
            permissions = permissions,
            owner = owner,
        )
    }

    /** 外部存储（FUSE）可能不支持 POSIX 属性，失败时返回 null 不影响列表。 */
    private fun Path.posixInfo(): Pair<String?, String?> = runCatching {
        val attributes = Files.readAttributes(this, PosixFileAttributes::class.java)
        val granted = attributes.permissions()
        val text = buildString {
            listOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            ).forEachIndexed { index, permission ->
                if (permission in granted) {
                    append("rwx"[index % 3])
                } else {
                    append('-')
                }
            }
        }
        text to attributes.owner().name
    }.getOrElse { null to null }

    /** 默认跟随符号链接（`/sdcard` 这类入口必须仍可按目录进入）；断链回退到不跟随。 */
    private fun Path.attributesFollowingLinks(): BasicFileAttributes =
        try {
            Files.readAttributes(this, BasicFileAttributes::class.java)
        } catch (missing: NoSuchFileException) {
            Files.readAttributes(this, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
}
