package com.vvfile.app.data.file

import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.core.format.TimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes

/**
 * 多选时的聚合属性（对齐 MT 的多选属性面板）。
 *
 * 与单条 [FileProperties] 分开：多选要算的是「合计大小 + 文件数/文件夹数」，
 * 文件夹必须递归统计，跟单条只报目录本身大小是两种语义。
 */
data class AggregateProperties(
    val names: List<String>,
    /** 参与统计的绝对路径，改权限时直接用，免得 UI 再传一遍。 */
    val paths: List<String>,
    val directory: String,
    val totalBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
    val newestModifiedAt: Long,
    /** 所有条目权限一致时给出 `rwxr-xr-x`，不一致为 null。 */
    val commonPermissions: String?,
    /** 所有条目所有者一致时给出所有者，不一致为 null。 */
    val commonOwner: String?,
    /** 统计时跳过了无权限/已消失的条目，数字只是下限。 */
    val partial: Boolean,
) {
    /** MT 的写法：`2.99M (3132722)`，小字号下也能一眼看出量级和精确值。 */
    val sizeText: String get() = "${SizeFormatter.formatCompact(totalBytes)} ($totalBytes)"

    val modifiedText: String get() = TimeFormatter.format(newestModifiedAt)

    /** 名称列：最多列 8 个，多余用 `…` 收尾，避免弹窗被撑成一屏。 */
    val nameText: String
        get() = if (names.size <= NAME_PREVIEW_LIMIT) {
            names.joinToString(", ")
        } else {
            names.take(NAME_PREVIEW_LIMIT).joinToString(", ") + ", …"
        }

    private companion object {
        const val NAME_PREVIEW_LIMIT = 8
    }
}

object AggregatePropertyReader {

    /** 递归统计的节点上限：几万个文件的目录也不至于让弹窗转半天。 */
    internal const val MAX_NODES = 200_000

    suspend fun read(paths: List<String>, directory: String): AggregateProperties =
        withContext(Dispatchers.IO) {
            val totals = Totals()
            val names = ArrayList<String>(paths.size)
            val modes = LinkedHashSet<String>()
            val owners = LinkedHashSet<String>()
            var partial = false

            for (raw in paths) {
                if (totals.nodes > MAX_NODES) {
                    partial = true
                    break
                }
                val path = runCatching { Paths.get(raw) }.getOrNull() ?: continue
                names += path.fileName?.toString() ?: raw

                val attributes = readTopLevel(path)
                modes += symbolicMode(attributes)
                owners += attributes?.owner()?.name ?: "—"
                if (attributes == null) partial = true

                if (!runCatching { Files.isDirectory(path) }.getOrDefault(false)) {
                    totals.file(
                        bytes = runCatching { Files.size(path) }.getOrDefault(0L),
                        modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
                    )
                    continue
                }

                // 选中的文件夹本身也算一个文件夹
                totals.dir(runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L))
                runCatching { walk(path, totals) }.onFailure { partial = true }
            }
            if (totals.nodes > MAX_NODES) partial = true

            AggregateProperties(
                names = names,
                paths = paths,
                directory = directory,
                totalBytes = totals.bytes,
                fileCount = totals.files,
                dirCount = totals.dirs,
                newestModifiedAt = totals.newest,
                commonPermissions = modes.singleOrNull(),
                commonOwner = owners.singleOrNull(),
                partial = partial,
            )
        }

    private fun readTopLevel(path: Path): PosixFileAttributes? = runCatching {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()

    private fun walk(root: Path, totals: Totals) {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    totals.file(
                        bytes = if (attrs.isRegularFile) attrs.size() else 0L,
                        modified = attrs.lastModifiedTime().toMillis(),
                    )
                    return if (totals.nodes > MAX_NODES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root) totals.dir(attrs.lastModifiedTime().toMillis())
                    return if (totals.nodes > MAX_NODES) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                // 没有权限的子目录跳过，不让整个统计失败
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.SKIP_SUBTREE
            },
        )
    }

    /** 统计过程中的累加器；[nodes] 同时是溢出保护的计数器。 */
    private class Totals {
        var bytes = 0L
        var files = 0
        var dirs = 0
        var newest = 0L
        var nodes = 0

        fun file(bytes: Long, modified: Long) {
            nodes++
            files++
            this.bytes += bytes
            newest = maxOf(newest, modified)
        }

        fun dir(modified: Long) {
            nodes++
            dirs++
            newest = maxOf(newest, modified)
        }
    }
}
