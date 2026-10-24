package com.vvfile.app.data.file

import android.os.StatFs
import com.vvfile.app.core.io.FileSystem
import com.vvfile.app.core.io.NioFileSystem
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.StorageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地文件系统仓库。首版只支持应用可直接访问的目录；root 分区读写由 M3 的
 * ShellFileSystem 接管（见 docs/02-架构设计.md）。
 *
 * 读取逻辑已下沉到 [NioFileSystem]；本类保持 M0 的公开 API 不变
 * （`list`、`storageStats`、`exists`），新增 `stat`。
 */
class FileRepository(
    private val fileSystem: FileSystem = NioFileSystem(),
) {

    suspend fun list(path: String, showHidden: Boolean): List<FileEntry> =
        fileSystem.list(path, showHidden)

    /** 单条元数据；路径不存在返回 `null`，无权限抛 PathAccessDenied。 */
    suspend fun stat(path: String): FileEntry? = fileSystem.stat(path)

    fun exists(path: String): Boolean = fileSystem.exists(path)

    suspend fun storageStats(path: String): StorageStats = withContext(Dispatchers.IO) {
        val stat = StatFs(path)
        StorageStats(totalBytes = stat.totalBytes, availableBytes = stat.availableBytes)
    }
}
