package com.vvfile.app.feature.browser

import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vvfile.app.R
import com.vvfile.app.ServiceLocator
import com.vvfile.app.core.path.PathUtils
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationPhase
import com.vvfile.app.core.result.OperationProgress
import com.vvfile.app.core.result.OperationResult
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.SortField
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.data.prefs.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException

class BrowserViewModel : ViewModel() {

    private val repository = ServiceLocator.fileRepository
    private val operator = ServiceLocator.fileOperator
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val loadJobs = mutableMapOf<PaneId, Job>()
    private var operationJob: Job? = null
    private var pendingTransfer: PendingTransfer? = null
    private var pendingUnzip: PendingUnzip? = null
    private val defaultPath: String = runCatching {
        Environment.getExternalStorageDirectory().absolutePath
    }.getOrDefault("/storage/emulated/0")

    init {
        _state.update { it.copy(rootAvailable = RootManager.isAvailable()) }
        viewModelScope.launch {
            val prefs = ServiceLocator.prefs
            val settings = runCatching { prefs.settings.first() }.getOrNull()
            val remembered = if (settings?.restoreLastPath != false) {
                runCatching { prefs.lastPath() }.getOrNull()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
            val initial = settings?.startPath?.takeIf { it.isNotBlank() }
                ?: remembered
                ?: defaultPath
            navigate(PaneId.LEFT, initial)
            navigate(PaneId.RIGHT, initial)
            runCatching { repository.storageStats(initial) }
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

    /** 设置当前窗格的关键字/扩展名过滤；过滤变化时清空选择，避免操作到不可见条目。 */
    fun setFilter(pane: PaneId, query: String, extensions: String) {
        val target = if (_state.value.dualPane) pane else PaneId.LEFT
        updatePane(target) {
            it.copy(
                filterQuery = query.trim(),
                filterExtensions = extensions.trim(),
                selected = emptySet(),
            )
        }
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

    /** 应用设置页的默认值（启动与设置变化时调用）。 */
    fun applySettings(settings: Settings) {
        _state.update { s ->
            val sort = settings.sort
            s.copy(
                showHidden = settings.showHidden,
                dualPane = settings.dualPane,
                sort = sort,
                left = s.left.copy(entries = s.left.entries.sortedWith(sort.comparator())),
                right = s.right.copy(
                    entries = s.right.entries.sortedWith(sort.comparator()),
                    selected = if (settings.dualPane) s.right.selected else emptySet(),
                ),
                active = if (settings.dualPane) s.active else PaneId.LEFT,
            )
        }
        refreshAll()
        settings.startPath?.takeIf { it.isNotBlank() }?.let { path ->
            navigate(PaneId.LEFT, path)
            if (settings.dualPane) navigate(PaneId.RIGHT, path)
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

    // -------------------------------------------------------------- 文件操作 --

    fun copySelectionToOtherPane() = startTransfer(OperationKind.COPY)

    fun moveSelectionToOtherPane() = startTransfer(OperationKind.MOVE)

    fun deleteSelection() {
        if (isBusy()) return notifyBusy()
        val paths = selectedPaths(activePaneState())
        if (paths.isEmpty()) return postMessage(UiMessage.Simple(R.string.ui_ops_nothing_selected))
        startFlow(OperationKind.DELETE) { operator.delete(paths) }
    }

    fun renameSelection(newName: String) {
        if (isBusy()) return notifyBusy()
        val entry = selectedEntries(activePaneState()).singleOrNull()
            ?: return postMessage(UiMessage.Simple(R.string.ui_ops_rename_requires_single))
        val name = validateName(newName) ?: return
        if (name == entry.name) return
        viewModelScope.launch {
            try {
                val result = operator.rename(entry.path, name)
                if (result.isSuccess) {
                    postMessage(UiMessage.Simple(R.string.ui_ops_rename_done))
                } else {
                    postResult(result)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                postMessage(UiMessage.Result(0, 1, 0, false, error.message))
            }
            afterOperation()
        }
    }

    fun createFolder(name: String) = createEntry(name, directory = true)

    fun createFile(name: String) = createEntry(name, directory = false)

    fun zipSelection(zipName: String, level: Int = 6) {
        if (isBusy()) return notifyBusy()
        val pane = activePaneState()
        val sources = selectedPaths(pane)
        if (sources.isEmpty()) return postMessage(UiMessage.Simple(R.string.ui_ops_nothing_selected))
        if (pane.path.isEmpty()) return postMessage(UiMessage.Simple(R.string.ui_ops_target_invalid))
        val name = validateName(zipName) ?: return
        val fileName = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
        val targetZip = PathUtils.join(pane.path, fileName)
        if (File(targetZip).exists()) {
            return postMessage(UiMessage.Simple(R.string.ui_ops_zip_exists))
        }
        startFlow(OperationKind.ZIP) { operator.zip(sources, targetZip, level) }
    }

    fun unzipSelection(targetDir: String? = null) {
        if (isBusy()) return notifyBusy()
        val pane = activePaneState()
        val zip = selectedEntries(pane).singleOrNull()
            ?.takeIf { !it.isDirectory && it.extension == "zip" }
            ?: return postMessage(UiMessage.Simple(R.string.ui_ops_unzip_requires_zip))
        val target = targetDir?.trim()?.takeIf { it.isNotEmpty() } ?: pane.path
        if (target.isEmpty()) return postMessage(UiMessage.Simple(R.string.ui_ops_target_invalid))
        viewModelScope.launch {
            try {
                val conflicts = operator.findZipConflicts(zip.path, target)
                if (conflicts.isEmpty()) {
                    startFlow(OperationKind.UNZIP) { operator.unzip(zip.path, target) }
                } else {
                    pendingUnzip = PendingUnzip(zip.path, target)
                    _state.update {
                        it.copy(conflictPrompt = ConflictPrompt(OperationKind.UNZIP, conflicts.size))
                    }
                }
            } catch (error: Exception) {
                if (isZipSlip(error)) {
                    postMessage(UiMessage.Simple(R.string.ui_ops_zip_slip_rejected))
                } else {
                    Log.e("VVFileUnzip", "probe failed", error)
                    postMessage(UiMessage.Simple(R.string.ui_ops_unzip_probe_failed))
                }
            }
        }
    }

    /** 冲突对话框的选择；policy 为 null 表示取消。 */
    fun resolveConflict(policy: ConflictPolicy?) {
        val pendingZip = pendingUnzip
        if (pendingZip != null) {
            pendingUnzip = null
            _state.update { it.copy(conflictPrompt = null) }
            if (policy == null) return
            startFlow(OperationKind.UNZIP) {
                operator.unzip(pendingZip.zipPath, pendingZip.targetDir, policy)
            }
            return
        }
        val pending = pendingTransfer
        pendingTransfer = null
        _state.update { it.copy(conflictPrompt = null) }
        if (pending == null || policy == null) return
        runTransfer(pending.kind, pending.sources, pending.targetDir, policy)
    }

    fun cancelOperation() {
        operationJob?.cancel()
    }

    /** UI 展示完一次性提示后调用，避免重复 Toast。 */
    fun consumeMessage() {
        _state.update { if (it.message == null) it else it.copy(message = null) }
    }

    fun changePermissions(path: String, mode: String) {
        if (isBusy()) return notifyBusy()
        viewModelScope.launch {
            try {
                val result = operator.chmod(path, mode)
                if (result.isSuccess) {
                    postMessage(UiMessage.Simple(R.string.ui_ops_chmod_success))
                    afterOperation()
                } else {
                    postMessage(
                        UiMessage.Result(
                            0,
                            1,
                            0,
                            false,
                            result.failures.firstOrNull()?.reason ?: "修改权限失败",
                        ),
                    )
                }
            } catch (error: Exception) {
                postMessage(UiMessage.Result(0, 1, 0, false, error.message ?: "修改权限失败"))
            }
        }
    }

    private fun startTransfer(kind: OperationKind) {
        if (isBusy()) return notifyBusy()
        val s = _state.value
        if (!s.dualPane) {
            return postMessage(UiMessage.Simple(R.string.ui_ops_single_pane_unavailable))
        }
        val sourcePane = if (s.active == PaneId.LEFT) s.left else s.right
        val targetPane = if (s.active == PaneId.LEFT) s.right else s.left
        val sources = selectedPaths(sourcePane)
        if (sources.isEmpty()) return postMessage(UiMessage.Simple(R.string.ui_ops_nothing_selected))
        if (sourcePane.path == targetPane.path) {
            return postMessage(UiMessage.Simple(R.string.ui_ops_same_dir))
        }
        if (targetPane.path.isEmpty()) {
            return postMessage(UiMessage.Simple(R.string.ui_ops_target_invalid))
        }
        val targetDir = targetPane.path
        val normalizedTarget = PathUtils.normalize(targetDir)
        val intoSelf = sources.any { source ->
            val normalizedSource = PathUtils.normalize(source)
            normalizedSource == normalizedTarget || PathUtils.isAncestor(normalizedSource, normalizedTarget)
        }
        if (intoSelf) {
            return postMessage(UiMessage.Simple(R.string.ui_ops_into_self))
        }
        operationJob = viewModelScope.launch {
            val conflicts = try {
                operator.findConflicts(sources, targetDir)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                postMessage(UiMessage.Simple(R.string.ui_ops_conflict_check_failed))
                return@launch
            }
            if (conflicts.isEmpty()) {
                operationJob = null
                runTransfer(kind, sources, targetDir, ConflictPolicy.RENAME)
            } else {
                pendingTransfer = PendingTransfer(kind, sources, targetDir)
                _state.update { it.copy(conflictPrompt = ConflictPrompt(kind, conflicts.size)) }
            }
        }
    }

    private fun runTransfer(
        kind: OperationKind,
        sources: List<String>,
        targetDir: String,
        policy: ConflictPolicy,
    ) {
        startFlow(kind) {
            if (kind == OperationKind.COPY) {
                operator.copy(sources, targetDir, policy)
            } else {
                operator.move(sources, targetDir, policy)
            }
        }
    }

    private fun startFlow(kind: OperationKind, flowBuilder: () -> Flow<OperationEvent>) {
        if (isBusy()) return notifyBusy()
        _state.update { it.copy(operation = OperationUiState(kind = kind), conflictPrompt = null) }
        operationJob = viewModelScope.launch {
            try {
                flowBuilder().collect { event ->
                    when (event) {
                        is OperationEvent.Progress -> updateProgress(event.progress)
                        is OperationEvent.Finished -> onOperationFinished(event.result)
                    }
                }
            } catch (cancel: CancellationException) {
                _state.update { it.copy(operation = null) }
                postMessage(UiMessage.Simple(R.string.ui_ops_result_cancelled))
                afterOperation()
                throw cancel
            } catch (error: Exception) {
                _state.update { it.copy(operation = null) }
                postMessage(UiMessage.Result(0, 1, 0, false, errorMessage(error)))
                afterOperation()
            }
        }
    }

    private fun updateProgress(progress: OperationProgress) {
        _state.update { s ->
            val current = s.operation ?: return@update s
            s.copy(
                operation = current.copy(
                    progress = progress.fraction,
                    currentName = progress.currentName,
                    processedItems = progress.processedItems,
                    totalItems = progress.totalItems,
                    preparing = progress.phase == OperationPhase.PREPARING,
                ),
            )
        }
    }

    private fun onOperationFinished(result: OperationResult) {
        _state.update { it.copy(operation = null) }
        postResult(result)
        afterOperation()
    }

    /** 操作结束后刷新双窗格并退出选择态（目标窗格也刷新）。 */
    private fun afterOperation() {
        _state.update { s ->
            s.copy(
                left = s.left.copy(selected = emptySet()),
                right = s.right.copy(selected = emptySet()),
            )
        }
        refreshAll()
    }

    private fun createEntry(name: String, directory: Boolean) {
        if (isBusy()) return notifyBusy()
        val pane = activePaneState()
        if (pane.path.isEmpty()) return postMessage(UiMessage.Simple(R.string.ui_ops_target_invalid))
        val validated = validateName(name) ?: return
        viewModelScope.launch {
            try {
                val result = if (directory) {
                    operator.createDirectory(pane.path, validated)
                } else {
                    operator.createFile(pane.path, validated)
                }
                if (result.isSuccess) {
                    postMessage(
                        UiMessage.Simple(
                            if (directory) R.string.ui_ops_folder_created else R.string.ui_ops_file_created,
                        ),
                    )
                } else {
                    postResult(result)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                postMessage(UiMessage.Result(0, 1, 0, false, error.message))
            }
            afterOperation()
        }
    }

    private fun isBusy(): Boolean = operationJob?.isActive == true

    private fun notifyBusy() {
        postMessage(UiMessage.Simple(R.string.ui_ops_busy))
    }

    /** 校验并返回去除首尾空白的文件名；非法时推送提示并返回 null。 */
    private fun validateName(name: String): String? {
        val candidate = name.trim()
        return runCatching { PathUtils.validateFileName(candidate) }.getOrElse {
            postMessage(UiMessage.Simple(R.string.ui_ops_name_invalid))
            null
        }
    }

    private fun activePaneState(): PaneState =
        if (_state.value.active == PaneId.LEFT) _state.value.left else _state.value.right

    private fun selectedEntries(pane: PaneState): List<FileEntry> =
        pane.entries.filter { it.path in pane.selected }

    private fun selectedPaths(pane: PaneState): List<String> =
        selectedEntries(pane).map { it.path }

    private fun postMessage(message: UiMessage) {
        _state.update { it.copy(message = message) }
    }

    private fun postResult(result: OperationResult) {
        postMessage(
            UiMessage.Result(
                succeeded = result.succeeded.size,
                failed = result.failures.size,
                skipped = result.skipped.size,
                cancelled = result.cancelled,
                failureReason = result.failures.firstOrNull()?.reason,
            ),
        )
    }

    private fun isZipSlip(error: Exception): Boolean =
        error is java.util.zip.ZipException &&
            error.message?.contains("Invalid zip entry path", ignoreCase = true) == true

    private fun errorMessage(error: Exception): String =
        if (isZipSlip(error)) "压缩包包含非法路径，已拒绝解压" else error.message ?: "操作失败"

    private data class PendingTransfer(
        val kind: OperationKind,
        val sources: List<String>,
        val targetDir: String,
    )

    private data class PendingUnzip(
        val zipPath: String,
        val targetDir: String,
    )

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
        if (path.isNotBlank()) {
            viewModelScope.launch { ServiceLocator.prefs.setLastPath(path) }
        }
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

