package com.vvfile.app.feature.terminal

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.KeyHandler
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlin.math.roundToInt

/**
 * 终端页面，仿 MT 的命令行窗口：
 *
 * ```
 * 终端 root                    ⋮  ✕
 * :/storage/emulated/0 # ls -l          ← 真 PTY + VT 仿真，整屏由 shell 绘制
 * ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁
 * Esc Tab PgUp Home ↑ End ⋮
 * Ctrl Alt PgDn ← ↓ → ↻
 * ```
 *
 * 屏幕内容来自 [TerminalView]（Termux terminal-view，Apache-2.0），
 * 进程跑在 `/dev/ptmx` 的 PTY 上，所以 `clear`、`vi`、`top`、`Tab`、`Ctrl+C`、resize
 * 都是真的，App 侧不再自己解析转义序列。
 *
 * root 不是开关：敲 `su` 即可。顶栏的 `root` 徽标读的是 `/proc/<pid>/status` 的真实 uid。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 13f,
    onFontSizeChange: ((Int) -> Unit)? = null,
    viewModel: TerminalViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctrlHeld by viewModel.ctrlHeld.collectAsStateWithLifecycle()
    val altHeld by viewModel.altHeld.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = remember(dark) { if (dark) DarkTerminalPalette else LightTerminalPalette }
    val localPalette = rememberUpdatedState(palette)

    val density = LocalDensity.current.density
    val fontSp = remember { mutableStateOf(fontSizeSp.roundToInt().coerceIn(MIN_FONT_SP, MAX_FONT_SP)) }
    val persistFont = rememberUpdatedState(onFontSizeChange)

    var keepScreenOn by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }

    val terminalRef = remember { mutableStateOf<TerminalView?>(null) }
    val ime = remember(context) { context.getSystemService(InputMethodManager::class.java) }
    // 输入法是否已经顶起来；用 Compose 的 ime inset 判断，InputMethodManager.isActive
    // 在「有输入连接但键盘未显示」时也是 true，不能拿来切换。
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleRef = remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) { imeVisibleRef.value = imeVisible }

    BackHandler(onBack = onClose)

    val view = LocalView.current
    if (keepScreenOn) {
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    // 字号变化 → 重新排版（TerminalView 会顺带把列/行数通过 TIOCSWINSZ 告诉 shell）。
    LaunchedEffect(fontSp.value, density) {
        terminalRef.value?.setTextSize((fontSp.value * density).roundToInt())
    }
    LaunchedEffect(fontSp.value) {
        if (fontSp.value != fontSizeSp.roundToInt()) persistFont.value?.invoke(fontSp.value)
    }

    val applyColors: () -> Unit = {
        val emulator = terminalRef.value?.mEmulator
        if (emulator != null) {
            val colors = emulator.mColors.mCurrentColors
            val current = localPalette.value
            colors[TextStyle.COLOR_INDEX_BACKGROUND] = current.background.toArgb()
            colors[TextStyle.COLOR_INDEX_FOREGROUND] = current.foreground.toArgb()
            colors[TextStyle.COLOR_INDEX_CURSOR] = current.accent.toArgb()
            terminalRef.value?.invalidate()
        }
    }
    val applyColorsNow = rememberUpdatedState(applyColors)

    val viewClient = remember(viewModel) {
        VvTerminalViewClient(
            ctrlHeld = { viewModel.ctrlHeld.value },
            altHeld = { viewModel.altHeld.value },
            onModifierConsumed = { viewModel.releaseModifiers() },
            onTextSizeStep = { delta ->
                fontSp.value = (fontSp.value + delta).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            },
            onSingleTap = {
                terminalRef.value?.let { terminal ->
                    if (imeVisibleRef.value) {
                        ime?.hideSoftInputFromWindow(terminal.windowToken, 0)
                    } else {
                        terminal.requestFocus()
                        ime?.showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            },
            onEmulatorReady = { applyColorsNow.value() },
        )
    }

    val sendKey: (TerminalKey) -> Unit = { key ->
        when (key) {
            TerminalKey.MENU -> menuOpen = true
            TerminalKey.CTRL -> viewModel.toggleCtrl()
            TerminalKey.ALT -> viewModel.toggleAlt()
            TerminalKey.RESET -> viewModel.restart()
            TerminalKey.INTERRUPT -> viewModel.sendCtrlC()
            TerminalKey.ARROWS_CTRL -> viewModel.sendKeyCode(key.keyCode, KeyHandler.KEYMOD_CTRL)
            else -> viewModel.sendKeyCode(key.keyCode)
        }
    }

    TerminalChrome(
        state = state,
        palette = palette,
        ctrlHeld = ctrlHeld,
        altHeld = altHeld,
        menuOpen = menuOpen,
        onMenuOpenChange = { menuOpen = it },
        onKeepScreenOn = { keepScreenOn = !keepScreenOn },
        keepScreenOn = keepScreenOn,
        onClear = { viewModel.clear() },
        onReset = { viewModel.restart() },
        onEndSession = { viewModel.closeSession() },
        onInterrupt = { viewModel.sendCtrlC() },
        onSettings = { fontDialog = true },
        onPaste = {
            val text = readClipboard(context)
            if (text.isNotEmpty()) viewModel.sendText(text)
        },
        onClose = onClose,
        onKey = sendKey,
        modifier = modifier,
        terminal = {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(viewClient)
                        setTextSize((fontSp.value * density).roundToInt())
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setBackgroundColor(localPalette.value.background.toArgb())
                        terminalRef.value = this
                        requestFocus()
                        viewModel.bindView(this)
                    }
                },
                update = { terminal ->
                    terminal.setBackgroundColor(localPalette.value.background.toArgb())
                },
            )
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            terminalRef.value?.let { viewModel.unbindView(it) }
            terminalRef.value = null
        }
    }

    if (fontDialog) {
        FontSizeDialog(
            current = fontSp.value,
            onPick = { size ->
                fontDialog = false
                fontSp.value = size.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            },
            onDismiss = { fontDialog = false },
        )
    }
}

@Composable
private fun TerminalChrome(
    state: TerminalUiState,
    palette: TerminalPalette,
    ctrlHeld: Boolean,
    altHeld: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
    onEndSession: () -> Unit,
    onInterrupt: () -> Unit,
    onSettings: () -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
    onKey: (TerminalKey) -> Unit,
    modifier: Modifier = Modifier,
    terminal: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        TerminalTopBar(
            state = state,
            palette = palette,
            menuOpen = menuOpen,
            onMenuOpenChange = onMenuOpenChange,
            keepScreenOn = keepScreenOn,
            onKeepScreenOn = onKeepScreenOn,
            onClear = onClear,
            onReset = onReset,
            onEndSession = onEndSession,
            onInterrupt = onInterrupt,
            onSettings = onSettings,
            onPaste = onPaste,
            onClose = onClose,
        )
        HorizontalDivider(color = palette.divider)

        state.error?.let { message ->
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

        Box(modifier = Modifier.weight(1f)) {
            terminal()
            if (!state.running) {
                Text(
                    text = "会话已结束 · 点 ↻ 重开",
                    color = palette.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(palette.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        TerminalKeyBar(
            palette = palette,
            ctrlHeld = ctrlHeld,
            altHeld = altHeld,
            onKey = onKey,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun TerminalTopBar(
    state: TerminalUiState,
    palette: TerminalPalette,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: () -> Unit,
    onClear: () -> Unit,
    onReset: () -> Unit,
    onEndSession: () -> Unit,
    onInterrupt: () -> Unit,
    onSettings: () -> Unit,
    onPaste: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.title?.takeIf { it.isNotBlank() } ?: "终端",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.foreground,
            maxLines = 1,
        )
        val badge = state.subtitle
        if (badge.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = badge,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (state.root) palette.accent else palette.muted,
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
                    text = { Text("清空") },
                    onClick = { onMenuOpenChange(false); onClear() },
                )
                DropdownMenuItem(
                    text = { Text("发送 Ctrl+C") },
                    onClick = { onMenuOpenChange(false); onInterrupt() },
                )
                DropdownMenuItem(
                    text = { Text(if (keepScreenOn) "唤醒锁 ✓" else "唤醒锁") },
                    onClick = { onMenuOpenChange(false); onKeepScreenOn() },
                )
                DropdownMenuItem(
                    text = { Text("结束") },
                    onClick = { onMenuOpenChange(false); onEndSession() },
                )
                DropdownMenuItem(
                    text = { Text("重置") },
                    onClick = { onMenuOpenChange(false); onReset() },
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    onClick = { onMenuOpenChange(false); onSettings() },
                )
                DropdownMenuItem(
                    text = { Text("粘贴") },
                    onClick = { onMenuOpenChange(false); onPaste() },
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

private enum class TerminalKey(val label: String, val keyCode: Int = 0) {
    ESC("Esc", KeyEvent.KEYCODE_ESCAPE),
    TAB("Tab", KeyEvent.KEYCODE_TAB),
    PGUP("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    HOME("Home", KeyEvent.KEYCODE_MOVE_HOME),
    UP("↑", KeyEvent.KEYCODE_DPAD_UP),
    END("End", KeyEvent.KEYCODE_MOVE_END),
    MENU("⋮"),

    CTRL("Ctrl"),
    ALT("Alt"),
    PGDN("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    LEFT("←", KeyEvent.KEYCODE_DPAD_LEFT),
    DOWN("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    RIGHT("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    INTERRUPT("^C"),
    ARROWS_CTRL("^←", KeyEvent.KEYCODE_DPAD_LEFT),
    RESET("↻"),
}

/** MT 的两行功能键条。 */
@Composable
private fun TerminalKeyBar(
    palette: TerminalPalette,
    ctrlHeld: Boolean,
    altHeld: Boolean,
    onKey: (TerminalKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface),
    ) {
        HorizontalDivider(color = palette.divider)
        ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                row.forEach { key ->
                    val active = (key == TerminalKey.CTRL && ctrlHeld) ||
                        (key == TerminalKey.ALT && altHeld)
                    KeyCell(
                        key = key,
                        palette = palette,
                        active = active,
                        modifier = Modifier.weight(1f),
                        onClick = { onKey(key) },
                    )
                }
            }
        }
    }
}

private val ROWS = listOf(
    listOf(
        TerminalKey.ESC, TerminalKey.TAB, TerminalKey.PGUP, TerminalKey.HOME,
        TerminalKey.UP, TerminalKey.END, TerminalKey.INTERRUPT, TerminalKey.MENU,
    ),
    listOf(
        TerminalKey.CTRL, TerminalKey.ALT, TerminalKey.ARROWS_CTRL, TerminalKey.PGDN,
        TerminalKey.LEFT, TerminalKey.DOWN, TerminalKey.RIGHT, TerminalKey.RESET,
    ),
)

@Composable
private fun KeyCell(
    key: TerminalKey,
    palette: TerminalPalette,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .background(if (active) palette.accent.copy(alpha = 0.22f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = key.label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = when {
                active -> palette.accent
                key == TerminalKey.CTRL || key == TerminalKey.ALT -> palette.muted
                else -> palette.foreground
            },
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
                listOf(10, 11, 12, 13, 14, 16, 18, 20).forEach { size ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .combinedClickable(onClick = { picked = size }, onLongClick = { picked = size }),
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

private fun readClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return runCatching {
        manager?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
    }.getOrDefault("")
}

private class TerminalPalette(
    val background: Color,
    val surface: Color,
    val divider: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
)

private val DarkTerminalPalette = TerminalPalette(
    background = Color(0xFF0B0E13),
    surface = Color(0xFF12161E),
    divider = Color(0xFF232A35),
    foreground = Color(0xFFD7DEE8),
    muted = Color(0xFF7F8794),
    accent = Color(0xFF34D399),
)

private val LightTerminalPalette = TerminalPalette(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFEFF2F7),
    divider = Color(0xFFDCE0E8),
    foreground = Color(0xFF1B1F27),
    muted = Color(0xFF5A6270),
    accent = Color(0xFF0B8A5F),
)

private const val MIN_FONT_SP = 8
private const val MAX_FONT_SP = 32
