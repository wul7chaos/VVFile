package com.vvfile.app.feature.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

    fun openAppInfo(app: AppInfo) = repository.openAppInfo(app.packageName)

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
                        message = "已导出到 下载/${target.name}",
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
