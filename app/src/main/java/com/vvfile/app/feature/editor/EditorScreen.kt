package com.vvfile.app.feature.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
        onSave = viewModel::save,
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
@Composable
internal fun EditorContent(
    state: EditorUiState,
    fieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onDismissExit: () -> Unit,
    onDiscard: () -> Unit,
    showLineNumbers: Boolean = true,
    fontSizeSp: Float = 13f,
    modifier: Modifier = Modifier,
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var gotoLine by rememberSaveable { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }
    val matches = remember(fieldValue.text, query) { findMatches(fieldValue.text, query) }
    LaunchedEffect(matches.size) {
        if (matches.isNotEmpty() && matchIndex > matches.lastIndex) matchIndex = 0
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
            onSearch = { searchVisible = !searchVisible },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        if (searchVisible) {
            EditorSearchBar(
                query = query,
                onQueryChange = {
                    query = it
                    matchIndex = 0
                },
                replacement = replacement,
                onReplacementChange = { replacement = it },
                matchCount = matches.size,
                matchIndex = matchIndex,
                readOnly = state.readOnly,
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
                        onValueChange(
                            TextFieldValue(
                                text = updated,
                                selection = TextRange(start + replacement.length),
                            ),
                        )
                    }
                },
                onReplaceAll = {
                    if (query.isNotEmpty()) {
                        val updated = Regex(
                            Regex.escape(query),
                            RegexOption.IGNORE_CASE,
                        ).replace(fieldValue.text, Regex.escapeReplacement(replacement))
                        onValueChange(TextFieldValue(updated, TextRange(0)))
                    }
                },
                gotoLine = gotoLine,
                onGotoLineChange = { gotoLine = it.filter(Char::isDigit).take(7) },
                onGoto = {
                    val line = gotoLine.toIntOrNull() ?: 1
                    val offset = offsetOfLine(fieldValue.text, line)
                    onValueChange(TextFieldValue(fieldValue.text, TextRange(offset)))
                },
                onClose = { searchVisible = false },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        readOnly = state.readOnly,
                        showLineNumbers = showLineNumbers,
                        fontSizeSp = fontSizeSp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        EditorStatusBar(state = state, fieldValue = fieldValue)
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
    matchCount: Int,
    matchIndex: Int,
    readOnly: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    gotoLine: String,
    onGotoLineChange: (String) -> Unit,
    onGoto: () -> Unit,
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
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (matchCount == 0) "0/0" else "${matchIndex + 1}/$matchCount",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.editor_goto_line),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            CompactField(
                value = gotoLine,
                onValueChange = onGotoLineChange,
                placeholder = "",
                modifier = Modifier.width(64.dp),
            )
            TextButton(onClick = onGoto) {
                Text(stringResource(R.string.editor_goto), fontSize = 12.sp)
            }
        }
    }
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

private fun findMatches(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<Int>()
    var index = text.indexOf(query, ignoreCase = true)
    while (index >= 0) {
        result.add(index)
        index = text.indexOf(
            query,
            index + query.length.coerceAtLeast(1),
            ignoreCase = true,
        )
    }
    return result
}

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

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSearch: () -> Unit,
) {
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
                text = if (state.readOnly) {
                    stringResource(R.string.editor_readonly_hint)
                } else {
                    state.path
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = if (state.readOnly) VVWarn else MaterialTheme.colorScheme.onSurfaceVariant,
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
            Spacer(Modifier.width(14.dp))
        } else {
            IconButton(onClick = onSearch) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.editor_search),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSave, enabled = state.canSave) {
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
        }
    }
}

@Composable
private fun TextEditorBody(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScroll)
                    .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fieldMinHeight),
                    textStyle = monoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    readOnly = readOnly,
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
