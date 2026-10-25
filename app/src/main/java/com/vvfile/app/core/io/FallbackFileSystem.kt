package com.vvfile.app.core.io

import com.vvfile.app.core.result.PathAccessDenied
import com.vvfile.app.data.model.FileEntry
import java.io.FileNotFoundException

/**
 * 先走直接 IO，遇到权限拒绝且 root 可用时回退到 [root] 通道。
 * 没有 root 的设备行为与纯 [NioFileSystem] 完全一致。
 *
 * [rootOnly] 用于补一条 FUSE 的坑：`/storage/emulated`、别人的 `Android/data` 这类目录
 * 在应用视角会报 ENOENT（[FileNotFoundException]），而不是 EACCES。只有调用方明确
 * 判定「这条路径必须 root」时，才在 ENOENT 上再试一次 root，避免把真的不存在当成权限问题。
 */
class FallbackFileSystem(
    private val direct: FileSystem,
    private val root: FileSystem,
    private val rootAvailable: () -> Boolean,
    private val rootOnly: (String) -> Boolean = { false },
) : FileSystem {

    override suspend fun list(path: String, showHidden: Boolean): List<FileEntry> =
        try {
            direct.list(path, showHidden)
        } catch (denied: PathAccessDenied) {
            if (rootAvailable()) root.list(path, showHidden) else throw denied
        } catch (missing: FileNotFoundException) {
            if (rootAvailable() && rootOnly(path)) root.list(path, showHidden) else throw missing
        }

    override suspend fun stat(path: String): FileEntry? =
        try {
            direct.stat(path)
        } catch (denied: PathAccessDenied) {
            if (rootAvailable()) root.stat(path) else throw denied
        } catch (missing: FileNotFoundException) {
            if (rootAvailable() && rootOnly(path)) root.stat(path) else throw missing
        }

    /** root-only 路径在直接通道上必然为 false；需要准确判断时用 [stat]。 */
    override fun exists(path: String): Boolean = direct.exists(path)
}
