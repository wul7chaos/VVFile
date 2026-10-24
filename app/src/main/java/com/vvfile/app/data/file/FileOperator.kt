package com.vvfile.app.data.file

import com.vvfile.app.core.result.ConflictInfo
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationResult
import kotlinx.coroutines.flow.Flow

/**
 * 批量文件操作入口。复制/移动/删除/压缩/解压返回冷 Flow：
 * 收集时执行，支持取消；UI 应在 IO 之外的 scope 收集并展示进度。
 */
interface FileOperator {
    fun copy(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
    ): Flow<OperationEvent>

    fun move(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
    ): Flow<OperationEvent>

    fun delete(paths: List<String>): Flow<OperationEvent>

    fun zip(
        sources: List<String>,
        targetZip: String,
        level: Int = java.util.zip.Deflater.DEFAULT_COMPRESSION,
    ): Flow<OperationEvent>

    fun unzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy = ConflictPolicy.REPLACE,
    ): Flow<OperationEvent>

    suspend fun rename(path: String, newName: String): OperationResult

    suspend fun createDirectory(parent: String, name: String): OperationResult

    suspend fun createFile(parent: String, name: String): OperationResult

    /** 修改权限；[mode] 支持 "755" 或 "rwxr-xr-x"。 */
    suspend fun chmod(path: String, mode: String): OperationResult

    /** 列出会在目标目录发生冲突的源；UI 据此弹出覆盖/跳过/重命名选择。 */
    suspend fun findConflicts(sources: List<String>, targetDir: String): List<ConflictInfo>

    /** 解压前探测 zip 内条目与目标目录的同名冲突。 */
    suspend fun findZipConflicts(zipPath: String, targetDir: String): List<ConflictInfo>
}
