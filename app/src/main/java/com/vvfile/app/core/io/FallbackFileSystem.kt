package com.vvfile.app.core.io

import com.vvfile.app.core.result.PathAccessDenied
import com.vvfile.app.data.model.FileEntry

/**
 * 先走直接 IO，遇到权限拒绝且 root 可用时回退到 [root] 通道。
 * 没有 root 的设备行为与纯 [NioFileSystem] 完全一致。
 */
class FallbackFileSystem(
    private val direct: FileSystem,
    private val root: FileSystem,
    private val rootAvailable: () -> Boolean,
) : FileSystem {

    override suspend fun list(path: String, showHidden: Boolean): List<FileEntry> =
        try {
            direct.list(path, showHidden)
        } catch (denied: PathAccessDenied) {
            if (rootAvailable()) root.list(path, showHidden) else throw denied
        }

    override suspend fun stat(path: String): FileEntry? =
        try {
            direct.stat(path)
        } catch (denied: PathAccessDenied) {
            if (rootAvailable()) root.stat(path) else throw denied
        }

    /** root-only 路径在直接通道上必然为 false；需要准确判断时用 [stat]。 */
    override fun exists(path: String): Boolean = direct.exists(path)
}
