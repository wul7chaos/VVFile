package com.vvfile.app.data.file

import com.vvfile.app.core.result.ConflictInfo
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationResult
import kotlinx.coroutines.flow.Flow

/**
 * 按路径分区选择执行通道：普通目录走 [local]，系统分区且 root 可用时走 [root]。
 * 没有 root 的设备行为与纯 [LocalFileOperator] 完全一致。
 */
class FallbackFileOperator(
    private val local: FileOperator,
    private val root: FileOperator,
    private val rootAvailable: () -> Boolean = { false },
    private val isRootPath: (String) -> Boolean = RootPaths::isRootOnly,
) : FileOperator {

    private fun useRoot(vararg paths: String): Boolean =
        rootAvailable() && paths.any(isRootPath)

    override fun copy(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> =
        if (useRoot(targetDir, *sources.toTypedArray())) {
            root.copy(sources, targetDir, policy)
        } else {
            local.copy(sources, targetDir, policy)
        }

    override fun move(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> =
        if (useRoot(targetDir, *sources.toTypedArray())) {
            root.move(sources, targetDir, policy)
        } else {
            local.move(sources, targetDir, policy)
        }

    override fun delete(paths: List<String>): Flow<OperationEvent> =
        if (useRoot(*paths.toTypedArray())) root.delete(paths) else local.delete(paths)

    override fun zip(sources: List<String>, targetZip: String): Flow<OperationEvent> =
        if (useRoot(targetZip, *sources.toTypedArray())) {
            root.zip(sources, targetZip)
        } else {
            local.zip(sources, targetZip)
        }

    override fun unzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> =
        if (useRoot(zipPath, targetDir)) {
            root.unzip(zipPath, targetDir, policy)
        } else {
            local.unzip(zipPath, targetDir, policy)
        }

    override suspend fun rename(path: String, newName: String): OperationResult =
        if (useRoot(path)) root.rename(path, newName) else local.rename(path, newName)

    override suspend fun createDirectory(parent: String, name: String): OperationResult =
        if (useRoot(parent)) root.createDirectory(parent, name) else local.createDirectory(parent, name)

    override suspend fun createFile(parent: String, name: String): OperationResult =
        if (useRoot(parent)) root.createFile(parent, name) else local.createFile(parent, name)

    override suspend fun findConflicts(
        sources: List<String>,
        targetDir: String,
    ): List<ConflictInfo> =
        if (useRoot(targetDir, *sources.toTypedArray())) {
            root.findConflicts(sources, targetDir)
        } else {
            local.findConflicts(sources, targetDir)
        }

    override suspend fun findZipConflicts(
        zipPath: String,
        targetDir: String,
    ): List<ConflictInfo> = local.findZipConflicts(zipPath, targetDir)
}
