package com.vvfile.app.feature.editor

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 编辑器直接用应用 uid 读写的通道。
 *
 * 抽成接口只为单测：生产实现是 [NioDirectFileChannel]，测试注入「必定被拒」的实现，
 * 才能在没有 root 的机器上验证 root 回退那几条分支。
 */
interface DirectFileChannel {

    fun exists(path: Path): Boolean

    fun isDirectory(path: Path): Boolean

    fun size(path: Path): Long

    /** 精确读前 [limit] 字节；无权限时抛 `AccessDeniedException`。 */
    fun readPrefix(path: Path, limit: Long): ByteArray

    fun write(path: Path, bytes: ByteArray)
}

object NioDirectFileChannel : DirectFileChannel {

    private const val BUFFER_BYTES = 64 * 1024

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)

    override fun size(path: Path): Long = Files.size(path)

    override fun readPrefix(path: Path, limit: Long): ByteArray {
        if (limit <= 0L) return ByteArray(0)
        Files.newInputStream(path).use { input ->
            val output = ByteArrayOutputStream(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val chunk = ByteArray(BUFFER_BYTES)
            var remaining = limit
            while (remaining > 0L) {
                val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(chunk, 0, read)
                remaining -= read
            }
            return output.toByteArray()
        }
    }

    override fun write(path: Path, bytes: ByteArray) {
        Files.write(
            path,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}
