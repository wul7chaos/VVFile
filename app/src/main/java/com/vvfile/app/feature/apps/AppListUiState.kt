package com.vvfile.app.feature.apps

import com.vvfile.app.data.app.AppDetails
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
    /** 非空时展示「应用信息」弹窗。 */
    val details: AppDetails? = null,
    /** 弹窗对应的列表项，供「更多 / 提取安装包」复用。 */
    val detailsApp: AppInfo? = null,
) {
    val visibleApps: List<AppInfo> get() = filterApps(apps, query, includeSystem)
    val userAppCount: Int get() = apps.count { !it.isSystem }
}
