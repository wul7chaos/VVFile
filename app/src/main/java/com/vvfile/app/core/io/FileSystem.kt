package com.vvfile.app.core.io

import com.vvfile.app.data.model.FileEntry

/**
 * 文件系统抽象。上层（`FileRepository`、`CopyEngine`）只依赖本接口；
 * 直接 IO（[NioFileSystem]）与后续 root/shell 通道各自实现，互不感知。
 *
 * 约定：
 * - 领域异常见 [com.vvfile.app.core.result.PathNotFound]、[com.vvfile.app.core.result.PathAccessDenied]、
 *   [com.vvfile.app.core.result.NotADirectory]；不向调用方抛裸 `IOException`。
 * - [exists] 是轻量元数据探测，保持同步签名（`FileRepository.exists` 的既有公开 API 是非 suspend，
 *   需要 suspend 语义时用 `stat(path) != null`）。
 */
interface FileSystem {

    /** 列目录内容；[showHidden] 为 false 时过滤 `.` 开头的条目。 */
    suspend fun list(path: String, showHidden: Boolean): List<FileEntry>

    /** 单条元数据；路径不存在返回 `null`。 */
    suspend fun stat(path: String): FileEntry?

    /** 路径是否存在。 */
    fun exists(path: String): Boolean
}
