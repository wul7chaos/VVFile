package com.vvfile.app.feature.apps

import com.vvfile.app.data.app.AppDetails
import com.vvfile.app.data.app.AppInfo
import com.vvfile.app.data.app.AppSortField
import com.vvfile.app.data.app.AppTab
import com.vvfile.app.data.app.visibleAppsOf

data class AppListUiState(
    val loading: Boolean = true,
    /** 下拉刷新中（列表已有内容时不再显示整页 loading）。 */
    val refreshing: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val tab: AppTab = AppTab.USER,
    val query: String = "",
    val searchOpen: Boolean = false,
    val sortField: AppSortField = AppSortField.NAME,
    val sortDescending: Boolean = false,
    val error: String? = null,
    val exportingPackage: String? = null,
    val exportProgress: Float = 0f,
    val exportingTotal: Int = 0,
    val exportingIndex: Int = 0,
    val message: String? = null,
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    /** 命中缓存时列表秒开，用于顶部提示「已缓存」。 */
    val fromCache: Boolean = false,
    /** 非空时展示「应用信息」弹窗。 */
    val details: AppDetails? = null,
    /** 弹窗对应的列表项，供「更多 / 提取安装包」复用。 */
    val detailsApp: AppInfo? = null,
) {
    val visibleApps: List<AppInfo>
        get() = visibleAppsOf(apps, query, tab, sortField, sortDescending)

    val userCount: Int get() = apps.count { !it.isSystem }
    val systemCount: Int get() = apps.count { it.isSystem }
    val tabCount: Int get() = if (tab == AppTab.USER) userCount else systemCount
    val selectedApps: List<AppInfo> get() = apps.filter { it.packageName in selected }
}
