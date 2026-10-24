package com.vvfile.app.data.file

import com.vvfile.app.core.path.PathUtils
import com.vvfile.app.core.result.ConflictInfo
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationFailure
import com.vvfile.app.core.result.OperationPhase
import com.vvfile.app.core.result.OperationProgress
import com.vvfile.app.core.result.OperationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 应用自身权限下的文件操作实现（java.nio）。
 *
 * 关键约束：
 * - 复制先写同目录临时文件，完成后原子替换，取消/失败不会留下半截目标文件；
 * - 目录复制/删除逐项检查协程取消；
 * - ZIP 解压做 Zip Slip 校验（目标必须落在目标目录内）；
 * - root 路径不在本类处理，后续由 ShellFileOperator 装饰。
 */
class LocalFileOperator(
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
        val result = runDelete(paths) { emit(OperationEvent.Progress(it)) }
        emit(OperationEvent.Finished(result))
    }.flowOn(io)

    override fun zip(sources: List<String>, targetZip: String): Flow<OperationEvent> = flow {
        val result = runZip(sources, targetZip) { emit(OperationEvent.Progress(it)) }
        emit(OperationEvent.Finished(result))
    }.flowOn(io)

    override fun unzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy,
    ): Flow<OperationEvent> = flow {
        val result = runUnzip(zipPath, targetDir, policy) { emit(OperationEvent.Progress(it)) }
        emit(OperationEvent.Finished(result))
    }.flowOn(io)

    override suspend fun rename(path: String, newName: String): OperationResult = withContext(io) {
        runSimple {
            val validated = PathUtils.validateFileName(newName)
            val source = Paths.get(path)
            val parent = source.parent ?: throw IOException("无法确定父目录")
            val target = parent.resolve(validated)
            if (Files.exists(target)) throw IOException("同名文件已存在")
            movePath(source, target)
            listOf(path)
        }
    }

    override suspend fun createDirectory(parent: String, name: String): OperationResult = withContext(io) {
        runSimple {
            val validated = PathUtils.validateFileName(name)
            val target = Paths.get(parent).resolve(validated)
            if (Files.exists(target)) throw IOException("同名文件已存在")
            Files.createDirectory(target)
            listOf(target.toString())
        }
    }

    override suspend fun createFile(parent: String, name: String): OperationResult = withContext(io) {
        runSimple {
            val validated = PathUtils.validateFileName(name)
            val target = Paths.get(parent).resolve(validated)
            if (Files.exists(target)) throw IOException("同名文件已存在")
            Files.createFile(target)
            listOf(target.toString())
        }
    }

    override suspend fun chmod(path: String, mode: String): OperationResult = withContext(io) {
        runSimple {
            val target = Paths.get(path)
            if (!Files.exists(target)) throw IOException("文件不存在")
            Files.setPosixFilePermissions(target, parsePermissions(mode))
            listOf(path)
        }
    }

    private fun parsePermissions(mode: String): Set<PosixFilePermission> {
        val trimmed = mode.trim()
        if (Regex("[0-7]{3,4}").matches(trimmed)) {
            val digits = trimmed.takeLast(3)
            val result = mutableSetOf<PosixFilePermission>()
            val classes = listOf(
                listOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                listOf(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                ),
                listOf(
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
            digits.forEachIndexed { index, char ->
                val value = char.digitToInt()
                if (value and 4 != 0) result += classes[index][0]
                if (value and 2 != 0) result += classes[index][1]
                if (value and 1 != 0) result += classes[index][2]
            }
            return result
        }
        if (!Regex("[r-][w-][x－-][r-][w-][x－-][r-][w-][x－-]").matches(trimmed)) {
            throw IOException("权限格式应为 755 或 rwxr-xr-x")
        }
        val chars = trimmed.replace('－', '-')
        val result = mutableSetOf<PosixFilePermission>()
        val classes = listOf(
            listOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            listOf(
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
            ),
            listOf(
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE,
            ),
        )
        for (index in 0 until 9) {
            if (chars[index] != '-') result += classes[index / 3][index % 3]
        }
        return result
    }

    override suspend fun findConflicts(
        sources: List<String>,
        targetDir: String,
    ): List<ConflictInfo> = withContext(io) {
        val root = Paths.get(targetDir)
        sources.mapNotNull { source ->
            val name = Paths.get(source).fileName?.toString() ?: return@mapNotNull null
            val target = root.resolve(name)
            if (Files.exists(target)) ConflictInfo(source, target.toString()) else null
        }
    }

    override suspend fun findZipConflicts(
        zipPath: String,
        targetDir: String,
    ): List<ConflictInfo> = withContext(io) {
        val root = Paths.get(targetDir)
        ZipFile(Paths.get(zipPath).toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .mapNotNull { entry ->
                    val target = root.resolve(entry.name).normalize()
                    if (!target.startsWith(root)) return@mapNotNull null
                    if (Files.exists(target)) {
                        ConflictInfo(entry.name, target.toString())
                    } else {
                        null
                    }
                }
                .toList()
        }
    }

    // ---------------------------------------------------------------- transfer --

    private fun transferFlow(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
        move: Boolean,
    ): Flow<OperationEvent> = flow {
        val result = runTransfer(sources, targetDir, policy, move) { emit(OperationEvent.Progress(it)) }
        emit(OperationEvent.Finished(result))
    }.flowOn(io)

    private suspend fun runTransfer(
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
        move: Boolean,
        report: suspend (OperationProgress) -> Unit,
    ): OperationResult {
        val succeeded = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<OperationFailure>()
        val root = Paths.get(targetDir)

        if (!Files.isDirectory(root)) {
            return OperationResult(failures = sources.map { OperationFailure(it, "目标不是文件夹") })
        }

        report(OperationProgress(phase = OperationPhase.PREPARING, currentName = "正在统计…"))
        val scans = sources.associateWith { runCatching { scan(it) }.getOrElse { error ->
            failures += OperationFailure(it, error.userMessage())
            Scan(emptyList(), 0L)
        } }
        val totalBytes = scans.values.sumOf { it.totalBytes }
        val totalItems = scans.values.sumOf { it.files.size }
        val tracker = ProgressTracker(totalBytes, totalItems, report)
        tracker.emit("")

        for (source in sources) {
            coroutineContext.ensureActive()
            val scan = scans.getValue(source)
            val sourcePath = Paths.get(source)
            if (!Files.exists(sourcePath)) {
                failures += OperationFailure(source, "源文件不存在")
                continue
            }
            val name = sourcePath.fileName?.toString() ?: continue
            if (sourcePath.normalize() == root.resolve(name).normalize()) {
                failures += OperationFailure(source, "源和目标相同")
                continue
            }
            val target = resolveTarget(root.resolve(name), policy)
            if (target == null) {
                skipped += source
                tracker.skipItems(scan)
                tracker.emit(name)
                continue
            }
            try {
                if (Files.isDirectory(sourcePath)) {
                    copyDirectory(sourcePath, target, tracker, move)
                } else {
                    copyFile(sourcePath, target, name, tracker)
                    if (move) Files.delete(sourcePath)
                }
                succeeded += source
            } catch (cancel: CancellationException) {
                cleanup(target)
                throw cancel
            } catch (error: Exception) {
                failures += OperationFailure(source, error.userMessage())
            }
        }
        tracker.force("")
        return OperationResult(succeeded, skipped, failures)
    }

    private suspend fun copyDirectory(
        source: Path,
        target: Path,
        tracker: ProgressTracker,
        move: Boolean,
    ) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val dest = target.resolve(source.relativize(file).toString())
                copyFileBlocking(file, dest, tracker, file.fileName.toString())
                return FileVisitResult.CONTINUE
            }
        })
        if (move) deleteRecursively(source)
    }

    /** JVM FileVisitor 内不能 suspend，这里用阻塞循环写进度；取消在下一个文件生效。 */
    private fun copyFileBlocking(source: Path, target: Path, tracker: ProgressTracker, label: String) {
        target.parent?.let { Files.createDirectories(it) }
        val temp = tempSibling(target)
        try {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        tracker.addBytes(read.toLong())
                    }
                }
            }
            movePath(temp, target)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw error
        }
        tracker.finishItemSilent()
    }

    private suspend fun copyFile(source: Path, target: Path, label: String, tracker: ProgressTracker) {
        target.parent?.let { Files.createDirectories(it) }
        val temp = tempSibling(target)
        try {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(
                    temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    copyStream(input, output, label, tracker)
                }
            }
            movePath(temp, target)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw error
        }
        tracker.completeItem(label)
    }

    private fun tempSibling(target: Path): Path =
        target.resolveSibling(".vvfile_tmp_${UUID.randomUUID()}_${target.fileName}")

    private suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        label: String,
        tracker: ProgressTracker,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            tracker.addBytes(read.toLong(), label)
        }
    }

    /** RENAME：找第一个不冲突的 "name (n).ext"；REPLACE：删掉旧目标；SKIP：返回 null。 */
    private fun resolveTarget(target: Path, policy: ConflictPolicy): Path? {
        if (!Files.exists(target)) return target
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.REPLACE -> {
                deleteRecursively(target)
                target
            }
            ConflictPolicy.RENAME -> uniquePath(target)
        }
    }

    private fun uniquePath(target: Path): Path {
        val parent = target.parent ?: return target
        val name = target.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = parent.resolve("$base ($index)$ext")
            if (!Files.exists(candidate)) return candidate
            index++
        }
    }

    // ------------------------------------------------------------------ delete --

    private suspend fun runDelete(
        paths: List<String>,
        report: suspend (OperationProgress) -> Unit,
    ): OperationResult {
        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<OperationFailure>()
        report(OperationProgress(phase = OperationPhase.PREPARING, currentName = "正在统计…"))

        var totalBytes = 0L
        var totalItems = 0
        for (path in paths) {
            runCatching { scan(path) }.onSuccess {
                totalBytes += it.totalBytes
                totalItems += it.files.size
            }
        }
        val tracker = ProgressTracker(totalBytes, totalItems, report)

        for (path in paths) {
            coroutineContext.ensureActive()
            try {
                val target = Paths.get(path)
                if (!Files.exists(target)) {
                    failures += OperationFailure(path, "文件不存在")
                    continue
                }
                val scan = runCatching { scan(path) }.getOrNull()
                deleteRecursively(target)
                if (scan != null) {
                    tracker.completeScan(scan, path.substringAfterLast('/'))
                } else {
                    tracker.completeItem(path)
                }
                succeeded += path
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failures += OperationFailure(path, error.userMessage())
            }
        }
        return OperationResult(succeeded, failures = failures)
    }

    // --------------------------------------------------------------------- zip --

    private suspend fun runZip(
        sources: List<String>,
        targetZip: String,
        report: suspend (OperationProgress) -> Unit,
    ): OperationResult {
        val failures = mutableListOf<OperationFailure>()
        val target = Paths.get(targetZip)
        report(OperationProgress(phase = OperationPhase.PREPARING, currentName = "正在统计…"))

        val entries = mutableListOf<Pair<Path, String>>()
        var totalBytes = 0L
        for (source in sources) {
            val sourcePath = Paths.get(source)
            if (!Files.exists(sourcePath)) {
                failures += OperationFailure(source, "文件不存在")
                continue
            }
            val baseName = sourcePath.fileName?.toString() ?: continue
            if (Files.isDirectory(sourcePath)) {
                Files.walk(sourcePath).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val rel = sourcePath.relativize(file).toString().replace('\\', '/')
                        entries += file to "$baseName/$rel"
                        totalBytes += runCatching { Files.size(file) }.getOrDefault(0L)
                    }
                }
            } else {
                entries += sourcePath to baseName
                totalBytes += runCatching { Files.size(sourcePath) }.getOrDefault(0L)
            }
        }

        if (entries.isEmpty() && failures.isEmpty()) {
            return OperationResult(failures = listOf(OperationFailure(targetZip, "没有可压缩的内容")))
        }

        val tracker = ProgressTracker(totalBytes, entries.size, report)
        target.parent?.let { Files.createDirectories(it) }
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(target))).use { zip ->
                for ((file, entryName) in entries) {
                    coroutineContext.ensureActive()
                    val entry = ZipEntry(entryName)
                    zip.putNextEntry(entry)
                    Files.newInputStream(file).use { input ->
                        copyStream(input, zip, entryName, tracker)
                    }
                    zip.closeEntry()
                    tracker.completeItem(entryName)
                }
            }
        } catch (cancel: CancellationException) {
            runCatching { Files.deleteIfExists(target) }
            throw cancel
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(target) }
            failures += OperationFailure(targetZip, error.userMessage())
        }
        return OperationResult(succeeded = listOf(targetZip).filter { failures.isEmpty() }, failures = failures)
    }

    // ------------------------------------------------------------------- unzip --

    private suspend fun runUnzip(
        zipPath: String,
        targetDir: String,
        policy: ConflictPolicy,
        report: suspend (OperationProgress) -> Unit,
    ): OperationResult {
        val root = Paths.get(targetDir)
        val succeeded = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<OperationFailure>()
        report(OperationProgress(phase = OperationPhase.PREPARING, currentName = "正在统计…"))

        ZipFile(Paths.get(zipPath).toFile()).use { zip ->
            val entries = zip.entries().toList()
            val totalBytes = entries.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) }
            val tracker = ProgressTracker(totalBytes, entries.size, report)
            Files.createDirectories(root)

            for (entry in entries) {
                coroutineContext.ensureActive()
                var out = root.resolve(entry.name).normalize()
                // Zip Slip：规范化后必须仍在目标目录内
                if (!out.startsWith(root)) {
                    failures += OperationFailure(entry.name, "非法的压缩包路径")
                    continue
                }
                try {
                    if (entry.isDirectory) {
                        if (Files.exists(out) && !Files.isDirectory(out)) {
                            when (policy) {
                                ConflictPolicy.SKIP -> {
                                    skipped += entry.name
                                    tracker.completeItem(entry.name)
                                    continue
                                }
                                ConflictPolicy.REPLACE -> deleteRecursively(out)
                                ConflictPolicy.RENAME -> out = uniquePath(out)
                            }
                        }
                        Files.createDirectories(out)
                        succeeded += out.toString()
                        tracker.completeItem(entry.name)
                    } else {
                        if (Files.exists(out)) {
                            when (policy) {
                                ConflictPolicy.SKIP -> {
                                    skipped += entry.name
                                    tracker.completeItem(entry.name)
                                    continue
                                }
                                ConflictPolicy.REPLACE -> Unit
                                ConflictPolicy.RENAME -> out = uniquePath(out)
                            }
                        }
                        out.parent?.let { Files.createDirectories(it) }
                        zip.getInputStream(entry).use { input ->
                            Files.newOutputStream(
                                out,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE,
                            ).use { output ->
                                copyStream(input, output, entry.name, tracker)
                            }
                        }
                        succeeded += out.toString()
                        tracker.completeItem(entry.name)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    failures += OperationFailure(entry.name, error.userMessage())
                }
            }
        }
        return OperationResult(succeeded, skipped = skipped, failures = failures)
    }

    // ----------------------------------------------------------------- helpers --

    private fun scan(path: String): Scan {
        val target = Paths.get(path)
        if (!Files.exists(target)) throw IOException("文件不存在")
        if (!Files.isDirectory(target)) {
            return Scan(listOf(target), runCatching { Files.size(target) }.getOrDefault(0L))
        }
        val files = mutableListOf<Path>()
        var total = 0L
        Files.walk(target).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                files.add(file)
                total += runCatching { Files.size(file) }.getOrDefault(0L)
            }
        }
        return Scan(files, total)
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        if (!Files.isDirectory(path)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun movePath(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanup(path: Path) {
        runCatching {
            if (Files.isDirectory(path)) deleteRecursively(path) else Files.deleteIfExists(path)
        }
    }

    private fun Throwable.userMessage(): String = message ?: javaClass.simpleName

    private suspend fun runSimple(block: suspend () -> List<String>): OperationResult =
        try {
            OperationResult(succeeded = block())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            OperationResult(failures = listOf(OperationFailure("", error.userMessage())))
        }

    private data class Scan(val files: List<Path>, val totalBytes: Long)

    private class ProgressTracker(
        private val totalBytes: Long,
        private val totalItems: Int,
        private val report: suspend (OperationProgress) -> Unit,
    ) {
        private var bytes = 0L
        private var items = 0
        private var lastEmitAt = 0L

        suspend fun emit(name: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitAt < EMIT_INTERVAL_MS) return
            lastEmitAt = now
            report(
                OperationProgress(
                    phase = OperationPhase.RUNNING,
                    currentName = name,
                    processedBytes = bytes,
                    totalBytes = totalBytes,
                    processedItems = items,
                    totalItems = totalItems,
                ),
            )
        }

        suspend fun addBytes(count: Long, name: String = "") {
            bytes += count
            emit(name)
        }

        fun addBytes(count: Long) {
            bytes += count
        }

        suspend fun completeItem(name: String) {
            items++
            emit(name, force = true)
        }

        /** 供 JVM FileVisitor 这类非 suspend 上下文使用：只计数，不回调。 */
        fun finishItemSilent() {
            items++
        }

        suspend fun completeScan(scan: Scan, name: String) {
            bytes += scan.totalBytes
            items += scan.files.size
            emit(name, force = true)
        }

        fun skipItems(scan: Scan) {
            bytes += scan.totalBytes
            items += scan.files.size
        }

        suspend fun force(name: String) = emit(name, force = true)
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val EMIT_INTERVAL_MS = 80L
    }
}
