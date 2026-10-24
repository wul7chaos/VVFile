package com.vvfile.app.core.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.vvfile.app.core.format.MimeTypes
import java.io.File

/**
 * 通过 FileProvider 分享文件；支持单文件与多文件。
 * 授权使用 FLAG_GRANT_READ_URI_PERMISSION，不暴露持久权限。
 */
object FileSharer {

    fun share(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val authority = "${context.packageName}.fileprovider"
        val uris = paths.mapNotNull { path ->
            runCatching {
                FileProvider.getUriForFile(context, authority, File(path))
            }.getOrNull()
        }
        if (uris.isEmpty()) return

        val mime = if (uris.size == 1) MimeTypes.forPath(paths.first()) else "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "分享"))
    }
}
