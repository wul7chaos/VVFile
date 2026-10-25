package com.vvfile.app.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.R
import kotlinx.coroutines.launch

/**
 * 大文件按行虚拟化的只读查看器。
 *
 * 为什么需要它：编辑器正文是一个 `BasicTextField`，把整篇文本塞进去，Compose 要为一整段
 * 字符建排版。实测 76 万字符（2 MB 中文）时打开要 6 秒以上、主线程被占满导致输入事件被丢弃，
 * 滚动一次要 3 秒多，再大一点就直接 OOM。这里改成 `LazyColumn` 一行一个 item，
 * 任意大小的文件都只排版屏幕上那几十行，打开是毫秒级、滚动不掉帧。
 *
 * 只在 [EditorFileGateway.LARGE_TEXT_CHARS] 以上使用；小文件仍走正常编辑器。
 */
@Composable
internal fun LargeTextReader(
    text: String,
    modifier: Modifier = Modifier,
    showLineNumbers: Boolean = true,
    fontSizeSp: Float = 13f,
    truncated: Boolean = false,
    onPosition: (firstVisibleLine: Int, totalLines: Int) -> Unit = { _, _ -> },
) {
    val index = remember(text) { LineIndex(text) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val style = remember(fontSizeSp) {
        TextStyle(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.6f).sp)
    }
    val numberStyle = remember(fontSizeSp) {
        TextStyle(
            fontSize = (fontSizeSp - 1f).coerceAtLeast(9f).sp,
            lineHeight = (fontSizeSp * 1.6f).sp,
            fontFamily = FontFamily.Monospace,
        )
    }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val positionSink = rememberUpdatedState(onPosition)

    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(first, index.lineCount) {
        positionSink.value(first, index.lineCount)
    }

    var jumpOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        LargeTextBanner(
            lineCount = index.lineCount,
            truncated = truncated,
            onJump = { jumpOpen = true },
        )
        Box(Modifier.weight(1f)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 12.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    items(index.lineCount) { line ->
                        Row(Modifier.fillMaxWidth()) {
                            if (showLineNumbers) {
                                Text(
                                    text = (line + 1).toString(),
                                    style = numberStyle,
                                    color = dim.copy(alpha = 0.7f),
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(LINE_NUMBER_WIDTH),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = index.line(line),
                                style = style,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            ReaderScrollbar(
                listState = listState,
                lineCount = index.lineCount,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(12.dp)
                    .padding(vertical = 8.dp),
            )
        }
    }

    if (jumpOpen) {
        JumpToLineDialog(
            maxLine = index.lineCount,
            onConfirm = { line ->
                jumpOpen = false
                scope.launch { listState.scrollToItem((line - 1).coerceIn(0, index.lineCount - 1)) }
            },
            onDismiss = { jumpOpen = false },
        )
    }
}

@Composable
private fun LargeTextBanner(lineCount: Int, truncated: Boolean, onJump: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 4.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.editor_large_banner),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.editor_large_banner_detail, lineCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                if (truncated) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.editor_large_truncated),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        TextButton(onClick = onJump) {
            Text(stringResource(R.string.editor_goto_line), fontSize = 12.sp)
        }
    }
}

/** 右侧可拖动的进度条：22 万行的小说靠手指甩是翻不到底的。 */
@Composable
private fun ReaderScrollbar(
    listState: LazyListState,
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val total = lineCount.coerceAtLeast(1)
    val first by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val visible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = modifier) {
        val boxHeight = maxHeight
        val heightPx = with(density) { boxHeight.toPx() }.coerceAtLeast(1f)
        val thumbFraction = (visible.toFloat() / total).coerceIn(0.03f, 1f)
        val offsetFraction = (first.toFloat() / total).coerceIn(0f, 1f - thumbFraction)
        val thumbHeight = (boxHeight * thumbFraction).coerceAtLeast(24.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight)
                    .offset(y = boxHeight * offsetFraction)
                    .background(thumbColor, RoundedCornerShape(6.dp)),
            )
        }
        // 拖动/点按：把 y 位置换算成目标行。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(total, heightPx) {
                    fun seek(y: Float) {
                        val f = (y / heightPx).coerceIn(0f, 1f)
                        scope.launch {
                            listState.scrollToItem((f * total).toInt().coerceIn(0, total - 1))
                        }
                    }
                    detectDragGestures(
                        onDragStart = { offset: Offset -> seek(offset.y) },
                        onDrag = { change, _ -> seek(change.position.y) },
                    )
                },
        )
    }
}

@Composable
private fun JumpToLineDialog(maxLine: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val parsed = value.trim().toIntOrNull()?.coerceIn(1, maxLine.coerceAtLeast(1))
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_goto_line)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.editor_large_position, parsed ?: 1, maxLine),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter { it.isDigit() }.take(9) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.editor_large_jump_hint),
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onConfirm) },
            ) { Text(stringResource(R.string.editor_goto)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 行索引：只为每一行记一个起始偏移，正文仍然只有一份。
 *
 * 76 万字符 / 2.2 万行时索引约 90 KB，比拆成 2.2 万个 String 省得多，
 * 也让 `line(i)` 是 O(行长度) 的切片。
 */
internal class LineIndex(private val text: String) {

    val starts: IntArray

    init {
        var count = 1
        for (c in text) if (c == '\n') count++
        val array = IntArray(count)
        array[0] = 0
        var idx = 1
        for (i in text.indices) {
            if (text[i] == '\n') array[idx++] = i + 1
        }
        starts = array
    }

    val lineCount: Int get() = starts.size

    /** 第 [index] 行（不含换行符）；超长行截断，避免单个 item 又变成一次大排版。 */
    fun line(index: Int): String {
        if (index !in starts.indices) return ""
        val start = starts[index]
        val rawEnd = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
        val end = rawEnd.coerceAtMost(start + MAX_RENDER_LINE_CHARS)
        if (end <= start) return ""
        var result = text.substring(start, end)
        if (end < rawEnd) result += " …"
        if (result.endsWith("\r")) result = result.dropLast(1)
        return result
    }

    /** 字符偏移落在第几行（从 0 开始）。 */
    fun lineOf(offset: Int): Int {
        if (offset <= 0) return 0
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    companion object {
        /** 单行最多渲染这么多字符；再长就截断（正常文本不会有这种行）。 */
        const val MAX_RENDER_LINE_CHARS = 4_000
    }
}

private val LINE_NUMBER_WIDTH = 46.dp
