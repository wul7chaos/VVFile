package com.vvfile.app.data.file

import com.vvfile.app.core.path.PathUtils
import com.vvfile.app.core.result.ConflictInfo
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationFailure
import com.vvfile.app.core.result.OperationPhase
import com.vvfile.app.core.result.OperationProgress
import com.vvfile.app.core.result.OperationResult
import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.ShellExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * root 分区的写入通道：cp/mv/rm/mkdir/touch 全部经 `su -c` 执行。
 *
 * 约束：只走 root，不接 Shizuku/ADB/无障碍；压缩/解压暂不支持 root 路径。
 * 命令参数一律经 [ShellExecutor.shellQuote]，避免空格/特殊字符注入。
 */
class ShellFileOperator(
    private val runner: CommandRunner = ShellExecutor(),
    private val asRoot: Boolean = true,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FileOperator {

    override fun copy(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> = transferFlow(sources, targetDir, policy, move = false)

    override fun move(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> = transferFlow(sources, targetDir, policy, move = true)

    override fun delete(paths: List<String>): Flow<OperationEvent> = flow {
        val failures = mutableListOf<OperationFailure>()
        val succeeded = mutableListOf<String>()
        paths.forEachIndexed { index, path ->
            currentCoroutineContext().ensureActive()
            val result = run("rm -rf ${quote(path)}", TIMEOUT_MS)
            if (result.isSuccess) {
                succeeded += path
            } else {
                failures += OperationFailure(path, result.errorText())
            }
            emit(
                OperationEvent.Progress(
                    OperationProgress(
                        phase = OperationPhase.RUNNING,
                        currentName = path.substringAfterLast('/'),
                        processedItems = index + 1,
                        totalItems = paths.size,
                    ),
                ),
            )
        }
        emit(OperationEvent.Finished(OperationResult(succeeded, failures = failures)))
    }.flowOn(io)

    override fun zip(sources: List<String>, targetZip: String): Flow<OperationEvent> =
        unsupportedFlow(targetZip, "root 分区暂不支持压缩")

    override fun unzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> = unsupportedFlow(zipPath, "root 分区暂不支持解压")

    override suspend fun rename(path: String, newName: String): OperationResult =
        withContext(io) {
            val validated = PathUtils.validateFileName(newName)
            val source = PathUtils.normalize(path)
            val parent = source.substringBeforeLast('/', "")
            if (parent.isEmpty()) return@withContext failure(path, "无法确定父目录")
            val target = "$parent/$validated"
            if (exists(target)) return@withContext failure(path, "同名文件已存在")
            val result = run("mv ${quote(source)} ${quote(target)}", TIMEOUT_MS)
            if (result.isSuccess) {
                OperationResult(succeeded = listOf(target))
            } else {
                failure(path, result.errorText())
            }
        }

    override suspend fun createDirectory(parent: String, name: String): OperationResult =
        withContext(io) {
            val validated = PathUtils.validateFileName(name)
            val target = join(parent, validated)
            if (exists(target)) return@withContext failure(target, "同名文件已存在")
            val result = run("mkdir ${quote(target)}", TIMEOUT_MS)
            if (result.isSuccess) OperationResult(succeeded = listOf(target))
            else failure(target, result.errorText())
        }

    override suspend fun createFile(parent: String, name: String): OperationResult =
        withContext(io) {
            val validated = PathUtils.validateFileName(name)
            val target = join(parent, validated)
            if (exists(target)) return@withContext failure(target, "同名文件已存在")
            val result = run("touch ${quote(target)}", TIMEOUT_MS)
            if (result.isSuccess) OperationResult(succeeded = listOf(target))
            else failure(target, result.errorText())
        }

    override suspend fun findConflicts(
        sources: List<String>,
        targetDir: String,
    ): List<ConflictInfo> = withContext(io) {
        sources.mapNotNull { source ->
            val name = source.substringAfterLast('/').ifEmpty { return@mapNotNull null }
            val target = join(targetDir, name)
            if (exists(target)) ConflictInfo(source, target) else null
        }
    }

    override suspend fun findZipConflicts(
        zipPath: String,
        targetDir: String,
    ): List<ConflictInfo> = emptyList()

    // ---------------------------------------------------------------- internals --

    private fun transferFlow(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
        move: Boolean,
    ): Flow<OperationEvent> = flow {
        val succeeded = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<OperationFailure>()
        sources.forEachIndexed { index, source ->
            currentCoroutineContext().ensureActive()
            val name = source.substringAfterLast('/').ifEmpty { "" }
            if (name.isEmpty()) {
                failures += OperationFailure(source, "路径不合法")
                return@forEachIndexed
            }
            var target = join(targetDir, name)
            if (exists(target)) {
                when (policy) {
                    ConflictPolicy.SKIP -> {
                        skipped += source
                        emit(
                            OperationEvent.Progress(
                                OperationProgress(
                                    phase = OperationPhase.RUNNING,
                                    currentName = name,
                                    processedItems = index + 1,
                                    totalItems = sources.size,
                                ),
                            ),
                        )
                        return@forEachIndexed
                    }
                    ConflictPolicy.REPLACE -> run("rm -rf ${quote(target)}", TIMEOUT_MS)
                    ConflictPolicy.RENAME -> target = uniqueTarget(target)
                }
            }
            val command = if (move) {
                "mv -f ${quote(source)} ${quote(target)}"
            } else {
                "cp -a ${quote(source)} ${quote(target)}"
            }
            val result = run(command, TIMEOUT_MS)
            if (result.isSuccess) {
                succeeded += target
            } else {
                failures += OperationFailure(source, result.errorText())
            }
            emit(
                OperationEvent.Progress(
                    OperationProgress(
                        phase = OperationPhase.RUNNING,
                        currentName = name,
                        processedItems = index + 1,
                        totalItems = sources.size,
                    ),
                ),
            )
        }
        emit(OperationEvent.Finished(OperationResult(succeeded, skipped = skipped, failures = failures)))
    }.flowOn(io)

    private suspend fun uniqueTarget(target: String): String {
        val slash = target.lastIndexOf('/')
        val dir = if (slash >= 0) target.substring(0, slash) else ""
        val name = target.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (index < 1000) {
            val candidate = join(dir, "$base ($index)$ext")
            if (!exists(candidate)) return candidate
            index++
        }
        return target
    }

    private suspend fun exists(path: String): Boolean =
        run("test -e ${quote(path)}", 15_000L).isSuccess

    private suspend fun run(command: String, timeoutMs: Long) =
        runner.exec(command, asRoot = asRoot, timeoutMs = timeoutMs)

    private fun unsupportedFlow(path: String, reason: String): Flow<OperationEvent> = flow {
        emit(OperationEvent.Progress(OperationProgress(phase = OperationPhase.PREPARING)))
        emit(
            OperationEvent.Finished(
                OperationResult(failures = listOf(OperationFailure(path, reason))),
            ),
        )
    }

    private fun failure(path: String, reason: String) =
        OperationResult(failures = listOf(OperationFailure(path, reason)))

    private fun join(parent: String, name: String): String =
        if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"

    private fun quote(value: String): String = ShellExecutor.shellQuote(value)

    private fun com.vvfile.app.core.shell.ShellResult.errorText(): String = when {
        !started -> "无法启动 su，请确认已授予 root"
        timedOut -> "命令执行超时"
        stderr.isNotBlank() -> stderr.trim()
        else -> "命令执行失败（exit=$exitCode）"
    }

    companion object {
        private const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
