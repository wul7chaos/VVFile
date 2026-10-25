package com.vvfile.app.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vvfile.app.R
import com.vvfile.app.ui.theme.VVFileTheme
import com.vvfile.app.ui.theme.VVWarn

/**
 * 独立可复用的文本编辑器页面，不依赖浏览器/导航代码。
 *
 * 接线方式（Lead 后续接导航）：
 * ```
 * EditorScreen(path = entry.path, onClose = { navController.popBackStack() })
 * ```
 */
@Composable
fun EditorScreen(
    path: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showLineNumbers: Boolean = true,
    fontSizeSp: Float = 13f,
) {
    // 每次打开文件使用独立的 ViewModelStore：关闭时清空，避免复用上一个文件的缓冲区
    val owner = remember(path) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val editorViewModel: EditorViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = EditorViewModel.factory(path),
    )
    EditorScreenContent(
        path = path,
        onClose = onClose,
        modifier = modifier,
        showLineNumbers = showLineNumbers,
        fontSizeSp = fontSizeSp,
        viewModel = editorViewModel,
    )
}

@Composable
private fun EditorScreenContent(
    path: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showLineNumbers: Boolean = true,
    fontSizeSp: Float = 13f,
    viewModel: EditorViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }

    // 加载/重试完成后把文件内容灌入输入框；用户输入时两者文本一致，不会互相覆盖
    LaunchedEffect(state.text, state.loading) {
        if (!state.loading && fieldValue.text != state.text) {
            fieldValue = TextFieldValue(
                text = state.text,
                selection = TextRange(state.text.length.coerceAtLeast(0)),
            )
        }
    }

    LaunchedEffect(state.savedAt) {
        if (state.savedAt > 0L) {
            Toast.makeText(context, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // 返回键与顶栏返回同一套逻辑：有未保存修改先弹确认框
    val attemptExit: () -> Unit = {
        if (viewModel.requestExit()) onClose()
    }
    BackHandler { attemptExit() }

    EditorContent(
        state = state,
        fieldValue = fieldValue,
        onValueChange = { value ->
            fieldValue = value
            viewModel.updateText(value.text)
        },
        onBack = attemptExit,
        onSave = { viewModel.save() },
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSaveAs = { target -> viewModel.saveAs(target) },
        onReload = viewModel::load,
        onReloadWithEncoding = viewModel::reloadWithEncoding,
        onSaveWithEncoding = { encoding -> viewModel.save(encoding) },
        onLineEndingChange = viewModel::setLineEnding,
        onToggleWrap = viewModel::toggleWrap,
        onToggleReadOnlyMode = viewModel::setReadOnlyMode,
        onToggleHighlight = viewModel::toggleHighlight,
        onRetry = viewModel::load,
        onDismissError = viewModel::clearError,
        onDismissExit = viewModel::dismissExit,
        onDiscard = {
            viewModel.discardChanges()
            onClose()
        },
        showLineNumbers = showLineNumbers,
        fontSizeSp = fontSizeSp,
        modifier = modifier,
    )
}

/** 无状态内容层，便于预览与后续复用（不依赖 ViewModel）。 */
/** 无状态内容层，便于预览与后续复用（不依赖 ViewModel）。 */
@Composable
internal fun EditorContent(
    state: EditorUiState,
    fieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onSaveAs: (String) -> Unit = {},
    onReload: () -> Unit = {},
    onReloadWithEncoding: (TextEncoding) -> Unit = {},
    onSaveWithEncoding: (TextEncoding) -> Unit = {},
    onLineEndingChange: (LineEnding) -> Unit = {},
    onToggleWrap: () -> Unit = {},
    onToggleReadOnlyMode: (Boolean) -> Unit = {},
    onToggleHighlight: () -> Unit = {},
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDismissExit: () -> Unit,
    onDiscard: () -> Unit,
    showLineNumbers: Boolean = true,
    fontSizeSp: Float = 13f,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wholeWord by rememberSaveable { mutableStateOf(false) }
    var matchIndex by remember { mutableStateOf(0) }
    var showSaveAs by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var layout by rememberSaveable(state.path) { mutableStateOf(PreviewLayout.SPLIT) }
    var gotoLineText by rememberSaveable { mutableStateOf("") }
    val matches = remember(fieldValue.text, query, matchCase, wholeWord) {
        findMatches(fieldValue.text, query, matchCase, wholeWord)
    }
    LaunchedEffect(matches.size) {
        if (matches.isNotEmpty() && matchIndex > matches.lastIndex) matchIndex = 0
    }

    fun applyEdit(newText: String, cursor: Int) {
        onValueChange(
            TextFieldValue(
                text = newText,
                selection = TextRange(cursor.coerceIn(0, newText.length)),
            ),
        )
    }

    fun selectMatch(index: Int) {
        if (matches.isEmpty()) return
        val safeIndex = ((index % matches.size) + matches.size) % matches.size
        matchIndex = safeIndex
        val start = matches[safeIndex]
        onValueChange(
            fieldValue.copy(selection = TextRange(start, start + query.length)),
        )
    }

    val selectionStart = fieldValue.selection.start
    val selectionEnd = fieldValue.selection.end
    val onLineAction: (EditorLineAction) -> Unit = { action ->
        val text = fieldValue.text
        when (action) {
            EditorLineAction.COPY -> {
                clipboard.setText(AnnotatedString(EditorLineOps.lineText(text, selectionEnd)))
            }
            EditorLineAction.CUT -> {
                clipboard.setText(AnnotatedString(EditorLineOps.lineText(text, selectionEnd)))
                val (updated, cursor) = EditorLineOps.deleteLine(text, selectionEnd)
                applyEdit(updated, cursor)
            }
            EditorLineAction.DELETE -> {
                val (updated, cursor) = EditorLineOps.deleteLine(text, selectionEnd)
                applyEdit(updated, cursor)
            }
            EditorLineAction.CLEAR -> {
                val (updated, cursor) = EditorLineOps.clearLine(text, selectionEnd)
                applyEdit(updated, cursor)
            }
            EditorLineAction.REPLACE -> {
                val content = clipboard.getText()?.text.orEmpty()
                val (updated, cursor) = EditorLineOps.replaceLine(text, selectionEnd, content)
                applyEdit(updated, cursor)
            }
            EditorLineAction.DUPLICATE -> {
                val (updated, cursor) = EditorLineOps.duplicateLine(text, selectionEnd)
                applyEdit(updated, cursor)
            }
            EditorLineAction.UPPER, EditorLineAction.LOWER -> {
                val (updated, cursor) = EditorLineOps.toCase(
                    text,
                    selectionStart,
                    selectionEnd,
                    upper = action == EditorLineAction.UPPER,
                )
                applyEdit(updated, cursor)
            }
            EditorLineAction.INDENT_IN, EditorLineAction.INDENT_OUT -> {
                val (updated, cursor) = EditorLineOps.indent(
                    text,
                    selectionStart,
                    selectionEnd,
                    increase = action == EditorLineAction.INDENT_IN,
                )
                applyEdit(updated, cursor)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        EditorTopBar(
            state = state,
            onBack = onBack,
            onSave = onSave,
            onUndo = onUndo,
            onRedo = onRedo,
            onSearch = { searchVisible = !searchVisible },
            onLineAction = onLineAction,
            onSaveAs = { showSaveAs = true },
            onReload = onReload,
            onReloadWithEncoding = onReloadWithEncoding,
            onSaveWithEncoding = onSaveWithEncoding,
            onLineEnding = onLineEndingChange,
            onStats = { showStats = true },
            onGotoLine = { showGoto = true },
            onToggleWrap = onToggleWrap,
            onToggleReadOnly = { onToggleReadOnlyMode(!state.readOnlyMode) },
            onCloseFile = onDiscard,
            previewAvailable = PreviewKind.of(state.path) != PreviewKind.NONE,
            layout = layout,
            onCyclePreview = {
                layout = when (layout) {
                    PreviewLayout.SPLIT -> PreviewLayout.EDIT_ONLY
                    PreviewLayout.EDIT_ONLY -> PreviewLayout.PREVIEW_ONLY
                    PreviewLayout.PREVIEW_ONLY -> PreviewLayout.SPLIT
                }
            },
            onToggleHighlight = onToggleHighlight,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (state.readOnlyMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "只读模式已开启，编辑与保存被禁用",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onToggleReadOnlyMode(false) }) {
                    Text("关闭只读", fontSize = 12.sp)
                }
            }
        }
        if (searchVisible) {
            EditorSearchBar(
                query = query,
                onQueryChange = {
                    query = it
                    matchIndex = 0
                },
                replacement = replacement,
                onReplacementChange = { replacement = it },
                showReplace = showReplace,
                onToggleReplace = { showReplace = !showReplace },
                matchCase = matchCase,
                onToggleMatchCase = { matchCase = !matchCase },
                wholeWord = wholeWord,
                onToggleWholeWord = { wholeWord = !wholeWord },
                matchCount = matches.size,
                matchIndex = matchIndex,
                readOnly = state.editingBlocked,
                onPrevious = { selectMatch(matchIndex - 1) },
                onNext = { selectMatch(matchIndex + 1) },
                onReplace = {
                    if (matches.isNotEmpty()) {
                        val start = matches[matchIndex.coerceIn(0, matches.lastIndex)]
                        val updated = fieldValue.text.replaceRange(
                            start,
                            start + query.length,
                            replacement,
                        )
                        applyEdit(updated, start + replacement.length)
                    }
                },
                onReplaceAll = {
                    if (query.isNotEmpty()) {
                        val regex = buildSearchRegex(query, matchCase, wholeWord)
                        val updated = regex.replace(fieldValue.text) { replacement }
                        applyEdit(updated, 0)
                    }
                },
                onClose = { searchVisible = false },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        val previewKind = remember(state.path) { PreviewKind.of(state.path) }
        val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val showEditor = previewKind == PreviewKind.NONE || layout != PreviewLayout.PREVIEW_ONLY
        val showPreview = previewKind != PreviewKind.NONE && layout != PreviewLayout.EDIT_ONLY

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showEditor) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        state.loading -> LoadingState()
                        state.text.isEmpty() && state.error != null -> EditorErrorState(
                            message = state.error.orEmpty(),
                            onRetry = onRetry,
                            onBack = onBack,
                        )

                        else -> Column(Modifier.fillMaxSize()) {
                            state.error?.let { message ->
                                ErrorBanner(message = message, onDismiss = onDismissError)
                            }
                            TextEditorBody(
                                value = fieldValue,
                                onValueChange = onValueChange,
                                readOnly = state.editingBlocked,
                                wrap = state.wrap,
                                path = state.path,
                                highlight = state.highlight,
                                showLineNumbers = showLineNumbers,
                                fontSizeSp = fontSizeSp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (showEditor && showPreview) {
                VerticalDivider(color = MaterialTheme.colorScheme.outline)
            }
            if (showPreview) {
                PreviewPane(
                    kind = previewKind,
                    text = fieldValue.text,
                    dark = darkTheme,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        EditorStatusBar(state = state, fieldValue = fieldValue)
    }

    if (showSaveAs) {
        SaveAsDialog(
            initialPath = state.path,
            onConfirm = {
                showSaveAs = false
                onSaveAs(it)
            },
            onDismiss = { showSaveAs = false },
        )
    }
    if (showGoto) {
        GoToLineDialog(
            maxLine = state.lineCount,
            value = gotoLineText,
            onValueChange = { gotoLineText = it.filter(Char::isDigit).take(7) },
            onConfirm = {
                showGoto = false
                val line = gotoLineText.toIntOrNull() ?: 1
                applyEdit(fieldValue.text, offsetOfLine(fieldValue.text, line))
            },
            onDismiss = { showGoto = false },
        )
    }
    if (showStats) {
        StatsDialog(
            text = state.text,
            encoding = state.encoding,
            onDismiss = { showStats = false },
        )
    }
    if (state.confirmExit) {
        UnsavedChangesDialog(onDismiss = onDismissExit, onDiscard = onDiscard)
    }
}

@Composable
private fun EditorSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    replacement: String,
    onReplacementChange: (String) -> Unit,
    showReplace: Boolean,
    onToggleReplace: () -> Unit,
    matchCase: Boolean,
    onToggleMatchCase: () -> Unit,
    wholeWord: Boolean,
    onToggleWholeWord: () -> Unit,
    matchCount: Int,
    matchIndex: Int,
    readOnly: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.editor_search_hint),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            SearchToggle(label = "Cc", active = matchCase, onClick = onToggleMatchCase)
            SearchToggle(label = "W", active = wholeWord, onClick = onToggleWholeWord)
            Text(
                text = if (matchCount == 0) "0/0" else "${matchIndex + 1}/$matchCount",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(onClick = onPrevious, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.editor_search_previous),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.editor_search_next),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.editor_search_close),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (showReplace) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    placeholder = stringResource(R.string.editor_replace_hint),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReplace, enabled = !readOnly && matchCount > 0) {
                    Text(stringResource(R.string.editor_replace), fontSize = 12.sp)
                }
                TextButton(onClick = onReplaceAll, enabled = !readOnly && matchCount > 0) {
                    Text(stringResource(R.string.editor_replace_all), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleReplace) {
                Text(if (showReplace) "收起替换" else "替换", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SearchToggle(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}

private fun findMatches(
    text: String,
    query: String,
    matchCase: Boolean = false,
    wholeWord: Boolean = false,
): List<Int> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<Int>()
    var index = text.indexOf(query, ignoreCase = !matchCase)
    while (index >= 0) {
        val boundaryOk = if (wholeWord) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + query.length)
            (before == null || !before.isLetterOrDigit()) &&
                (after == null || !after.isLetterOrDigit())
        } else {
            true
        }
        if (boundaryOk) result.add(index)
        index = text.indexOf(
            query,
            index + query.length.coerceAtLeast(1),
            ignoreCase = !matchCase,
        )
    }
    return result
}


/** 第 [line] 行行首在文本中的偏移（从 1 开始，越界收敛）。 */
private fun offsetOfLine(text: String, line: Int): Int {
    if (line <= 1) return 0
    var current = 1
    var index = 0
    while (index < text.length && current < line) {
        if (text[index] == '\n') current++
        index++
    }
    return index.coerceAtMost(text.length)
}

private fun buildSearchRegex(query: String, matchCase: Boolean, wholeWord: Boolean): Regex {
    val body = Regex.escape(query)
    val pattern = if (wholeWord) "(?<![A-Za-z0-9])$body(?![A-Za-z0-9])" else body
    val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return Regex(pattern, options)
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSearch: () -> Unit,
    onLineAction: (EditorLineAction) -> Unit,
    onSaveAs: () -> Unit,
    onReload: () -> Unit,
    onReloadWithEncoding: (TextEncoding) -> Unit,
    onSaveWithEncoding: (TextEncoding) -> Unit,
    onLineEnding: (LineEnding) -> Unit,
    onStats: () -> Unit,
    onGotoLine: () -> Unit,
    onToggleWrap: () -> Unit,
    onToggleReadOnly: () -> Unit,
    onCloseFile: () -> Unit,
    previewAvailable: Boolean,
    layout: PreviewLayout,
    onCyclePreview: () -> Unit,
    onToggleHighlight: () -> Unit,
) {
    var editMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.editor_back),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.dirty) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = if (state.editingBlocked) {
                    stringResource(R.string.editor_readonly_hint)
                } else {
                    state.path
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = if (state.editingBlocked) {
                    VVWarn
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.saving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        } else {
            IconButton(onClick = onUndo, enabled = state.canUndo, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Undo,
                    contentDescription = "撤销",
                    tint = if (state.canUndo) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                )
            }
            IconButton(onClick = onRedo, enabled = state.canRedo, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Redo,
                    contentDescription = "重做",
                    tint = if (state.canRedo) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                )
            }
            IconButton(onClick = onSave, enabled = state.canSave, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Save,
                    contentDescription = stringResource(R.string.editor_save),
                    tint = if (state.canSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                )
            }
            if (previewAvailable) {
                IconButton(onClick = onCyclePreview, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.VerticalSplit,
                        contentDescription = "切换编辑/预览布局",
                        tint = if (layout == PreviewLayout.SPLIT) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

        }

        // 编辑：按行/选区的批量处理
        Box {
            IconButton(onClick = { editMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = "编辑",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = editMenu, onDismissRequest = { editMenu = false }) {
                EditorLineAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label, fontSize = 13.sp) },
                        onClick = {
                            editMenu = false
                            onLineAction(action)
                        },
                    )
                }
            }
        }

        // 更多：文件 / 搜索 / 显示选项
        Box {
            IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                Box {
                    DropdownMenuItem(
                        text = { Text("文件", fontSize = 13.sp) },
                        onClick = { fileMenu = true },
                    )
                    DropdownMenu(
                        expanded = fileMenu,
                        onDismissRequest = { fileMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("另存为…", fontSize = 13.sp) },
                            onClick = {
                                fileMenu = false
                                moreMenu = false
                                onSaveAs()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重新加载文件", fontSize = 13.sp) },
                            onClick = {
                                fileMenu = false
                                moreMenu = false
                                onReload()
                            },
                        )
                        TextEncoding.entries.forEach { encoding ->
                            DropdownMenuItem(
                                text = { Text("按 ${encoding.label} 重新加载", fontSize = 13.sp) },
                                onClick = {
                                    fileMenu = false
                                    moreMenu = false
                                    onReloadWithEncoding(encoding)
                                },
                            )
                        }
                        HorizontalDivider()
                        TextEncoding.entries.forEach { encoding ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("保存为 ${encoding.label}", fontSize = 13.sp)
                                        Spacer(Modifier.weight(1f))
                                        if (state.encoding == encoding) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    fileMenu = false
                                    moreMenu = false
                                    onSaveWithEncoding(encoding)
                                },
                            )
                        }
                        HorizontalDivider()
                        LineEnding.entries.forEach { ending ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("换行符 ${ending.label}", fontSize = 13.sp)
                                        Spacer(Modifier.weight(1f))
                                        if (state.lineEnding == ending) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    fileMenu = false
                                    moreMenu = false
                                    onLineEnding(ending)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("统计", fontSize = 13.sp) },
                            onClick = {
                                fileMenu = false
                                moreMenu = false
                                onStats()
                            },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text("搜索", fontSize = 13.sp) },
                    onClick = {
                        moreMenu = false
                        onSearch()
                    },
                )
                DropdownMenuItem(
                    text = { Text("转到指定行", fontSize = 13.sp) },
                    onClick = {
                        moreMenu = false
                        onGotoLine()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("语法高亮", fontSize = 13.sp) },
                    trailingIcon = {
                        if (state.highlight) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        moreMenu = false
                        onToggleHighlight()
                    },
                )
                DropdownMenuItem(
                    text = { Text("自动换行", fontSize = 13.sp) },
                    trailingIcon = {
                        if (state.wrap) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        moreMenu = false
                        onToggleWrap()
                    },
                )
                DropdownMenuItem(
                    text = { Text("只读模式", fontSize = 13.sp) },
                    trailingIcon = {
                        if (state.readOnlyMode) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        moreMenu = false
                        onToggleReadOnly()
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("关闭文件", fontSize = 13.sp) },
                    onClick = {
                        moreMenu = false
                        onCloseFile()
                    },
                )
            }
        }
    }
}

/** 编辑菜单里的行操作。 */
enum class EditorLineAction(val label: String) {
    COPY("复制行"),
    CUT("剪切行"),
    DELETE("删除行"),
    CLEAR("清空行"),
    REPLACE("替换行"),
    DUPLICATE("重复行"),
    UPPER("转为大写"),
    LOWER("转为小写"),
    INDENT_IN("增加缩进"),
    INDENT_OUT("减小缩进"),
}

@Composable
private fun TextEditorBody(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
    wrap: Boolean = true,
    path: String = "",
    highlight: Boolean = true,
    showLineNumbers: Boolean,
    fontSizeSp: Float = 13f,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val monoStyle = remember(fontSizeSp) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.4f).sp,
        )
    }
    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val gutterVisible = showLineNumbers && lineCount <= MAX_GUTTER_LINES
    val darkForSyntax = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // 语法高亮：只在开关打开、且文件不太大时计算；否则用空表，视觉上等同纯文本
    val syntaxSpans = remember(value.text, path, highlight, darkForSyntax) {
        if (!highlight) {
            emptyList()
        } else {
            EditorSyntax.spans(value.text, path, darkForSyntax)
        }
    }
    val syntaxTransformation = remember(syntaxSpans) {
        VisualTransformation { text ->
            TransformedText(
                text = AnnotatedString(
                    text = text.text,
                    spanStyles = syntaxSpans.filter { it.start < text.length && it.end <= text.length },
                ),
                offsetMapping = OffsetMapping.Identity,
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val viewportHeight = maxHeight
        // 输入框至少撑满视口，点在文字下方的空白处也能聚焦
        val fieldMinHeight = maxOf(0.dp, viewportHeight - 16.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScroll)
                .heightIn(min = viewportHeight),
        ) {
            if (gutterVisible) {
                LineNumberGutter(lineCount = lineCount, style = monoStyle)
            }
            val textBox = Modifier
                .weight(1f)
                .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            Box(modifier = textBox) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fieldMinHeight),
                    textStyle = monoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    readOnly = readOnly,
                    visualTransformation = syntaxTransformation,
                )
            }
        }
    }
}

@Composable
private fun LineNumberGutter(lineCount: Int, style: TextStyle) {
    val numbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    Text(
        text = numbers,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = Int.MAX_VALUE,
        modifier = Modifier
            .width(46.dp)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun EditorStatusBar(state: EditorUiState, fieldValue: TextFieldValue) {
    val offset = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
    val beforeCursor = fieldValue.text.substring(0, offset)
    val line = beforeCursor.count { it == '\n' } + 1
    val column = offset - (beforeCursor.lastIndexOf('\n') + 1) + 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = stringResource(R.string.editor_status_encoding, state.encoding.label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.readOnly) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.editor_readonly_badge),
                style = MaterialTheme.typography.labelMedium,
                color = VVWarn,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.editor_status_position, line, column),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.editor_status_chars, state.charCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.editor_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorErrorState(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.editor_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.editor_retry))
                }
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.editor_back))
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.editor_dismiss_error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 转到指定行：只接受数字，越界自动收敛。 */
@Composable
private fun GoToLineDialog(
    maxLine: Int,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转到指定行", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    label = { Text("行号（1-$maxLine）", fontSize = 12.sp) },
                    textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
/** 另存为：直接填路径（与文件管理器的路径风格一致）。 */
@Composable
private fun SaveAsDialog(
    initialPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by rememberSaveable(initialPath) { mutableStateOf(initialPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("另存为", fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    label = { Text("目标路径", fontSize = 12.sp) },
                    textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "保存后编辑会话会指向新文件",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(path) },
                enabled = path.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 统计：行/字符/非空白/词/UTF-8 字节。 */
@Composable
private fun StatsDialog(
    text: String,
    encoding: TextEncoding,
    onDismiss: () -> Unit,
) {
    val stats = remember(text) { EditorLineOps.stats(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统计", fontSize = 16.sp) },
        text = {
            Column {
                StatRow("行数", stats.lines.toString())
                StatRow("字符数", stats.chars.toString())
                StatRow("非空白字符", stats.charsNoSpace.toString())
                StatRow("词数", stats.words.toString())
                StatRow("UTF-8 字节", stats.bytesUtf8.toString())
                StatRow("编码", encoding.label)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
@Composable
private fun UnsavedChangesDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = { Text(stringResource(R.string.editor_unsaved_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.editor_discard),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_keep_editing))
            }
        },
    )
}

/** 行号槽最多渲染这么多行，超大预览退化为无行号，避免一次拼出几十万行字符串。 */
private const val MAX_GUTTER_LINES = 20_000

@Preview(showBackground = true, backgroundColor = 0xFF0F1218)
@Composable
private fun EditorContentPreview() {
    VVFileTheme {
        EditorContent(
            state = EditorUiState(
                path = "/sdcard/Documents/notes.md",
                text = "# 周末清单\n\n- 买咖啡豆\n- 给耳机充电\n- 备份照片\n\n记得给 VVFile 提 issue。",
                loading = false,
                dirty = true,
            ),
            fieldValue = TextFieldValue(
                text = "# 周末清单\n\n- 买咖啡豆\n- 给耳机充电\n- 备份照片\n\n记得给 VVFile 提 issue。",
                selection = TextRange(0),
            ),
            onValueChange = {},
            onBack = {},
            onSave = {},
            onRetry = {},
            onDismissError = {},
            onDismissExit = {},
            onDiscard = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0F1218)
@Composable
private fun EditorReadOnlyPreview() {
    VVFileTheme {
        EditorContent(
            state = EditorUiState(
                path = "/sdcard/logs/huge.log",
                text = "12:00:01 启动服务\n12:00:02 连接数据库\n12:00:03 就绪",
                loading = false,
                truncated = true,
                readOnly = true,
            ),
            fieldValue = TextFieldValue("12:00:01 启动服务\n12:00:02 连接数据库\n12:00:03 就绪"),
            onValueChange = {},
            onBack = {},
            onSave = {},
            onRetry = {},
            onDismissError = {},
            onDismissExit = {},
            onDiscard = {},
        )
    }
}
