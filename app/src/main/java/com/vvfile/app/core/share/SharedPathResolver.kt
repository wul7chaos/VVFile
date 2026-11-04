package com.vvfile.app.core.share

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.core.shell.RootState
import com.vvfile.app.core.shell.ShellExecutor
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 把其他应用分享/打开的 Uri 解析成本机绝对路径。
 *
 * 只解析、不拷贝：能拿到真实路径就交给文件管理器「定位」，拿不到返回 null，
 * 由调用方提示失败——把一个几百 MB 的文件偷偷复制到缓存里定位没有意义。
 *
 * 解析分三层，越往后越慢，但覆盖的场景越多：
 * 1. [resolve]：快路径。`file://`、`DocumentsContract` 的 docId、
 *    以及老式 `_data` 列。系统文件选择器/相册/下载都走这里，几十毫秒。
 * 2. [deepResolve] 里的 fd 反查：**QQ、Telegram、微信这类用 FileProvider 的应用
 *    只在授权后给一条内容流**，`_data` 列根本不存在（查询直接抛 IllegalArgumentException
 *    或返回 null），原来就卡在这一步——它们给不出路径，但路径其实一直都在：
 *    把流打开拿到 fd 后，`/proc/self/fd/N` 的软链就是文件的真实位置。
 * 3. 还有 root 就按文件名去共享存储里搜一遍，兜住 fd 指向管道（内容由代码现拼）的少数情况。
 */
object SharedPathResolver {

    /**
     * 深解析结果：拿到路径，或者拿不到时给用户看的原因。
     *
     * [needsRoot] 为 true 表示路径落在别的应用的私有目录里（`/data/data/...`），
     * 浏览器得走 root 才能列出来。
     */
    data class Resolution(
        val path: String?,
        val reason: String? = null,
        val needsRoot: Boolean = false,
    )

    private val shell = ShellExecutor()

    /** 从 VIEW / SEND 两类 intent 里取目标 Uri。 */
    fun uriOf(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        }
    }

    /** 快路径解析：只做不需要额外开销的几步。 */
    fun resolveIntent(context: Context, intent: Intent?): String? =
        uriOf(intent)?.let { resolve(context, it) }

    fun resolve(context: Context, uri: Uri): String? {
        val raw = when (uri.scheme?.lowercase()) {
            "file" -> uri.path
            "content" -> fromContent(context, uri)
            else -> null
        } ?: return null
        val path = raw.removePrefix("raw:")
        return path.takeIf { it.startsWith("/") && File(it).exists() }
    }

    /**
     * 慢路径：快路径失败后再试 fd 反查和 root 搜索。
     *
     * 一定要在协程里调用（内部会跑 `su`）；成功返回路径，失败返回原因。
     */
    suspend fun deepResolve(context: Context, uri: Uri?): Resolution {
        if (uri == null) return Resolution(null, "没拿到要定位的文件")
        resolve(context, uri)?.let { return Resolution(it) }

        // FileProvider 的授权内容流：打开一次就能反查出真实路径。
        // fd 是内核给的，路径一定是对的，不再做可读性判断——很多分享的文件本来就在
        // 对方应用的私有目录里，普通 uid 永远读不到，但那正是 root 浏览器的用武之地。
        val viaFd = fromFileDescriptor(context, uri)
        if (viaFd != null) {
            return Resolution(viaFd, needsRoot = !runCatching { File(viaFd).exists() }.getOrDefault(false))
        }

        val name = displayName(context, uri)
        if (!name.isNullOrBlank()) {
            val rootState = runCatching { RootManager.detect() }.getOrNull()
            if (rootState is RootState.Available) {
                findByRoot(name, sizeOf(context, uri))?.let { return Resolution(it) }
            }
        }

        val reason = when {
            name.isNullOrBlank() -> "分享方只提供了内容流，既没有文件名也没有真实路径"
            else -> "分享方只提供了内容流（$name），存储里也没搜到同名文件"
        }
        return Resolution(null, reason)
    }

    /**
     * 打开内容流拿 fd，再读 `/proc/self/fd/N` 的软链。
     *
     * 管道（内容由应用现拼出来）会得到 `pipe:[1234]`，这种情况返回 null，
     * 交给 root 搜索兜底。
     */
    private fun fromFileDescriptor(context: Context, uri: Uri): String? {
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull() ?: return null
        return descriptor.use { opened ->
            runCatching { Os.readlink("/proc/self/fd/" + opened.fd) }.getOrNull()
                ?.removeSuffix(" (deleted)")
                ?.takeIf { it.startsWith("/storage/") || it.startsWith("/data/") || it.startsWith("/mnt/") }
        }
    }

    /** 分享方给的文件名；查询不到返回 null。 */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 分享方给的文件大小；拿不到返回 null，只用于同名文件里挑最像的那个。 */
    private fun sizeOf(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
    }.getOrNull()?.takeIf { it > 0 }


    /**
     * root 兜底：按文件名在共享存储里找，多个同名时按大小挑。
     *
     * `/data/media/0` 与 `/storage/emulated/0` 是同一批文件的两种视图，统一成后者再去重。
     */
    private suspend fun findByRoot(name: String, size: Long?): String? = withContext(Dispatchers.IO) {
        val quoted = ShellExecutor.shellQuote(name)
        val command = "find /storage/emulated/0 /data/media/0 -maxdepth 8 -type f -name " +
            "$quoted 2>/dev/null | head -20"
        val found = runCatching { shell.exec(command, asRoot = true, timeoutMs = 8_000) }
            .getOrNull()?.stdout.orEmpty()
        val candidates = found.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/") }
            .map { path ->
                if (path.startsWith(MEDIA_ROOT_PREFIX)) PRIMARY_ROOT + path.removePrefix(MEDIA_ROOT_PREFIX) else path
            }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return@withContext null
        if (candidates.size == 1 || size == null) return@withContext candidates.first()

        val statCommand = "stat -c '%s %n' " +
            candidates.joinToString(" ") { ShellExecutor.shellQuote(it) } + " 2>/dev/null"
        val statOut = runCatching { shell.exec(statCommand, asRoot = true, timeoutMs = 5_000) }
            .getOrNull()?.stdout.orEmpty()
        val matched = statOut.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            val space = trimmed.indexOf(' ')
            if (space <= 0) return@mapNotNull null
            val bytes = trimmed.substring(0, space).toLongOrNull() ?: return@mapNotNull null
            trimmed.substring(space + 1).takeIf { bytes == size }
        }.firstOrNull()
        matched ?: candidates.first()
    }

    private fun fromContent(context: Context, uri: Uri): String? {
        val authority = uri.authority.orEmpty()
        // 文件管理器/系统选择器走 externalstorage 文档提供者，docId 里直接带卷和路径
        if (authority == EXTERNAL_STORAGE_AUTHORITY) return fromExternalStorage(uri)
        if (authority == MEDIA_AUTHORITY) return fromMediaDocuments(context, uri)
        if (authority == DOWNLOAD_AUTHORITY) return fromDownloads(context, uri)
        // 兜底：很多提供者都实现了 _data 列（QQ/TG 这类 FileProvider 没有，会抛异常）
        return queryData(context, uri)
    }

    /** `primary:Download/a.zip` → `/storage/emulated/0/Download/a.zip`。 */
    private fun fromExternalStorage(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: uri.lastPathSegment
            ?: return null
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2 || parts[1].isBlank()) return null
        val (volume, relative) = parts
        return if (volume.equals(PRIMARY_VOLUME, ignoreCase = true)) {
            "$PRIMARY_ROOT/$relative"
        } else {
            "/storage/$volume/$relative"
        }
    }

    /** `image:1234` → 到 MediaStore 里按 id 查 `_data`。 */
    private fun fromMediaDocuments(context: Context, uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val collection = when (parts[0]) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        return queryData(context, ContentUris.withAppendedId(collection, id))
    }

    private fun fromDownloads(context: Context, uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val id = docId.toLongOrNull() ?: return null
        val downloadUri = ContentUris.withAppendedId(DOWNLOAD_CONTENT_URI, id)
        return queryData(context, downloadUri) ?: queryData(context, uri)
    }

    @Suppress("DEPRECATION")
    private fun queryData(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val MEDIA_AUTHORITY = "com.android.providers.media.documents"
    private const val DOWNLOAD_AUTHORITY = "com.android.providers.downloads.documents"
    private const val PRIMARY_VOLUME = "primary"
    private const val PRIMARY_ROOT = "/storage/emulated/0"
    private const val MEDIA_ROOT_PREFIX = "/data/media/0"
    private val DOWNLOAD_CONTENT_URI: Uri = Uri.parse("content://downloads/public_downloads")
}

