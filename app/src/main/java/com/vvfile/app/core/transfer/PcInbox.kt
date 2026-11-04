package com.vvfile.app.core.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.vvfile.app.core.share.SharedPathResolver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「别的应用发过来的文件」变成可以直接上传的本地文件。
 *
 * 能解析出真实路径就直接用（不复制，几百 MB 也不搬一遍）；
 * 只有内容 URI（相册、聊天软件的私有目录）才落到应用缓存 `cache/pc-out/` 里再发。
 */
object PcInbox {

    /** ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW 里的全部 Uri。 */
    fun urisOf(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val list = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list += it }
            }
            else -> intent.data?.let { list += it }
        }
        return list.distinct()
    }

    /**
     * 解析成可上传的文件列表。解析失败的 Uri 会被跳过——上层据此提示
     * 「有 N 个文件没取到」，而不是整单失败。
     */
    suspend fun resolve(context: Context, intent: Intent?, cacheDir: File): List<File> =
        withContext(Dispatchers.IO) {
            urisOf(intent).mapNotNull { uri -> resolveOne(context, uri, cacheDir) }
        }

    private fun resolveOne(context: Context, uri: Uri, cacheDir: File): File? {
        // 1) 真实路径优先
        SharedPathResolver.resolve(context, uri)?.let { path ->
            val file = File(path)
            if (file.exists() && file.canRead()) return file
        }
        // 2) 退到内容流：复制进缓存再发
        val name = displayName(context, uri) ?: "vvfile-${System.currentTimeMillis()}.bin"
        val dir = File(cacheDir, CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = uniqueFile(dir, name)
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER) }
            } ?: return null
            target.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.replace('/', '_').replace('\\', '_').ifBlank { "vvfile.bin" }
        var candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while (candidate.exists() && index < 10_000) {
            candidate = File(dir, "$base ($index)$ext")
            index += 1
        }
        return candidate
    }

    private const val CACHE_DIR = "pc-out"
    private const val DEFAULT_BUFFER = 64 * 1024
}
