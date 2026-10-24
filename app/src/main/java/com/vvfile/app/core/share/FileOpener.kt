package com.vvfile.app.core.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.vvfile.app.core.format.MimeTypes
import java.io.File

/** 用系统应用打开文件；返回 false 表示没有可用应用或 URI 生成失败。 */
object FileOpener {

    fun open(context: Context, path: String): Boolean {
        val authority = "${context.packageName}.fileprovider"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, File(path))
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MimeTypes.forPath(path))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "打开方式"))
        }.isSuccess
    }
}
