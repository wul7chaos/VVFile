package com.vvfile.app.feature.terminal

import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vvfile.app.ui.theme.VVFileTheme
import kotlinx.coroutines.launch

/**
 * 终端页面，仿 MT 的命令行窗口：
 *
 * ```
 * 终端                                  ⋮  ✕
 * :/storage/emulated/0 $ ls -l          ← 输出区（可滚动、可选中复制）
 * ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁
 * :/storage/emulated/0 $ |              ← 输入行，提示符与输入同一行
 * Esc Tab PgUp Home ↑ End ⋮
 * Ctrl Alt PgDn ← ↓ → ↻
 * ```
 *
 * root 不是开关，而是敲 `su` / `sudo <命令>`；顶部不再有 sh/root 切换。
 * 产品红线：root 只通过 `su` 启动，不接入 Shizuku、ADB shell、无障碍。
 */
@Composable
fun TerminalScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    onFontSizeChange: ((Int) -> Unit)? = null,
    viewModel: TerminalViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var keepScreenOn by rememberSaveable { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    BackHandler(onBack = onClose)

    if (keepScreenOn) {
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    val submit: () -> Unit = {
        if (state.running && input.text.isNotBlank()) {
            viewModel.send(input.text)
            input = TextFieldValue("")
        }
    }

    TerminalContent(
        state = state,
        input = input,
        onInputChange = { input = it },
        onSubmit = submit,
        onClose = onClose,
        onMenu = object : TerminalMenuActions {
            override val keepScreenOn: Boolean get() = keepScreenOn

            override fun toggleKeepScreenOn() {
                keepScreenOn = !keepScreenOn
            }

            override fun endSession() = viewModel.closeSession()

            override fun reset() = viewModel.reset()

            override fun openSettings() {
                fontDialog = true
            }

            override fun paste(text: String) {
                input = input.copy(text = input.text + text, selection = TextRange(input.text.length + text.length))
            }
        },
        onHistoryUp = {
            viewModel.historyUp()?.let { input = TextFieldValue(it, TextRange(it.length)) }
        },
        onHistoryDown = {
            viewModel.historyDown()?.let { input = TextFieldValue(it, TextRange(it.length)) }
        },
        onSendKey = viewModel::sendKey,
        fontSizeSp = fontSizeSp,
        modifier = modifier,
    )

    if (fontDialog) {
        FontSizeDialog(
            current = fontSizeSp.toInt(),
            onPick = { size ->
                fontDialog = false
                onFontSizeChange?.invoke(size)
            },
            onDismiss = { fontDialog = false },
        )
    }
}

/** 菜单里能做的事；抽成接口让 [TerminalContent] 保持无状态，便于预览。 */
internal interface TerminalMenuActions {
    val keepScreenOn: Boolean
    fun toggleKeepScreenOn()
    fun endSession()
    fun reset()
    fun openSettings()
    fun paste(text: String)
}

@Composable
internal fun TerminalContent(
    state: TerminalUiState,
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onMenu: TerminalMenuActions,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onSendKey: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
) {
    val palette = terminalPalette()
    val textStyle = remember(fontSizeSp, palette) { terminalTextStyle(fontSizeSp, palette.foreground) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val keyHandler: (TerminalKey) -> Unit = { key ->
        when (key) {
            TerminalKey.ESC -> onSendKey("\u001b")
            TerminalKey.TAB -> onSendKey("\t")
            TerminalKey.PGUP -> scope.launch {
                listState.scrollToItem((listState.firstVisibleItemIndex - PAGE_LINES).coerceAtLeast(0))
            }
            TerminalKey.PGDN -> scope.launch {
                val target = (listState.firstVisibleItemIndex + PAGE_LINES)
                    .coerceAtMost(state.lines.lastIndex.coerceAtLeast(0))
                listState.scrollToItem(target)
            }
            TerminalKey.HOME -> scope.launch { listState.scrollToItem(0) }
            TerminalKey.END -> scope.launch {
                if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex)
            }
            TerminalKey.UP -> onHistoryUp()
            TerminalKey.DOWN -> onHistoryDown()
            TerminalKey.LEFT -> {
                val cursor = (input.selection.start - 1).coerceAtLeast(0)
                onInputChange(input.copy(selection = TextRange(cursor)))
            }
            TerminalKey.RIGHT -> {
                val cursor = (input.selection.end + 1).coerceAtMost(input.text.length)
                onInputChange(input.copy(selection = TextRange(cursor)))
            }
            TerminalKey.RESET -> onMenu.reset()
            TerminalKey.MENU -> menuOpen = true
            TerminalKey.CTRL, TerminalKey.ALT ->
                Toast.makeText(context, "无 PTY，组合键不可用", Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalTerminalPalette provides palette) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(palette.background)
                .statusBarsPadding()
                .imePadding(),
        ) {
            TerminalTopBar(
                state = state,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onMenu = onMenu,
                onClose = onClose,
            )
            HorizontalDivider(color = palette.divider)

            state.error?.let { message ->
                TerminalErrorBanner(message = message)
            }

            TerminalOutput(
                lines = state.lines,
                textStyle = textStyle,
                listState = listState,
                modifier = Modifier.weight(1f),
            )

            HorizontalDivider(color = palette.divider)

            TerminalInputRow(
                prompt = state.prompt,
                input = input,
                textStyle = textStyle,
                running = state.running,
                onInputChange = onInputChange,
                onSubmit = onSubmit,
            )

            TerminalKeyBar(
                onKey = keyHandler,
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun TerminalTopBar(
    state: TerminalUiState,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onMenu: TerminalMenuActions,
    onClose: () -> Unit,
) {
    val palette = LocalTerminalPalette.current
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "终端",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
        )
        if (state.sessionType == TerminalSessionType.ROOT) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "root",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = palette.accent,
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            IconButton(onClick = { onMenuOpenChange(true) }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "菜单",
                    tint = palette.foreground,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                DropdownMenuItem(
                    text = { Text(if (onMenu.keepScreenOn) "唤醒锁 ✓" else "唤醒锁") },
                    onClick = {
                        onMenuOpenChange(false)
                        onMenu.toggleKeepScreenOn()
                    },
                )
                DropdownMenuItem(
                    text = { Text("结束") },
                    onClick = {
                        onMenuOpenChange(false)
                        onMenu.endSession()
                    },
                )
                DropdownMenuItem(
                    text = { Text("重置") },
                    onClick = {
                        onMenuOpenChange(false)
                        onMenu.reset()
                    },
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    onClick = {
                        onMenuOpenChange(false)
                        onMenu.openSettings()
                    },
                )
                DropdownMenuItem(
                    text = { Text("粘贴") },
                    onClick = {
                        onMenuOpenChange(false)
                        val text = clipboard.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            .orEmpty()
                            .replace("\n", " ")
                        if (text.isNotEmpty()) onMenu.paste(text)
                    },
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = palette.foreground,
            )
        }
    }
}

@Composable
private fun TerminalErrorBanner(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TerminalOutput(
    lines: List<TerminalLine>,
    textStyle: TextStyle,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // 自动滚底：用户没往上翻时贴住最后一行。
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= lines.lastIndex - 2 || listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    // weight 必须落在 Column 的直接子节点上，所以挂在 SelectionContainer。
    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(LocalTerminalPalette.current.background),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                TerminalLineText(line, textStyle)
            }
        }
    }
}

@Composable
private fun TerminalLineText(line: TerminalLine, textStyle: TextStyle) {
    val palette = LocalTerminalPalette.current
    val annotated = remember(line, palette) { line.toAnnotatedString(palette) }
    Text(
        text = if (annotated.text.isEmpty()) AnnotatedString(" ") else annotated,
        style = textStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 提示符 + 输入同一行，跟真终端一致。 */
@Composable
private fun TerminalInputRow(
    prompt: String,
    input: TextFieldValue,
    textStyle: TextStyle,
    running: Boolean,
    onInputChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
) {
    val palette = LocalTerminalPalette.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prompt,
            style = textStyle,
            color = palette.accent,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .padding(vertical = 8.dp),
            textStyle = textStyle,
            singleLine = true,
            enabled = running,
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        )
    }
}

private enum class TerminalKey(val label: String) {
    ESC("Esc"),
    TAB("Tab"),
    PGUP("PgUp"),
    HOME("Home"),
    UP("↑"),
    END("End"),
    MENU("⋮"),
    CTRL("Ctrl"),
    ALT("Alt"),
    PGDN("PgDn"),
    LEFT("←"),
    DOWN("↓"),
    RIGHT("→"),
    RESET("↻"),
}

/** MT 的两行功能键条。 */
@Composable
private fun TerminalKeyBar(
    onKey: (TerminalKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalTerminalPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface),
    ) {
        HorizontalDivider(color = palette.divider)
        listOf(
            listOf(TerminalKey.ESC, TerminalKey.TAB, TerminalKey.PGUP, TerminalKey.HOME, TerminalKey.UP, TerminalKey.END, TerminalKey.MENU),
            listOf(TerminalKey.CTRL, TerminalKey.ALT, TerminalKey.PGDN, TerminalKey.LEFT, TerminalKey.DOWN, TerminalKey.RIGHT, TerminalKey.RESET),
        ).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    KeyCell(
                        key = key,
                        modifier = Modifier.weight(1f),
                        muted = key == TerminalKey.CTRL || key == TerminalKey.ALT,
                        onClick = { onKey(key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCell(
    key: TerminalKey,
    modifier: Modifier = Modifier,
    muted: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalTerminalPalette.current
    Box(
        modifier = modifier
            .height(38.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (muted) palette.muted.copy(alpha = 0.55f) else palette.foreground,
        )
    }
}

@Composable
private fun FontSizeDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("终端字号") },
        text = {
            Column {
                listOf(11, 12, 13, 14, 16, 18, 20).forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clickable { picked = size },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == size, onClick = { picked = size })
                        Spacer(Modifier.width(8.dp))
                        Text("$size sp", fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun TerminalLine.toAnnotatedString(palette: TerminalPalette): AnnotatedString = buildAnnotatedString {
    if (spans.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var styledLength = 0
    spans.forEach { span ->
        withStyle(
            SpanStyle(
                color = palette.ansi(span.color),
                fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
            ),
        ) {
            append(span.text)
        }
        styledLength += span.text.length
    }
    // 极端情况下（如退格跨段）纯文本比分段多，补上剩余部分。
    if (styledLength < text.length) {
        append(text.substring(styledLength))
    }
}

/**
 * 终端配色：跟随主题明暗，浅色主题下不再是一片黑。
 *
 * ANSI 16 色分别给深/浅两套表，保证两种背景下都能看清。
 */
private class TerminalPalette(
    val background: Color,
    val surface: Color,
    val divider: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    private val ansiTable: Map<TerminalColor, Color>,
) {
    fun ansi(color: TerminalColor): Color = ansiTable[color] ?: foreground
}

private val DarkTerminalPalette = TerminalPalette(
    background = Color(0xFF0B0E13),
    surface = Color(0xFF12161E),
    divider = Color(0xFF232A35),
    foreground = Color(0xFFD7DEE8),
    muted = Color(0xFF7F8794),
    accent = Color(0xFF34D399),
    ansiTable = mapOf(
        TerminalColor.BLACK to Color(0xFF5C6370),
        TerminalColor.RED to Color(0xFFE06C75),
        TerminalColor.GREEN to Color(0xFF98C379),
        TerminalColor.YELLOW to Color(0xFFE5C07B),
        TerminalColor.BLUE to Color(0xFF61AFEF),
        TerminalColor.MAGENTA to Color(0xFFC678DD),
        TerminalColor.CYAN to Color(0xFF56B6C2),
        TerminalColor.WHITE to Color(0xFFD7DEE8),
        TerminalColor.BRIGHT_BLACK to Color(0xFF7F8794),
        TerminalColor.BRIGHT_RED to Color(0xFFFF7B86),
        TerminalColor.BRIGHT_GREEN to Color(0xFFB5E890),
        TerminalColor.BRIGHT_YELLOW to Color(0xFFFFD68A),
        TerminalColor.BRIGHT_BLUE to Color(0xFF8CC8FF),
        TerminalColor.BRIGHT_MAGENTA to Color(0xFFE29BFF),
        TerminalColor.BRIGHT_CYAN to Color(0xFF7FE0EA),
        TerminalColor.BRIGHT_WHITE to Color(0xFFFFFFFF),
    ),
)

private val LightTerminalPalette = TerminalPalette(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFEFF2F7),
    divider = Color(0xFFDCE0E8),
    foreground = Color(0xFF1B1F27),
    muted = Color(0xFF5A6270),
    accent = Color(0xFF0B8A5F),
    ansiTable = mapOf(
        TerminalColor.BLACK to Color(0xFF3F4653),
        TerminalColor.RED to Color(0xFFB3261E),
        TerminalColor.GREEN to Color(0xFF1B6E3C),
        TerminalColor.YELLOW to Color(0xFF7A5A00),
        TerminalColor.BLUE to Color(0xFF1A4F8A),
        TerminalColor.MAGENTA to Color(0xFF7B2E8E),
        TerminalColor.CYAN to Color(0xFF0B6172),
        TerminalColor.WHITE to Color(0xFF2A2F3A),
        TerminalColor.BRIGHT_BLACK to Color(0xFF525A68),
        TerminalColor.BRIGHT_RED to Color(0xFFC0392B),
        TerminalColor.BRIGHT_GREEN to Color(0xFF14783F),
        TerminalColor.BRIGHT_YELLOW to Color(0xFF8A6500),
        TerminalColor.BRIGHT_BLUE to Color(0xFF1F5FA8),
        TerminalColor.BRIGHT_MAGENTA to Color(0xFF8E35A3),
        TerminalColor.BRIGHT_CYAN to Color(0xFF0E7490),
        TerminalColor.BRIGHT_WHITE to Color(0xFF11141A),
    ),
)

private val LocalTerminalPalette = staticCompositionLocalOf { DarkTerminalPalette }

@Composable
private fun terminalPalette(): TerminalPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        DarkTerminalPalette
    } else {
        LightTerminalPalette
    }

private fun terminalTextStyle(fontSizeSp: Float, color: Color): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * 1.45f).sp,
    color = color,
)

private const val PAGE_LINES = 12

@Preview(showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun TerminalContentPreview() {
    VVFileTheme {
        TerminalContent(
            state = TerminalUiState(
                lines = listOf(
                    TerminalLine(
                        text = ":/storage/emulated/0 $ ls",
                        spans = listOf(AnsiSpan(":/storage/emulated/0 $ ", TerminalColor.BRIGHT_GREEN), AnsiSpan("ls")),
                    ),
                    TerminalLine(
                        text = "Download  Documents  VVFile",
                        spans = listOf(AnsiSpan("Download  Documents  VVFile", TerminalColor.BLUE)),
                    ),
                ),
                sessionType = TerminalSessionType.SH,
                running = true,
                cwd = "/storage/emulated/0",
            ),
            input = TextFieldValue("ls -l"),
            onInputChange = {},
            onSubmit = {},
            onClose = {},
            onMenu = object : TerminalMenuActions {
                override val keepScreenOn: Boolean = false
                override fun toggleKeepScreenOn() = Unit
                override fun endSession() = Unit
                override fun reset() = Unit
                override fun openSettings() = Unit
                override fun paste(text: String) = Unit
            },
            onHistoryUp = {},
            onHistoryDown = {},
            onSendKey = {},
        )
    }
}
