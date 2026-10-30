package com.vvfile.app.data.file

import com.vvfile.app.core.result.ConflictInfo
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationFailure
import com.vvfile.app.core.result.OperationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 按路径分区选择执行通道：普通目录走 [local]，系统分区 / `Android/data` / `Android/obb`
 * 且 root 可用时走 [root]。没有 root 的设备行为与纯 [LocalFileOperator] 完全一致；
 * 需要 root 又没开时给出明确原因，而不是让它落到 local 上报「文件不存在」
 * （FUSE 对受限目录就是返 ENOENT，本地通道根本说不清）。
 */
class FallbackFileOperator(
    private val local: FileOperator,
    private val root: FileOperator,
    private val rootAvailable: () -> Boolean = { false },
    private val isRootPath: (String) -> Boolean = RootPaths::isRootOnly,
) : FileOperator {

    private fun useRoot(vararg paths: String): Boolean =
        rootAvailable() && paths.any(isRootPath)

    private fun needsRootButUnavailable(vararg paths: String): Boolean =
        !rootAvailable() && paths.any(isRootPath)

    private fun rootRequiredFlow(paths: List<String>): Flow<OperationEvent> = flow {
        emit(
            OperationEvent.Finished(
                OperationResult(failures = paths.map { OperationFailure(it, ROOT_REQUIRED) }),
            ),
        )
    }

    private fun rootRequired(path: String): OperationResult =
        OperationResult(failures = listOf(OperationFailure(path, ROOT_REQUIRED)))

    override fun copy(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> = when {
        useRoot(targetDir, *sources.toTypedArray()) -> root.copy(sources, targetDir, policy)
        needsRootButUnavailable(targetDir, *sources.toTypedArray()) ->
            rootRequiredFlow(sources.ifEmpty { listOf(targetDir) })
        else -> local.copy(sources, targetDir, policy)
    }

    override fun move(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> = when {
        useRoot(targetDir, *sources.toTypedArray()) -> root.move(sources, targetDir, policy)
        needsRootButUnavailable(targetDir, *sources.toTypedArray()) ->
            rootRequiredFlow(sources.ifEmpty { listOf(targetDir) })
        else -> local.move(sources, targetDir, policy)
    }

    override fun delete(paths: List<String>): Flow<OperationEvent> = when {
        useRoot(*paths.toTypedArray()) -> root.delete(paths)
        needsRootButUnavailable(*paths.toTypedArray()) -> rootRequiredFlow(paths)
        else -> local.delete(paths)
    }

    override fun zip(
        sources: List<String>,
        targetZip: String,
        level: Int,
    ): Flow<OperationEvent> = when {
        useRoot(targetZip, *sources.toTypedArray()) -> root.zip(sources, targetZip, level)
        needsRootButUnavailable(targetZip, *sources.toTypedArray()) ->
            rootRequiredFlow(sources.ifEmpty { listOf(targetZip) })
        else -> local.zip(sources, targetZip, level)
    }

    override fun unzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy,
        password: String?,
    ): Flow<OperationEvent> = when {
        useRoot(zipPath, targetDir) -> root.unzip(zipPath, targetDir, policy, password)
        needsRootButUnavailable(zipPath, targetDir) -> rootRequiredFlow(listOf(zipPath))
        else -> local.unzip(zipPath, targetDir, policy, password)
    }

    override suspend fun rename(path: String, newName: String): OperationResult = when {
        useRoot(path) -> root.rename(path, newName)
        needsRootButUnavailable(path) -> rootRequired(path)
        else -> local.rename(path, newName)
    }

    override suspend fun createDirectory(parent: String, name: String): OperationResult = when {
        useRoot(parent) -> root.createDirectory(parent, name)
        needsRootButUnavailable(parent) -> rootRequired(parent)
        else -> local.createDirectory(parent, name)
    }

    override suspend fun createFile(parent: String, name: String): OperationResult = when {
        useRoot(parent) -> root.createFile(parent, name)
        needsRootButUnavailable(parent) -> rootRequired(parent)
        else -> local.createFile(parent, name)
    }

    override suspend fun findConflicts(
        sources: List<String>,
        targetDir: String,
    ): List<ConflictInfo> = when {
        useRoot(targetDir, *sources.toTypedArray()) -> root.findConflicts(sources, targetDir)
        needsRootButUnavailable(targetDir, *sources.toTypedArray()) -> emptyList()
        else -> local.findConflicts(sources, targetDir)
    }

    override suspend fun chmod(path: String, mode: String): OperationResult = when {
        useRoot(path) -> root.chmod(path, mode)
        needsRootButUnavailable(path) -> rootRequired(path)
        else -> local.chmod(path, mode)
    }

    override suspend fun findZipConflicts(
        zipPath: String,
        targetDir: String,
        password: String?,
    ): List<ConflictInfo> = local.findZipConflicts(zipPath, targetDir, password)

    private companion object {
        const val ROOT_REQUIRED = "这个目录需要 ROOT 权限才能修改（侧栏 → 设置 → root 请求策略）"
    }
}
