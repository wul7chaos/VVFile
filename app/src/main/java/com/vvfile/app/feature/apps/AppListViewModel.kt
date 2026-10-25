package com.vvfile.app.feature.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vvfile.app.core.path.WorkspaceLayout
import com.vvfile.app.data.app.AppDetails
import com.vvfile.app.data.app.AppInfo
import com.vvfile.app.data.app.AppRepository
import com.vvfile.app.data.app.AppSortField
import com.vvfile.app.data.app.AppTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListViewModel(
    private val repository: AppRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

    /**
     * 安装 / 卸载 / 更新广播：让缓存失效并立刻重扫，
     * 对应需求里的「有变化再重新获取」。列表本来没打开时 ViewModel 已销毁，不会触发。
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppRepository.invalidateCache()
            load(force = true, silent = true)
        }
    }

    init {
        registerPackageReceiver()
        load(force = false)
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                packageReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /**
     * 读列表。[force] 为 true 时跳过缓存校验（下拉刷新、包变化广播）。
     * [silent] 用于后台刷新：不显示整页 loading，失败也不弹错误页。
     */
    fun load(force: Boolean = false, silent: Boolean = false) {
        val hasApps = _state.value.apps.isNotEmpty()
        val cacheHit = !force && AppRepository.cachedApps() != null
        if (!silent) {
            _state.update {
                it.copy(
                    loading = !hasApps && !force,
                    refreshing = force || hasApps,
                    error = null,
                )
            }
        }
        viewModelScope.launch {
            try {
                val apps = repository.loadApps(force = force)
                _state.update { current ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        apps = apps,
                        error = null,
                        fromCache = cacheHit,
                        // 列表变了就把已经不存在的选中项清掉
                        selected = current.selected.filterTo(HashSet()) { pkg ->
                            apps.any { it.packageName == pkg }
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = if (silent && it.apps.isNotEmpty()) it.error else {
                            failure.message ?: "读取应用列表失败"
                        },
                    )
                }
            }
        }
    }

    fun refresh() = load(force = true)

    /**
     * 页面重新可见时调用：走一次「包变化」校验（很轻），没有变化就直接用缓存，
     * 有变化才重新扫描。列表秒开靠的就是这里。
     */
    fun onVisible() {
        if (_state.value.loading) return
        load(force = false, silent = true)
    }

    fun setTab(tab: AppTab) {
        _state.update { it.copy(tab = tab, selected = emptySet(), selectionMode = false) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun toggleSearch() {
        _state.update { it.copy(searchOpen = !it.searchOpen, query = if (it.searchOpen) "" else it.query) }
    }

    fun closeSearch() {
        _state.update { it.copy(searchOpen = false, query = "") }
    }

    fun setSort(field: AppSortField, descending: Boolean) {
        _state.update { it.copy(sortField = field, sortDescending = descending) }
    }

    // ---- 多选 ----

    fun enterSelection(packageName: String? = null) {
        _state.update {
            it.copy(
                selectionMode = true,
                selected = if (packageName == null) it.selected else it.selected + packageName,
            )
        }
    }

    fun toggleSelect(packageName: String) {
        _state.update {
            val next = if (packageName in it.selected) it.selected - packageName else it.selected + packageName
            it.copy(selected = next, selectionMode = next.isNotEmpty() || it.selectionMode)
        }
    }

    fun selectAllVisible() {
        _state.update { it.copy(selectionMode = true, selected = it.visibleApps.map { a -> a.packageName }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectionMode = false, selected = emptySet()) }
    }

    fun launch(app: AppInfo) {
        if (!repository.launch(app.packageName)) {
            _state.update { it.copy(message = "该应用没有可启动的界面") }
        }
    }

    /** 打开「应用信息」弹窗：字段在 IO 线程读取，读完再一次性更新状态。 */
    fun openAppInfo(app: AppInfo) {
        _state.update { it.copy(detailsApp = app, details = AppDetails.from(app)) }
        viewModelScope.launch {
            val details = runCatching { repository.loadDetails(app) }.getOrNull() ?: return@launch
            // 期间用户可能已经关掉弹窗或换了目标，只有仍是同一个包才落地。
            _state.update { current ->
                if (current.detailsApp?.packageName == app.packageName) {
                    current.copy(details = details)
                } else {
                    current
                }
            }
        }
    }

    fun dismissDetails() {
        _state.update { it.copy(details = null, detailsApp = null) }
    }

    /** 弹窗里的「更多」：跳到系统「应用信息」页。 */
    fun openSystemAppInfo() {
        val app = _state.value.detailsApp ?: return
        repository.openAppInfo(app.packageName)
    }

    fun requestUninstall(app: AppInfo) {
        if (app.isSystem) {
            _state.update { it.copy(message = "系统应用不可直接卸载") }
            return
        }
        repository.requestUninstall(app.packageName)
    }

    fun export(app: AppInfo) {
        exportAll(listOf(app))
    }

    /** 多选导出：按顺序逐个导出，进度弹窗显示「第 i/N 个」。 */
    fun exportSelected() {
        val targets = _state.value.selectedApps
        if (targets.isEmpty()) {
            _state.update { it.copy(message = "没有选中的应用") }
            return
        }
        exportAll(targets)
    }

    private fun exportAll(targets: List<AppInfo>) {
        if (targets.isEmpty()) return
        if (_state.value.exportingPackage != null) return
        _state.update {
            it.copy(
                exportingPackage = targets.first().packageName,
                exportProgress = 0f,
                exportingTotal = targets.size,
                exportingIndex = 1,
            )
        }
        viewModelScope.launch {
            var exported = 0
            try {
                targets.forEachIndexed { index, app ->
                    _state.update {
                        it.copy(exportingPackage = app.packageName, exportingIndex = index + 1, exportProgress = 0f)
                    }
                    withContext(Dispatchers.IO) {
                        var lastPercent = -1
                        repository.exportApk(app) { progress ->
                            val percent = (progress * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.update { it.copy(exportProgress = progress) }
                            }
                        }
                    }
                    exported++
                }
                _state.update {
                    it.copy(
                        exportingPackage = null,
                        exportProgress = 0f,
                        exportingTotal = 0,
                        exportingIndex = 0,
                        selectionMode = false,
                        selected = emptySet(),
                        message = if (targets.size == 1) {
                            val name = targets.first().label
                            "已导出 $name 到 ${WorkspaceLayout.ROOT_NAME}/${WorkspaceLayout.APK_DIR_NAME}"
                        } else {
                            "已导出 $exported 个安装包到 ${WorkspaceLayout.ROOT_NAME}/${WorkspaceLayout.APK_DIR_NAME}"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        exportingPackage = null,
                        exportProgress = 0f,
                        exportingTotal = 0,
                        exportingIndex = 0,
                        message = "导出失败：${failure.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { if (it.message == null) it else it.copy(message = null) }
    }

    override fun onCleared() {
        runCatching { appContext.unregisterReceiver(packageReceiver) }
        super.onCleared()
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("AppListViewModel 需要 Application 上下文")
                AppListViewModel(AppRepository(application), application)
            }
        }
    }
}
