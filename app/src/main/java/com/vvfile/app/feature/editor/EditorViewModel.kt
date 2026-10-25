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
 * 页面只负责渲染与输入；编码识别、只读判定、dirty 基线、撤销栈都在这里维护。
 * 保存默认沿用打开时识别出的编码，也可以在「更多 → 指定保存的编码」里改。
 */
class EditorViewModel(
    private val path: String,
    private val gateway: EditorFileGateway = EditorFileGateway(),
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState(path = path))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** 上次成功打开/保存的内容，作为 dirty 的比对基线。 */
    private var baseline: String = ""

    /** 撤销/重做快照，只存文本；上限 [UNDO_LIMIT]。 */
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    init {
        load()
    }

    fun load() = loadWith(null)

    /** 指定编码重新加载（编码判错时手动纠正）。 */
    fun reloadWithEncoding(encoding: TextEncoding) = loadWith(encoding)

    private fun loadWith(forced: TextEncoding?) {
        _state.update { it.copy(loading = true, error = null, confirmExit = false) }
        viewModelScope.launch {
            try {
                val document = gateway.read(path, forced)
                baseline = document.text
                undoStack.clear()
                redoStack.clear()
                _state.update { current ->
                    current.copy(
                        text = document.text,
                        encoding = document.encoding,
                        truncated = document.truncated,
                        readOnly = document.readOnly,
                        dirty = false,
                        loading = false,
                        error = null,
                        canUndo = false,
                        canRedo = false,
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

    /** 输入回调。只读时不回写状态，避免触发重组抖动。 */
    fun updateText(text: String) {
        val current = _state.value
        if (current.editingBlocked || text == current.text) return
        pushUndo(current.text)
        redoStack.clear()
        _state.update {
            it.copy(
                text = text,
                dirty = text != baseline,
                canUndo = undoStack.isNotEmpty(),
                canRedo = false,
            )
        }
    }

    /** 行操作等整体替换，同样进撤销栈。 */
    fun replaceText(text: String) {
        val current = _state.value
        if (current.editingBlocked || text == current.text) return
        pushUndo(current.text)
        redoStack.clear()
        _state.update {
            it.copy(
                text = text,
                dirty = text != baseline,
                canUndo = undoStack.isNotEmpty(),
                canRedo = false,
            )
        }
    }

    private fun pushUndo(previous: String) {
        if (undoStack.lastOrNull() == previous) return
        undoStack.addLast(previous)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
    }

    fun undo() {
        val current = _state.value
        if (current.editingBlocked) return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current.text)
        _state.update {
            it.copy(
                text = previous,
                dirty = previous != baseline,
                canUndo = undoStack.isNotEmpty(),
                canRedo = true,
            )
        }
    }

    fun redo() {
        val current = _state.value
        if (current.editingBlocked) return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current.text)
        _state.update {
            it.copy(
                text = next,
                dirty = next != baseline,
                canUndo = true,
                canRedo = redoStack.isNotEmpty(),
            )
        }
    }

    fun setReadOnlyMode(enabled: Boolean) {
        _state.update { it.copy(readOnlyMode = enabled) }
    }

    /** 语法高亮开关。 */
    fun toggleHighlight() {
        _state.update { it.copy(highlight = !it.highlight) }
    }

    fun toggleWrap() {
        _state.update { it.copy(wrap = !it.wrap) }
    }

    fun setLineEnding(ending: LineEnding) {
        _state.update { it.copy(lineEnding = ending) }
    }

    /** 保存到原路径，可临时改编码。 */
    fun save(encoding: TextEncoding? = null) {
        val current = _state.value
        if (!current.canSave) return
        writeTo(path, current.text, encoding ?: current.encoding)
    }

    /** 另存为：保存成功后把后续编辑指向新路径。 */
    fun saveAs(targetPath: String, encoding: TextEncoding? = null) {
        val current = _state.value
        val trimmed = targetPath.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "路径不能为空") }
            return
        }
        writeTo(trimmed, current.text, encoding ?: current.encoding, switchTo = true)
    }

    private fun writeTo(
        targetPath: String,
        text: String,
        encoding: TextEncoding,
        switchTo: Boolean = false,
    ) {
        val ending = _state.value.lineEnding
        val normalized = text.replace("\r\n", "\n")
        val payload = if (ending == LineEnding.CRLF) {
            normalized.replace("\n", "\r\n")
        } else {
            normalized
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                gateway.save(targetPath, payload, encoding)
                baseline = text
                _state.update {
                    it.copy(
                        path = if (switchTo) targetPath else it.path,
                        encoding = encoding,
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
     * 页面据标志弹确认框；否则返回 true，页面可直接关闭。
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
        const val UNDO_LIMIT = 100

        /** 页面用：把 path 注入 ViewModel。 */
        fun factory(path: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { EditorViewModel(path) }
        }
    }
}
