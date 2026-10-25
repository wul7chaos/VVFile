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
    val firstInstallTime: Long = 0L,
) {
    val versionText: String
        get() = if (versionName.isBlank()) "v$versionCode" else "v$versionName"
}

/** 应用列表的两个页签，对齐 MT 的「用户应用 / 系统应用」。 */
enum class AppTab { USER, SYSTEM }

/** 排序字段，对齐 MT 的排序方式面板。 */
enum class AppSortField { NAME, PACKAGE, SIZE, FIRST_INSTALL, LAST_UPDATE }

/** 页签 + 关键字过滤。纯函数，便于单测。 */
fun filterApps(
    apps: List<AppInfo>,
    query: String,
    tab: AppTab,
): List<AppInfo> {
    val keyword = query.trim()
    return apps.filter { app ->
        (tab == AppTab.SYSTEM) == app.isSystem &&
            (keyword.isEmpty() ||
                app.label.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true))
    }
}

/**
 * 按字段排序；[descending] 为 true 时整体反转。
 *
 * 名称用不区分大小写的比较，包名/大小/时间按字面值比。
 */
fun sortApps(
    apps: List<AppInfo>,
    field: AppSortField,
    descending: Boolean,
): List<AppInfo> {
    val comparator = when (field) {
        AppSortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        AppSortField.PACKAGE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
        AppSortField.SIZE -> compareBy<AppInfo> { it.apkSizeBytes }.thenBy { it.label.lowercase() }
        AppSortField.FIRST_INSTALL -> compareBy<AppInfo> { it.firstInstallTime }.thenBy { it.label.lowercase() }
        AppSortField.LAST_UPDATE -> compareBy<AppInfo> { it.lastUpdateTime }.thenBy { it.label.lowercase() }
    }
    val sorted = apps.sortedWith(comparator)
    return if (descending) sorted.reversed() else sorted
}

/** 一次算出页签内可见列表：过滤 → 排序。[sortApps] / [filterApps] 拆开是为了单测。 */
fun visibleAppsOf(
    apps: List<AppInfo>,
    query: String,
    tab: AppTab,
    field: AppSortField,
    descending: Boolean,
): List<AppInfo> = sortApps(filterApps(apps, query, tab), field, descending)

