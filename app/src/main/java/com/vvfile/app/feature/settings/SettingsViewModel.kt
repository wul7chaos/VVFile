package com.vvfile.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.data.prefs.PrefsRepository
import com.vvfile.app.data.prefs.Settings
import com.vvfile.app.data.prefs.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页状态：把 [PrefsRepository.settings] 提升成页面可用的 StateFlow。
 *
 * 未订阅时上游停止收集（WhileSubscribed），离开设置页不占资源；
 * 重新进入会立刻拿到磁盘上的最新值，所以页面不需要自己维护副本。
 */
class SettingsViewModel(
    private val repository: PrefsRepository,
) : ViewModel() {

    val state: StateFlow<Settings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Settings.DEFAULT,
    )

    /** 写盘失败的一次性错误文案；页面 Toast 后调用 [clearError]。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setTheme(mode: ThemeMode) = write { repository.setTheme(mode) }

    fun setDualPane(enabled: Boolean) = write { repository.setDualPane(enabled) }

    fun setShowHidden(enabled: Boolean) = write { repository.setShowHidden(enabled) }

    fun setDetailedList(enabled: Boolean) = write { repository.setDetailedList(enabled) }

    fun setAccentIndex(index: Int) = write { repository.setAccentIndex(index) }

    fun setTerminalFontSize(size: Int) = write { repository.setTerminalFontSize(size) }

    fun setEditorFontSize(size: Int) = write { repository.setEditorFontSize(size) }

    fun setSort(spec: SortSpec) = write { repository.setSort(spec) }

    fun setSortField(field: SortField) = write { repository.setSortField(field) }

    fun setSortAscending(ascending: Boolean) = write { repository.setSortAscending(ascending) }

    fun setStartPath(path: String?) = write { repository.setStartPath(path) }

    fun clearError() {
        _error.value = null
    }

    private fun write(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = failure.message ?: "保存设置失败"
            }
        }
    }

    companion object {
        /** 页面默认工厂：从 Application 取单例仓库。 */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("SettingsViewModel 需要 Application 上下文")
                SettingsViewModel(PrefsRepository.get(application))
            }
        }
    }
}
