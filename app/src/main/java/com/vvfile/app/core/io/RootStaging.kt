package com.vvfile.app.core.io

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * root-only 文件的「落地缓存」：把读不到的文件复制一份到应用缓存目录，
 * 交给只认路径的组件用（`ZipFile`、`BitmapFactory`、ExoPlayer）。
 *
 * 只用于**只读**场景。文本编辑器不经过这里——它要改的是原文件，
 * 拿缓存副本编辑再写回属于另一套语义（见 `EditorFileGateway` 的 root 回退）。
 *
 * 缓存名带大小与修改时间，源文件一变就换新副本；同名旧副本顺手删掉，
 * 目录里最多留 [MAX_ENTRIES] 份，超了按最久未使用淘汰。
 */
class RootStaging(
    private val channel: RootFileChannel = SuRootFileChannel(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cacheRoot: File = defaultCacheRoot(),
) {

    /**
     * 需要 root 才能读到 [path] 时，返回一份可读的本地副本；可以直接读时返回 null。
     *
     * 复制失败也返回 null——调用方照常按原路径打开，让它自己报错，
     * 总比这里吞掉错误、界面一片空白好。
     */
    suspend fun stage(path: String): File? = withContext(io) {
        if (path.isBlank()) return@withContext null
        val direct = File(path)
        if (direct.canRead() && direct.isFile) return@withContext null

        val meta = channel.stat(path) ?: return@withContext null
        if (meta.isDirectory) return@withContext null

        val dir = File(cacheRoot, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return@withContext null

        val key = cacheKey(path, meta)
        val cached = File(dir, key)
        if (cached.isFile && cached.length() == meta.size) {
            cached.setLastModified(System.currentTimeMillis())
            return@withContext cached
        }

        // 同一路径的旧副本（换了大小/时间）先清掉，避免缓存目录越滚越大
        val prefix = key.substringBeforeLast('-')
        runCatching { dir.listFiles { f -> f.name.startsWith("$prefix-") }?.forEach { it.delete() } }

        val temp = File(dir, "$key.part")
        val copied = runCatching { channel.copyTo(path, temp) }.getOrDefault(false)
        if (!copied || !temp.isFile || temp.length() != meta.size) {
            runCatching { temp.delete() }
            return@withContext null
        }
        if (!temp.renameTo(cached)) {
            runCatching { temp.delete() }
            return@withContext cached.takeIf { it.isFile }
        }
        trim(dir)
        cached
    }

    private fun cacheKey(path: String, meta: RootMeta): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val name = path.substringAfterLast('/').ifBlank { "file" }
        val suffix = name.substringAfterLast('.', "").take(8).takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        return "$digest-${meta.size}-${meta.modifiedAt}$suffix"
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val DIR_NAME = "root-stage"
        private const val MAX_ENTRIES = 24

        /** 默认落在应用自己的缓存目录（Android 上 `java.io.tmpdir` 就是它）。 */
        fun defaultCacheRoot(): File {
            val tmp = System.getProperty("java.io.tmpdir")
            return if (tmp.isNullOrBlank()) File("/data/local/tmp") else File(tmp)
        }
    }
}
