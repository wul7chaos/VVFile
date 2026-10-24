package com.vvfile.app.data.app

import android.graphics.Bitmap

/** 已安装应用条目；图标在 IO 线程加载，UI 只做展示。 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val apkSizeBytes: Long,
    val lastUpdateTime: Long,
    val icon: Bitmap?,
    val sourceDir: String,
) {
    val versionText: String
        get() = if (versionName.isBlank()) "v$versionCode" else "v$versionName"
}

/** 应用列表过滤：按名称/包名搜索，可选是否包含系统应用。纯函数，便于单测。 */
fun filterApps(
    apps: List<AppInfo>,
    query: String,
    includeSystem: Boolean,
): List<AppInfo> {
    val keyword = query.trim()
    return apps.filter { app ->
        (includeSystem || !app.isSystem) &&
            (keyword.isEmpty() ||
                app.label.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true))
    }
}
