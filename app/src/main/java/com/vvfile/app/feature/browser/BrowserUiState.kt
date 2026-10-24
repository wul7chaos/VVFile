package com.vvfile.app.feature.browser

import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.data.model.StorageStats

enum class PaneId { LEFT, RIGHT }

/** 单个窗格的完整状态（路径、历史、列表、选择）。 */
data class PaneState(
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val loading: Boolean = true,
    val error: String? = null,
    /** 已选条目的绝对路径集合；非空即处于选择态。 */
    val selected: Set<String> = emptySet(),
    /** 关键字过滤（名称，忽略大小写）。 */
    val filterQuery: String = "",
    /** 扩展名过滤，逗号/空格分隔；空表示不过滤。 */
    val filterExtensions: String = "",
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex in 0 until history.lastIndex
    val selectionMode: Boolean get() = selected.isNotEmpty()
    val filterActive: Boolean get() = filterQuery.isNotBlank() || filterExtensions.isNotBlank()

    /** 过滤后的可见条目；过滤不改变 entries，避免影响选择与操作语义。 */
    val visibleEntries: List<FileEntry>
        get() {
            if (!filterActive) return entries
            val keyword = filterQuery.trim()
            val extensions = filterExtensions
                .split(',', ' ', '，')
                .map { it.trim().removePrefix(".").lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            return entries.filter { entry ->
                (keyword.isEmpty() || entry.name.contains(keyword, ignoreCase = true)) &&
                    (extensions.isEmpty() || entry.extension in extensions)
            }
        }
}

data class BrowserUiState(
    val left: PaneState = PaneState(),
    val right: PaneState = PaneState(),
    val active: PaneId = PaneId.LEFT,
    val showHidden: Boolean = false,
    val sort: SortSpec = SortSpec(),
    val dualPane: Boolean = true,
    val storage: StorageStats? = null,
    val rootAvailable: Boolean = false,
    /** 正在进行的文件操作；非空时显示进度对话框。 */
    val operation: OperationUiState? = null,
    /** 复制/移动检测到同名目标时的处理提示。 */
    val conflictPrompt: ConflictPrompt? = null,
    /** 一次性提示（操作摘要/校验失败），UI 展示后调用 consumeMessage()。 */
    val message: UiMessage? = null,
)

/** 会产生进度对话框的文件操作类型。 */
enum class OperationKind { COPY, MOVE, DELETE, ZIP, UNZIP }

data class OperationUiState(
    val kind: OperationKind,
    val progress: Float = 0f,
    val currentName: String = "",
    val processedItems: Int = 0,
    val totalItems: Int = 0,
    val preparing: Boolean = true,
)

data class ConflictPrompt(
    val kind: OperationKind,
    val count: Int,
)

/** 操作结束后交给 UI 的一次性反馈。Simple 用于校验/提示类文案。 */
sealed interface UiMessage {
    data class Result(
        val succeeded: Int,
        val failed: Int,
        val skipped: Int,
        val cancelled: Boolean,
        val failureReason: String? = null,
    ) : UiMessage

    data class Simple(val textRes: Int) : UiMessage
}
