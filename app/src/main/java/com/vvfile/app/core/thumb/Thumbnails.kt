package com.vvfile.app.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 列表缩略图：内存 LRU + 磁盘缓存 + 有限并发解码。
 *
 * 设计取向是「宁可慢一点也别卡主线程」：
 * - 解码一律在 [Dispatchers.IO] 上跑，最多同时 [MAX_PARALLEL] 张，快速滑动时不会把 CPU 打满；
 * - 列表滑走时协程被取消，排队中的请求直接丢掉（位图已经过了缓存就是顺手存下）；
 * - 失败的条目记进 [failed]，同一张坏图不会每次滚回来都重试一次；
 * - 解码失败/还没加载完，调用方拿到的就是 null，界面继续显示原来的分类图标。
 *
 * 缓存分层：内存里存解码后的 [Bitmap]（按字节数计容量），磁盘上存 PNG（保留透明通道），
 * 进程重启后第二层还在，重新进目录基本是「读文件 → 显示」。
 */
object Thumbnails {

    /** 同时解码的线程数。视频取帧比图片贵，3 个足够把列表铺满又不抢 UI。 */
    private const val MAX_PARALLEL = 3

    /** 磁盘缓存上限；超了按「最久没被写过」从旧文件删起，直到降到 3/4。 */
    private const val DISK_LIMIT_BYTES = 64L * 1024 * 1024

    /** 每写这么多次清一次磁盘，而不是每写一张都去 listFiles。 */
    private const val PRUNE_EVERY = 32

    /** 失败记忆条数上限，防止在几万张坏图的目录里把集合撑爆。 */
    private const val FAILED_LIMIT = 2000

    private val gate = Semaphore(MAX_PARALLEL)

    private val memory: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(memoryBudget()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    private val failed: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val diskWrites = AtomicInteger()

    @Volatile
    private var cacheDir: File? = null

    /** 在 Application 里调一次；重复调用无副作用。 */
    fun init(context: Context) {
        if (cacheDir != null) return
        val dir = File(context.applicationContext.cacheDir, "thumbs")
        runCatching { dir.mkdirs() }
        cacheDir = dir
        // 上次进程留下的缓存可能已经超限（比如突然没空间），启动时先清一遍，别占着房间等
        Thread {
            runCatching { prune(dir) }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** 同步取内存里的那一张，用于滚动回来时立刻显示、不等协程。 */
    fun peek(path: String, size: Long, lastModified: Long): Bitmap? =
        memory.get(ThumbnailMath.cacheKey(path, size, lastModified))

    /**
     * 取缩略图：内存 → 磁盘 → 现解码。
     *
     * 返回 null 表示「这张没有缩略图」（格式不支持、文件没了、没有读权限……），
     * 调用方继续用图标显示即可，不需要区分原因。
     */
    suspend fun load(
        path: String,
        size: Long,
        lastModified: Long,
        isVideo: Boolean,
    ): Bitmap? {
        val key = ThumbnailMath.cacheKey(path, size, lastModified)
        memory.get(key)?.let { return it }
        if (failed.contains(key)) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                // 排队期间别的行可能已经解出同一张（两个窗格看同一目录）
                memory.get(key)?.let { return@withPermit it }

                readFromDisk(key)?.let { bitmap ->
                    memory.put(key, bitmap)
                    return@withPermit bitmap
                }

                val decoded = decode(File(path), isVideo)
                if (decoded == null) {
                    rememberFailure(key)
                    return@withPermit null
                }
                memory.put(key, decoded)
                writeToDisk(key, decoded)
                decoded
            }
        }
    }

    private fun memoryBudget(): Int {
        val heap = Runtime.getRuntime().maxMemory()
        return (heap / 8).coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024).toInt()
    }

    private fun rememberFailure(key: String) {
        if (failed.size >= FAILED_LIMIT) failed.clear()
        failed.add(key)
    }

    private fun decode(file: File, isVideo: Boolean): Bitmap? = try {
        if (!file.isFile) {
            null
        } else if (isVideo) {
            decodeVideoFrame(file)
        } else {
            decodeImageFile(file)
        }
    } catch (e: OutOfMemoryError) {
        // 单张图把堆撑爆时先腾地方；这次仍然放弃，下回滚回来还有机会
        memory.evictAll()
        null
    } catch (e: Exception) {
        null
    }

    private fun decodeImageFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ThumbnailMath.inSampleSize(width, height)
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return scaleDown(raw)
    }

    private fun decodeVideoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // -1 让系统挑「有代表性的一帧」；个别机型/封装返回空，再退回第 0 秒
            val frame = retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val (width, height) = ThumbnailMath.scaledSize(bitmap.width, bitmap.height)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun diskFile(key: String): File? = cacheDir?.let { File(it, "$key.png") }

    private fun readFromDisk(key: String): Bitmap? {
        val file = diskFile(key) ?: return null
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        if (bitmap == null) file.delete()
        return bitmap
    }

    private fun writeToDisk(key: String, bitmap: Bitmap) {
        val dir = cacheDir ?: return
        val target = File(dir, "$key.png")
        val temp = File(dir, "$key.tmp")
        try {
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            return
        }
        if (diskWrites.incrementAndGet() % PRUNE_EVERY == 0) prune(dir)
    }

    /** 磁盘缓存超限时删最旧的，直到降到上限的 3/4（留出余量，避免每次都清）。 */
    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT_BYTES) return
        val target = DISK_LIMIT_BYTES * 3 / 4
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
