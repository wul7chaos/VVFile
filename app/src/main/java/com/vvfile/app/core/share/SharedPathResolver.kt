package com.vvfile.app.core.share

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * 把其他应用分享/打开的 Uri 解析成本机绝对路径。
 *
 * 只解析、不拷贝：能拿到真实路径就交给文件管理器「定位」，拿不到返回 null，
 * 由调用方提示「无法定位」——把一个几百 MB 的文件偷偷复制到缓存里定位没有意义。
 */
object SharedPathResolver {

    /** 从 VIEW / SEND 两类 intent 里取目标 Uri 并解析。 */
    fun resolveIntent(context: Context, intent: Intent?): String? {
        intent ?: return null
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> intent.data
        } ?: return null
        return resolve(context, uri)
    }

    fun resolve(context: Context, uri: Uri): String? {
        val raw = when (uri.scheme?.lowercase()) {
            "file" -> uri.path
            "content" -> fromContent(context, uri)
            else -> null
        } ?: return null
        val path = raw.removePrefix("raw:")
        return path.takeIf { it.startsWith("/") && File(it).exists() }
    }

    private fun fromContent(context: Context, uri: Uri): String? {
        val authority = uri.authority.orEmpty()
        // 文件管理器/系统选择器走 externalstorage 文档提供者，docId 里直接带卷和路径
        if (authority == EXTERNAL_STORAGE_AUTHORITY) return fromExternalStorage(uri)
        if (authority == MEDIA_AUTHORITY) return fromMediaDocuments(context, uri)
        if (authority == DOWNLOAD_AUTHORITY) return fromDownloads(context, uri)
        // 兜底：很多提供者都实现了 _data 列
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
    private val DOWNLOAD_CONTENT_URI: Uri = Uri.parse("content://downloads/public_downloads")
}
