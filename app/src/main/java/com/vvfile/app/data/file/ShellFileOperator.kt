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
import java.io.File
import kotlinx.coroutines.withContext

/**
 * root 分区的写入通道：cp/mv/rm/mkdir/touch 全部经 `su -c` 执行。
 *
 * 约束：特权操作只走 root；压缩/解压暂不支持 root 路径。
 * 命令参数一律经 [ShellExecutor.shellQuote]，避免空格/特殊字符注入。
 */
class ShellFileOperator(
    private val runner: CommandRunner = ShellExecutor(),
    private val asRoot: Boolean = true,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 解压解码器：应用自己权限的实现，root 只管最后的搬运。 */
    private val local: FileOperator? = null,
    /** 落地缓存目录（app cache）；为 null 时 root 解压直接报不可用。 */
    private val cacheDir: File? = null,
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

    override fun zip(
        sources: List<String>,
        targetZip: String,
        level: Int,
    ): Flow<OperationEvent> =
        unsupportedFlow(targetZip, "root 分区暂不支持压缩")

    /**
     * root 目录解压。
     *
     * 解压本身仍用应用自己的权限跑（[local]），只有「落地」这一步走 `su`：
     * 1. root-only 的包先 `cp` 到应用缓存，否则 [local] 打不开它；
     * 2. 解到缓存里的临时目录；
     * 3. 每个顶层条目 `cp -a` 进目标目录，冲突策略与 [transferFlow] 一致。
     *
     * 只支持 ZIP：7z/RAR 的解码器同样只能读应用有权限的路径，而设备上没有 7z/unrar
     * 命令行工具可借，硬做出来会让「能解」和「解不了」在不同包上随机表现。
     */
    override fun unzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy,
        password: String?,
        entries: List<String>?,
    ): Flow<OperationEvent> = flow {
        val decoder = local
        if (decoder == null || cacheDir == null) {
            emit(OperationEvent.Progress(OperationProgress(phase = OperationPhase.PREPARING)))
            emit(
                OperationEvent.Finished(
                    OperationResult(failures = listOf(OperationFailure(zipPath, ROOT_UNZIP_DISABLED))),
                ),
            )
            return@flow
        }
        emit(OperationEvent.Progress(OperationProgress(phase = OperationPhase.PREPARING, currentName = "正在读取压缩包…")))
        val source = stageArchive(zipPath, cacheDir)
        if (source == null) {
            emit(
                OperationEvent.Finished(
                    OperationResult(failures = listOf(OperationFailure(zipPath, "无法读取压缩包"))),
                ),
            )
            return@flow
        }
        val work = File(cacheDir, "unzip-work-${System.currentTimeMillis()}")
        runCatching { work.mkdirs() }
        try {
            var decodeFailure: String? = null
            decoder.unzip(source.absolutePath, work.absolutePath, ConflictPolicy.REPLACE, password, entries)
                .collect { event ->
                    when (event) {
                        is OperationEvent.Progress -> emit(OperationEvent.Progress(event.progress))
                        is OperationEvent.Finished -> {
                            decodeFailure = event.result.failures.firstOrNull()?.reason
                        }
                    }
                }
            if (decodeFailure != null && work.listFiles().isNullOrEmpty()) {
                emit(
                    OperationEvent.Finished(
                        OperationResult(failures = listOf(OperationFailure(zipPath, decodeFailure!!))),
                    ),
                )
                return@flow
            }
            // 「解压到同名文件夹」时目标目录还不存在，而 cp 不会自己建它
            run("mkdir -p ${quote(targetDir)}", TIMEOUT_MS)
            transferFlow(work.listFiles().orEmpty().map { it.absolutePath }, targetDir, policy, move = false)
                .collect { event -> emit(event) }
            // cp -a 会把应用自己的 uid、600 权限与 caches 组的 setgid 位一起带过去：落在
            // /data 这类目录里之后除了本应用谁也读不了。系统分区里统一改成 root 所有、
            // 目录 755 / 文件 644，和手动写进去的文件一致。
            if (RootPaths.isRootOnly(targetDir)) {
                run(
                    "chown -R 0:0 ${quote(targetDir)} && " +
                        "find ${quote(targetDir)} -type d -exec chmod 755 {} + && " +
                        "find ${quote(targetDir)} -type f -exec chmod 644 {} +",
                    TIMEOUT_MS,
                )
            }
        } finally {
            runCatching { work.deleteRecursively() }
            // 只清我们自己落地的副本，别把用户原有的包删了
            if (source.parentFile?.name == STAGE_DIR) {
                runCatching { source.parentFile?.deleteRecursively() }
            }
        }
    }.flowOn(io)

    /**
     * 把包搬到应用自己读得到的地方。
     *
     * 已经是可读路径就原样返回（调用方据此判断别删原文件）；只有 root 读得到的包复制一份
     * 到缓存目录下的 [STAGE_DIR]。返回 null 表示复制失败。
     */
    private suspend fun stageArchive(zipPath: String, cache: File): File? = withContext(io) {
        val direct = File(zipPath)
        if (direct.canRead() && direct.isFile) return@withContext direct
        val dir = File(cache, STAGE_DIR).apply { runCatching { mkdirs() } }
        val temp = File(dir, "src-${System.currentTimeMillis()}.zip")
        val copied = run("cp -a ${quote(zipPath)} ${quote(temp.absolutePath)}", TIMEOUT_MS)
        if (!copied.isSuccess || !temp.isFile || temp.length() == 0L) {
            runCatching { temp.delete() }
            return@withContext null
        }
        temp
    }

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

    override suspend fun chmod(path: String, mode: String): OperationResult = withContext(io) {
        val trimmed = mode.trim()
        if (!Regex("[0-7]{3,4}").matches(trimmed)) {
            return@withContext failure(path, "权限格式应为 755")
        }
        val result = run("chmod $trimmed ${quote(path)}", TIMEOUT_MS)
        if (result.isSuccess) {
            OperationResult(succeeded = listOf(path))
        } else {
            failure(path, result.errorText())
        }
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
        password: String?,
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
        private const val STAGE_DIR = "unzip-stage"
        private const val ROOT_UNZIP_DISABLED = "root 解压需要应用缓存目录与本地解码器"
    }
}


