package com.vvfile.app.feature.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vvfile.app.core.path.WorkspaceLayout
import com.vvfile.app.data.app.AppDetails
import com.vvfile.app.data.app.AppInfo
import com.vvfile.app.data.app.AppRepository
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
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val apps = repository.loadApps()
                _state.update { it.copy(loading = false, apps = apps, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(loading = false, error = failure.message ?: "读取应用列表失败")
                }
            }
        }
    }

    fun refresh() = load()

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setIncludeSystem(include: Boolean) {
        _state.update { it.copy(includeSystem = include) }
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
        if (_state.value.exportingPackage != null) return
        _state.update { it.copy(exportingPackage = app.packageName, exportProgress = 0f) }
        viewModelScope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    var lastPercent = -1
                    repository.exportApk(app) { progress ->
                        val percent = (progress * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.update { it.copy(exportProgress = progress) }
                        }
                    }
                }
                _state.update {
                    it.copy(
                        exportingPackage = null,
                        exportProgress = 0f,
                        message = "已导出到 ${WorkspaceLayout.ROOT_NAME}/${WorkspaceLayout.APK_DIR_NAME}/${target.name}",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        exportingPackage = null,
                        exportProgress = 0f,
                        message = "导出失败：${failure.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { if (it.message == null) it else it.copy(message = null) }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("AppListViewModel 需要 Application 上下文")
                AppListViewModel(AppRepository(application))
            }
        }
    }
}
