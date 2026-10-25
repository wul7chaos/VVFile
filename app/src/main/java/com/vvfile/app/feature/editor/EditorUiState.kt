package com.vvfile.app.feature.editor

/** 保存时使用的换行符。 */
enum class LineEnding(val label: String, val sequence: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n"),
}

/**
 * 编辑器页面状态。
 *
 * 事件用递增计数表达（[savedAt]），避免一次性事件被重组重复消费：
 * 页面比较前后值决定是否弹 Toast。
 */
data class EditorUiState(
    val path: String = "",
    val text: String = "",
    val encoding: TextEncoding = TextEncoding.UTF_8,
    /** 文件超过 5 MB，只加载了前 2 MB 预览。 */
    val truncated: Boolean = false,
    /** 只读（truncated 或用户手动开启只读模式）；只读时不允许编辑与保存。 */
    val readOnly: Boolean = false,
    /** 用户在「更多 → 只读模式」里手动打开的只读。 */
    val readOnlyMode: Boolean = false,
    /** 相对上次成功打开/保存是否有修改。 */
    val dirty: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** 可直接展示的错误文案；加载失败与保存失败共用。 */
    val error: String? = null,
    /** 未保存时点返回：需要页面弹确认对话框。 */
    val confirmExit: Boolean = false,
    /** 每次保存成功 +1，页面据此弹一次"已保存"。 */
    val savedAt: Long = 0L,
    /** 保存时使用的换行符。 */
    val lineEnding: LineEnding = LineEnding.LF,
    /** 自动换行（关掉后长行横向滚动）。 */
    val wrap: Boolean = true,
    /** 语法高亮开关（常见代码文件）。 */
    val highlight: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    /** 顶栏显示的文件名。 */
    val fileName: String get() = path.substringAfterLast('/').ifEmpty { path }

    val charCount: Int get() = text.length

    /**
     * 文本太大，正文改用按行虚拟化的只读查看器（[LargeTextReader]），
     * 不再塞进单个 `BasicTextField`。阈值见 [EditorFileGateway.LARGE_TEXT_CHARS]。
     */
    val isLargeText: Boolean get() = text.length > EditorFileGateway.LARGE_TEXT_CHARS

    val lineCount: Int get() = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1

    /** 只读 = 截断预览 或 手动只读。 */
    val editingBlocked: Boolean get() = readOnly || readOnlyMode

    /** 保存按钮可用条件：有修改、非只读、且没有正在进行的加载/保存。 */
    val canSave: Boolean
        get() = dirty && !editingBlocked && !loading && !saving && path.isNotEmpty()
}
