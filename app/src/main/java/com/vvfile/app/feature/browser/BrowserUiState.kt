package com.vvfile.app.feature.browser

import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.SortSpec
import com.vvfile.app.data.model.StorageStats
import com.vvfile.app.feature.archive.ArchiveEntryInfo
import com.vvfile.app.feature.archive.ArchiveIndex

enum class PaneId { LEFT, RIGHT }

/**
 * 窗格里的压缩包浏览状态。
 *
 * 压缩包不再另开界面：点哪个窗格里的包，就在那个窗格里把条目铺成列表（和目录一样逐层看），
 * 退回包根再退一次就回到原来的目录。
 */
data class ArchiveView(
    val path: String,
    val name: String,
    /** 条目的完整列表；null 表示还在读。 */
    val entries: List<ArchiveEntryInfo>? = null,
    /** 包内当前目录，空串是包根。 */
    val currentDir: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    /** 密码已经验证过（7z `-mhe` / RAR `-hp`）。 */
    val unlocked: Boolean = false,
    /** 正在解出的条目名；非空时列表顶部显示细进度线。 */
    val extracting: String? = null,
) {
    /** 当前这一层的条目（目录在前，文件在后）。 */
    val visibleEntries: List<ArchiveEntryInfo>
        get() = entries?.let { ArchiveIndex.children(it, currentDir) }.orEmpty()

    val canGoUp: Boolean get() = currentDir.isNotEmpty()
}

/** 窗格状态。 */
data class PaneState(
    val path: String = "",
    val entries: List<FileEntry> = emptyList(),
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
    val loading: Boolean = true,
    /** 下拉刷新进行中：与 [loading] 分开，避免普通导航时误显示下拉指示器。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    /** 已选条目的绝对路径集合；非空即处于选择态。 */
    val selected: Set<String> = emptySet(),
    /** 关键字过滤（名称，忽略大小写）。 */
    val filterQuery: String = "",
    /** 扩展名过滤，逗号/空格分隔；空表示不过滤。 */
    val filterExtensions: String = "",
    /** 非空表示这个窗格正在浏览压缩包，列表显示的是包内条目。 */
    val archive: ArchiveView? = null,
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
    /** ASK 策略下等待用户确认访问系统分区。 */
    val rootPrompt: Boolean = false,
    /** 正在进行的文件操作；非空时显示进度对话框。 */
    val operation: OperationUiState? = null,
    /** 复制/移动检测到同名目标时的处理提示。 */
    val conflictPrompt: ConflictPrompt? = null,
    /** 解压加密压缩包时等待密码。 */
    val zipPasswordPrompt: ZipPasswordPrompt? = null,
    /** 窗格里打开压缩包时等待密码（读列表或解某一条）。 */
    val archivePrompt: ArchivePromptState? = null,
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

/**
 * 解压加密压缩包时的密码请求。
 *
 * [wrong] 为 true 表示上一次输入没解开：弹窗留在原地并显示「密码不正确」，
 * 不让用户从头再点一遍解压。
 */
data class ZipPasswordPrompt(
    val zipPath: String,
    val targetDir: String,
    val wrong: Boolean = false,
)

/**
 * 窗格里打开压缩包时的密码请求。
 *
 * [entryName] 为空表示连条目列表都读不出来（7z `-mhe` / RAR `-hp`）；
 * 非空表示只挡解这一条。两种都留在弹窗里重试，[error] 就是「密码不正确」。
 */
data class ArchivePromptState(
    val pane: PaneId,
    val archivePath: String,
    val fileName: String,
    val entryName: String? = null,
    val error: String? = null,
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
