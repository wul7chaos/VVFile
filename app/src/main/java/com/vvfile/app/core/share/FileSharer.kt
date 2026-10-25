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

    /** 「打开方式」里的一个可选目标。 */
    data class OpenWithTarget(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
    )

    private fun uriFor(context: Context, path: String): Uri? =
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        }.getOrNull()

    /** 列出能打开该文件的系统应用；过滤掉自身，避免自己出现在「打开方式」里。 */
    fun resolveOpenWith(context: Context, path: String): List<OpenWithTarget> {
        val uri = uriFor(context, path) ?: return emptyList()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MimeTypes.forPath(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pm = context.packageManager
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                OpenWithTarget(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** 用指定应用打开文件；返回是否成功拉起。 */
    fun openWith(context: Context, path: String, packageName: String): Boolean {
        val uri = uriFor(context, path) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MimeTypes.forPath(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(packageName)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
