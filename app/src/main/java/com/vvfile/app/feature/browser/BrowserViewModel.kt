package com.vvfile.app.feature.browser

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vvfile.app.ServiceLocator
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

class BrowserViewModel : ViewModel() {

    private val repository = ServiceLocator.fileRepository
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val loadJobs = mutableMapOf<PaneId, Job>()
    private val defaultPath: String = runCatching {
        Environment.getExternalStorageDirectory().absolutePath
    }.getOrDefault("/storage/emulated/0")

    init {
        _state.update { it.copy(rootAvailable = RootManager.isAvailable()) }
        navigate(PaneId.LEFT, defaultPath)
        navigate(PaneId.RIGHT, defaultPath)
        viewModelScope.launch {
            runCatching { repository.storageStats(defaultPath) }
                .onSuccess { stats -> _state.update { it.copy(storage = stats) } }
        }
    }

    fun setActive(pane: PaneId) {
        // 单窗格模式下右窗格不可用，活动窗格始终为左窗格
        val target = if (_state.value.dualPane) pane else PaneId.LEFT
        if (_state.value.active == target) return
        _state.update { current ->
            // 离开带选择的窗格时结束其选择态，避免留下看不见的选中项
            val cleared = when (current.active) {
                PaneId.LEFT -> current.copy(left = current.left.copy(selected = emptySet()))
                PaneId.RIGHT -> current.copy(right = current.right.copy(selected = emptySet()))
            }
            cleared.copy(active = target)
        }
    }

    fun navigate(pane: PaneId, path: String) {
        val current = paneState(pane)
        if (path == current.path) return
        val history = current.history.take(current.historyIndex + 1) + path
        load(pane, path, history, history.lastIndex)
    }

    fun navigateUp(pane: PaneId) {
        val parent = File(paneState(pane).path).parent ?: return
        navigate(pane, parent)
    }

    fun goBack(pane: PaneId) {
        val current = paneState(pane)
        if (!current.canGoBack) return
        val index = current.historyIndex - 1
        load(pane, current.history[index], current.history, index)
    }

    fun goForward(pane: PaneId) {
        val current = paneState(pane)
        if (!current.canGoForward) return
        val index = current.historyIndex + 1
        load(pane, current.history[index], current.history, index)
    }

    fun refresh(pane: PaneId) {
        val current = paneState(pane)
        if (current.path.isEmpty()) return
        load(pane, current.path, current.history, current.historyIndex, keepEntries = true)
    }

    fun refreshAll() {
        refresh(PaneId.LEFT)
        refresh(PaneId.RIGHT)
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        refreshAll()
    }

    fun setSort(field: SortField) {
        val current = _state.value
        val next = if (current.sort.field == field) {
            current.sort.copy(ascending = !current.sort.ascending)
        } else {
            SortSpec(field = field, ascending = true)
        }
        _state.update { s ->
            s.copy(
                sort = next,
                left = s.left.copy(entries = s.left.entries.sortedWith(next.comparator())),
                right = s.right.copy(entries = s.right.entries.sortedWith(next.comparator())),
            )
        }
    }

    fun swapPanes() {
        _state.update { s ->
            val left = s.left
            s.copy(left = s.right.copy(loading = s.right.loading), right = left.copy(loading = left.loading))
        }
    }

    fun setDualPane(enabled: Boolean) {
        _state.update { s ->
            s.copy(
                dualPane = enabled,
                active = PaneId.LEFT,
                // 收起右窗格时丢弃其选择，避免重新展开后残留看不见的选中项
                right = if (enabled) s.right else s.right.copy(selected = emptySet()),
            )
        }
    }

    /** 长按/选择态点击：切换单条选中状态，并把该窗格设为活动窗格。 */
    fun toggleSelect(pane: PaneId, path: String) {
        _state.update { s ->
            val target = if (s.dualPane) pane else PaneId.LEFT
            val current = if (target == PaneId.LEFT) s.left else s.right
            val selected = if (path in current.selected) current.selected - path else current.selected + path
            s.withOnlySelection(target, selected)
        }
    }

    fun clearSelection(pane: PaneId) {
        updatePane(pane) { it.copy(selected = emptySet()) }
    }

    fun selectAll(pane: PaneId) {
        _state.update { s ->
            val target = if (s.dualPane) pane else PaneId.LEFT
            val current = if (target == PaneId.LEFT) s.left else s.right
            s.withOnlySelection(target, current.entries.map { it.path }.toSet())
        }
    }

    /** 选择态始终只属于一个窗格：写入目标窗格时清空对侧选择。 */
    private fun BrowserUiState.withOnlySelection(pane: PaneId, selected: Set<String>): BrowserUiState =
        if (pane == PaneId.LEFT) {
            copy(
                left = left.copy(selected = selected),
                right = right.copy(selected = emptySet()),
                active = PaneId.LEFT,
            )
        } else {
            copy(
                right = right.copy(selected = selected),
                left = left.copy(selected = emptySet()),
                active = PaneId.RIGHT,
            )
        }

    /** 文件打开在 M2 接入；此处只暴露判断给 UI。 */
    fun isDirectory(path: String): Boolean = File(path).isDirectory

    private fun load(
        pane: PaneId,
        path: String,
        history: List<String>,
        historyIndex: Int,
        keepEntries: Boolean = false,
    ) {
        loadJobs[pane]?.cancel()
        val pathChanged = path != paneState(pane).path
        updatePane(pane) {
            it.copy(
                path = path,
                history = history,
                historyIndex = historyIndex,
                loading = true,
                error = null,
                entries = if (keepEntries) it.entries else emptyList(),
                // 切换目录时退出选择态；刷新同一目录保留选择
                selected = if (pathChanged) emptySet() else it.selected,
            )
        }
        loadJobs[pane] = viewModelScope.launch {
            try {
                val entries = repository.list(path, _state.value.showHidden)
                    .sortedWith(_state.value.sort.comparator())
                updatePane(pane) { current ->
                    // 列表刷新后剔除已不存在的条目，避免"已选 N 项"与列表不一致
                    val present = entries.map { it.path }.toSet()
                    current.copy(
                        entries = entries,
                        loading = false,
                        error = null,
                        selected = current.selected.intersect(present),
                    )
                }
                runCatching { repository.storageStats(path) }
                    .onSuccess { stats -> _state.update { it.copy(storage = stats) } }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                updatePane(pane) { it.copy(loading = false, error = error.toUserMessage()) }
            }
        }
    }

    private fun paneState(pane: PaneId): PaneState =
        if (pane == PaneId.LEFT) _state.value.left else _state.value.right

    private fun updatePane(pane: PaneId, transform: (PaneState) -> PaneState) {
        _state.update { s ->
            if (pane == PaneId.LEFT) s.copy(left = transform(s.left))
            else s.copy(right = transform(s.right))
        }
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is SecurityException -> "没有权限读取此目录"
        is FileNotFoundException -> message ?: "目录不存在"
        else -> message ?: "读取失败"
    }
}
