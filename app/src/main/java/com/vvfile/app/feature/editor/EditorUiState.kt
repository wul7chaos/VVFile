package com.vvfile.app.feature.editor

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
    /** 只读（当前等同于 truncated）；只读时不允许编辑与保存。 */
    val readOnly: Boolean = false,
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
) {
    /** 顶栏显示的文件名。 */
    val fileName: String get() = path.substringAfterLast('/').ifEmpty { path }

    val charCount: Int get() = text.length

    val lineCount: Int get() = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1

    /** 保存按钮可用条件：有修改、非只读、且没有正在进行的加载/保存。 */
    val canSave: Boolean get() = dirty && !readOnly && !loading && !saving && path.isNotEmpty()
}
