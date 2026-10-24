package com.vvfile.app.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 单个文件的编辑会话。
 *
 * 页面只负责渲染与输入；编码识别、只读判定、dirty 基线都在这里维护。
 * 保存始终使用打开时识别出的编码，不在中途切换。
 */
class EditorViewModel(
    private val path: String,
    private val gateway: EditorFileGateway = EditorFileGateway(),
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState(path = path))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 上次成功打开/保存的内容，作为 dirty 的比对基线。 */
    private var baseline: String = ""

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null, confirmExit = false) }
        viewModelScope.launch {
            try {
                val document = gateway.read(path)
                baseline = document.text
                _state.update { current ->
                    current.copy(
                        text = document.text,
                        encoding = document.encoding,
                        truncated = document.truncated,
                        readOnly = document.readOnly,
                        dirty = false,
                        loading = false,
                        error = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(loading = false, error = failure.message ?: "打开文件失败")
                }
            }
        }
    }

    /** 输入回调。只读文件直接忽略，不回写状态以免触发重组抖动。 */
    fun updateText(text: String) {
        val current = _state.value
        if (current.readOnly || text == current.text) return
        _state.update { it.copy(text = text, dirty = text != baseline) }
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                gateway.save(path, current.text, current.encoding)
                baseline = current.text
                _state.update {
                    it.copy(
                        saving = false,
                        dirty = false,
                        savedAt = it.savedAt + 1,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(saving = false, error = failure.message ?: "保存失败")
                }
            }
        }
    }

    /**
     * 返回/关闭请求。有未保存修改时置位 [EditorUiState.confirmExit] 并返回 false，
     * 页面据标志弹确认框；否则返回 true，页面可直接 [EditorUiState] 关闭。
     */
    fun requestExit(): Boolean {
        if (!_state.value.dirty) return true
        _state.update { it.copy(confirmExit = true) }
        return false
    }

    /** 关闭确认框（继续编辑）。 */
    fun dismissExit() {
        _state.update { it.copy(confirmExit = false) }
    }

    /** 放弃修改并关闭，由页面在确认后调用。 */
    fun discardChanges() {
        _state.update { it.copy(confirmExit = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        /** 页面用：把 path 注入 ViewModel。 */
        fun factory(path: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditorViewModel(path) }
        }
    }
}
