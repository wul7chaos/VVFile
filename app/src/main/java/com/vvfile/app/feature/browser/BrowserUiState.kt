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
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex in 0 until history.lastIndex
    val selectionMode: Boolean get() = selected.isNotEmpty()
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
)
