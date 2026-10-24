package com.vvfile.app.feature.apps

import com.vvfile.app.data.app.AppInfo
import com.vvfile.app.data.app.filterApps

data class AppListUiState(
    val loading: Boolean = true,
    val apps: List<AppInfo> = emptyList(),
    val query: String = "",
    val includeSystem: Boolean = false,
    val error: String? = null,
    val exportingPackage: String? = null,
    val exportProgress: Float = 0f,
    val message: String? = null,
) {
    val visibleApps: List<AppInfo> get() = filterApps(apps, query, includeSystem)
    val userAppCount: Int get() = apps.count { !it.isSystem }
}
