package com.vvfile.app.core.io

import com.vvfile.app.core.result.NotADirectory
import com.vvfile.app.core.result.PathAccessDenied
import com.vvfile.app.core.result.PathNotFound
import com.vvfile.app.core.shell.ShellExecutor
import com.vvfile.app.data.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * root/shell 只读通道：通过 `su -c` 执行 `ls`/`stat`，用于浏览应用自身无权访问的系统分区。
 *
 * 特权操作只走 root（su）；[exists] 为同步签名且无法安全阻塞，
 * root-only 路径请用 `stat(path) != null` 判断。
 *
 * 注意：本类只提供读取；root 分区的写入/删除/重命名由后续 ShellFileOperator 负责。
 */
class ShellFileSystem(
    private val executor: ShellExecutor = ShellExecutor(),
    private val asRoot: Boolean = true,
) : FileSystem {

    override suspend fun list(path: String, showHidden: Boolean): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val result = executor.exec("ls -la ${ShellExecutor.shellQuote(path)}", asRoot = asRoot)
            if (result.exitCode != 0) throw result.toDomainError(path)
            ShellListingParser.parseList(result.stdout, path, showHidden)
        }

    override suspend fun stat(path: String): FileEntry? = withContext(Dispatchers.IO) {
        val result = executor.exec(
            "stat -c '%F|%s|%Y' ${ShellExecutor.shellQuote(path)}",
            asRoot = asRoot,
        )
        if (result.exitCode != 0) {
            if (result.stderr.contains("No such file", ignoreCase = true)) return@withContext null
            throw result.toDomainError(path)
        }
        ShellListingParser.parseStat(result.stdout, path)
    }

    override fun exists(path: String): Boolean = false

    private fun com.vvfile.app.core.shell.ShellResult.toDomainError(path: String): Throwable {
        val message = stderr.ifBlank { "命令执行失败（exit=$exitCode）" }
        return when {
            message.contains("No such file", ignoreCase = true) -> PathNotFound(path)
            message.contains("Not a directory", ignoreCase = true) -> NotADirectory(path)
            else -> PathAccessDenied(path)
        }
    }
}
