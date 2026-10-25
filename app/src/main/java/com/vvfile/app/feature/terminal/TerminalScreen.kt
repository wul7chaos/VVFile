package com.vvfile.app.feature.terminal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vvfile.app.R
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.ui.theme.VVFileTheme

/**
 * 独立可复用的终端页面，不依赖浏览器/导航代码。
 *
 * 接线方式（Lead 后续接抽屉/全屏覆盖）：
 * ```
 * TerminalScreen(onClose = { overlay = null })
 * ```
 *
 * 产品红线：root 会话只通过 `su` 启动，不接入 Shizuku、ADB shell、无障碍。
 */
@Composable
fun TerminalScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    viewModel: TerminalViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }

    BackHandler(onBack = onClose)

    val submit: () -> Unit = {
        if (input.isNotBlank() && state.running) {
            viewModel.send(input)
            input = ""
        }
    }

    val switchSession: (TerminalSessionType) -> Unit = { type ->
        if (type == TerminalSessionType.ROOT && !RootManager.isAvailable()) {
            Toast.makeText(context, R.string.terminal_root_unavailable, Toast.LENGTH_SHORT).show()
        }
        viewModel.switchSession(type)
    }

    TerminalContent(
        state = state,
        input = input,
        onInputChange = { input = it },
        onSubmit = submit,
        onClear = viewModel::clear,
        onClose = onClose,
        onSwitchSession = switchSession,
        onHistoryUp = { viewModel.historyUp()?.let { input = it } },
        onHistoryDown = { viewModel.historyDown()?.let { input = it } },
        onQuickCommand = { command -> if (state.running) viewModel.send(command) },
        fontSizeSp = fontSizeSp,
        modifier = modifier,
    )
}

/** 无状态内容层，便于预览与后续复用（不依赖 ViewModel）。 */
@Composable
internal fun TerminalContent(
    state: TerminalUiState,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onSwitchSession: (TerminalSessionType) -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onQuickCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
) {
    val palette = terminalPalette()
    val textStyle = remember(fontSizeSp, palette) { terminalTextStyle(fontSizeSp, palette.foreground) }
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
            onSwitchSession = onSwitchSession,
            onClear = onClear,
            onClose = onClose,
        )
        HorizontalDivider(color = palette.divider)
        state.error?.let { message ->
            TerminalErrorBanner(message = message)
        }
        TerminalOutput(
            lines = state.lines,
            textStyle = textStyle,
            modifier = Modifier.weight(1f),
        )
        QuickCommandBar(
            enabled = state.running,
            onQuickCommand = onQuickCommand,
        )
        HorizontalDivider(color = palette.divider)
        TerminalInputRow(
            input = input,
            prompt = if (state.sessionType == TerminalSessionType.ROOT) "#" else "\$",
            running = state.running,
            textStyle = textStyle,
            onInputChange = onInputChange,
            onSubmit = onSubmit,
            onHistoryUp = onHistoryUp,
            onHistoryDown = onHistoryDown,
        )
    }
    }
}

@Composable
private fun TerminalTopBar(
    state: TerminalUiState,
    onSwitchSession: (TerminalSessionType) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalTerminalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.terminal_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
        )
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = state.sessionType == TerminalSessionType.SH,
            onClick = { onSwitchSession(TerminalSessionType.SH) },
            label = { Text(TerminalSessionType.SH.label, fontFamily = FontFamily.Monospace) },
        )
        Spacer(Modifier.width(6.dp))
        FilterChip(
            selected = state.sessionType == TerminalSessionType.ROOT,
            onClick = { onSwitchSession(TerminalSessionType.ROOT) },
            label = { Text(TerminalSessionType.ROOT.label, fontFamily = FontFamily.Monospace) },
        )
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Outlined.ClearAll,
                contentDescription = stringResource(R.string.terminal_clear),
                tint = palette.muted,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.terminal_close),
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 自动滚底：有新输出时贴住最后一行。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    // 注意：weight 必须落在 Column 的直接子节点上，所以挂在 SelectionContainer；
    // 挂在里面的 LazyColumn 上会被忽略，输出区不铺满、底部留白。
    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(LocalTerminalPalette.current.background),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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

@Composable
private fun QuickCommandBar(
    enabled: Boolean,
    onQuickCommand: (String) -> Unit,
) {
    val palette = LocalTerminalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.terminal_quick_commands),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
        QUICK_COMMANDS.forEach { command ->
            AssistChip(
                onClick = { onQuickCommand(command) },
                enabled = enabled,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = palette.surface,
                    labelColor = palette.foreground,
                    disabledContainerColor = palette.surface,
                    disabledLabelColor = palette.muted,
                ),
                label = {
                    Text(
                        text = command,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun TerminalInputRow(
    input: String,
    prompt: String,
    running: Boolean,
    textStyle: TextStyle,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
) {
    val palette = LocalTerminalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = prompt,
            style = textStyle,
            color = palette.accent,
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { innerTextField ->
                Box {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.terminal_input_hint),
                            style = textStyle,
                            color = palette.muted,
                        )
                    }
                    innerTextField()
                }
            },
        )
        IconButton(onClick = onHistoryUp, enabled = running) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = stringResource(R.string.terminal_history_up),
                tint = if (running) palette.muted else palette.muted.copy(alpha = 0.35f),
            )
        }
        IconButton(onClick = onHistoryDown, enabled = running) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.terminal_history_down),
                tint = if (running) palette.muted else palette.muted.copy(alpha = 0.35f),
            )
        }
        IconButton(onClick = onSubmit, enabled = running && input.isNotBlank()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.terminal_send),
                tint = if (running && input.isNotBlank()) palette.accent else palette.muted.copy(alpha = 0.35f),
            )
        }
    }
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
private val QUICK_COMMANDS = listOf("ls", "cd", "pwd", "id", "whoami")

@Preview(showBackground = true, backgroundColor = 0xFF0B0E13)
@Composable
private fun TerminalContentPreview() {
    VVFileTheme {
        TerminalContent(
            state = TerminalUiState(
                lines = listOf(
                    TerminalLine(
                        text = "VVFile 终端 · sh 会话（应用自身权限）",
                        spans = listOf(AnsiSpan("VVFile 终端 · sh 会话（应用自身权限）", TerminalColor.BRIGHT_BLACK)),
                    ),
                    TerminalLine(
                        text = "total 2",
                        spans = listOf(AnsiSpan("total 2", TerminalColor.GREEN, bold = true)),
                    ),
                    TerminalLine(
                        text = "drwxr-xr-x 4 root root 4096 Download",
                        spans = listOf(AnsiSpan("drwxr-xr-x 4 root root 4096 Download", TerminalColor.BLUE)),
                    ),
                ),
                sessionType = TerminalSessionType.SH,
                running = true,
            ),
            input = "ls -la /sdcard",
            onInputChange = {},
            onSubmit = {},
            onClear = {},
            onClose = {},
            onSwitchSession = {},
            onHistoryUp = {},
            onHistoryDown = {},
            onQuickCommand = {},
        )
    }
}
