package com.vvfile.app.feature.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端会话管理（真 PTY）。
 *
 * 与上一版的区别：不再自己解析 ANSI、不再自己拼提示符。进程通过 `/dev/ptmx` 起在 PTY 上，
 * 输出交给 Termux 的 VT 仿真器（Apache-2.0）渲染，输入直接写进 PTY 的 stdin。
 * 因此 `clear`、`vi`、`top`、`Ctrl+C`、`Tab` 补全、窗口 resize 全部由 shell 和 ncurses 程序
 * 自己完成，App 侧没有任何模拟。
 *
 * root 依然是「敲命令」而不是开关：`su` 就是往 PTY 里写 `su\r`，由系统 su 完成提权。
 *
 * 产品红线：root 只通过 `su` 二进制，不接入 Shizuku、ADB shell、无障碍。
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /** Ctrl / Alt 是粘滞键：按一次生效，随后的一个字符发完即释放。 */
    private val _ctrlHeld = MutableStateFlow(false)
    val ctrlHeld: StateFlow<Boolean> = _ctrlHeld.asStateFlow()

    private val _altHeld = MutableStateFlow(false)
    val altHeld: StateFlow<Boolean> = _altHeld.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val clipboard: ClipboardManager? =
        application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private var view: TerminalView? = null
    private var session: TerminalSession? = null

    private val sessionClient = HostSessionClient()

    /** 主动关闭时置位，避免「结束」又被 root 退出逻辑救活。 */
    private var closing = false

    // ---------------------------------------------------------------- 视图绑定

    /**
     * 绑定 [TerminalView]。
     *
     * 会话在第一次拿到非零宽高时才会真正 `fork()`（`TerminalSession.updateSize()` 里），
     * 所以这里只做 attach，尺寸由 View 的 `onSizeChanged` 回调。
     */
    fun bindView(terminalView: TerminalView) {
        view = terminalView
        val current = ensureSession()
        if (terminalView.currentSession !== current) {
            attach(terminalView, current)
        }
    }

    private fun attach(terminalView: TerminalView, target: TerminalSession) {
        runCatching { terminalView.attachSession(target) }
            .onFailure { error ->
                Log.e(TAG, "PTY 初始化失败", error)
                _state.value = _state.value.copy(
                    running = false,
                    error = "PTY 初始化失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
    }

    fun unbindView(terminalView: TerminalView) {
        if (view === terminalView) view = null
    }

    // ---------------------------------------------------------------- 输入

    /** 粘贴 / IME 文本：直接写进 PTY。 */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendRaw(text.toByteArray(Charsets.UTF_8))
    }

    /** 原始字节直接进 PTY。控制字符（如 `^C` = 0x03）走这条。 */
    fun sendRaw(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val active = session ?: return
        if (!active.isRunning) return
        active.write(bytes, 0, bytes.size)
    }

    /** 中断前台命令：等价于键盘上的 Ctrl+C。 */
    fun sendCtrlC() {
        releaseModifiers()
        sendRaw(CONTROL_C)
    }

    /** 功能键条：交给 [TerminalView.handleKeyCode]，由 KeyHandler 决定发什么字节。 */
    fun sendKeyCode(keyCode: Int, keyMod: Int = 0) {
        val terminalView = view ?: return
        if (terminalView.mEmulator == null) return
        runCatching { terminalView.handleKeyCode(keyCode, keyMod) }
            .onFailure { Log.w(TAG, "handleKeyCode($keyCode) 失败", it) }
    }

    fun sendEnter() = sendKeyCode(KeyEvent.KEYCODE_ENTER)

    fun toggleCtrl() {
        _ctrlHeld.value = !_ctrlHeld.value
    }

    fun toggleAlt() {
        _altHeld.value = !_altHeld.value
    }

    /** 一个字符发完就释放粘滞修饰键。 */
    fun releaseModifiers() {
        if (_ctrlHeld.value) _ctrlHeld.value = false
        if (_altHeld.value) _altHeld.value = false
    }

    // ---------------------------------------------------------------- 会话动作

    /** 菜单「清空」：直接清 VT 缓冲（不是发 `clear` 命令）。 */
    fun clear() {
        session?.reset()
    }

    /** 菜单「重置」/ 功能键 ↻：同一身份重开 shell。 */
    fun reset() {
        restart()
    }

    /** 菜单「结束」：SIGKILL 掉当前 shell。 */
    fun closeSession() {
        closing = true
        val active = session
        session = null
        active?.finishIfRunning()
        _state.value = _state.value.copy(running = false, root = false)
    }

    /** 重新起一个会话（新 PTY）。旧会话被 SIGKILL，滚动历史随之丢弃。 */
    fun restart() {
        closing = false
        val spec = buildSpec()
        val fresh = TerminalSession(
            spec.command,
            spec.cwd,
            spec.args,
            spec.env,
            spec.transcriptRows,
            sessionClient,
        )
        val old = session
        session = fresh
        _state.value = TerminalUiState(running = true)

        view?.let { attach(it, fresh) }
        old?.finishIfRunning()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCleared() {
        closing = true
        view = null
        session?.finishIfRunning()
        session = null
        super.onCleared()
    }

    // ---------------------------------------------------------------- 内部

    private fun ensureSession(): TerminalSession {
        session?.let { if (it.isRunning) return it }
        val spec = buildSpec()
        val created = TerminalSession(
            spec.command,
            spec.cwd,
            spec.args,
            spec.env,
            spec.transcriptRows,
            sessionClient,
        )
        session = created
        _state.value = _state.value.copy(running = true, root = false, exitStatus = null, error = null)
        return created
    }

    private fun buildSpec(): PtyShellSpec {
        val app = getApplication<Application>()
        val home = File(app.filesDir, "home").apply { runCatching { mkdirs() } }
        val external = File(EXTERNAL_ROOT)
        val cwd = if (external.isDirectory) EXTERNAL_ROOT else home.absolutePath
        return PtyShell.spec(
            home = home.absolutePath,
            tmpDir = app.cacheDir.absolutePath,
            cwd = cwd,
            rcFile = PtyShell.writeRc(home),
        )
    }

    /**
     * 从光标所在行判断当前是不是 root。
     *
     * 不能读 `/proc/<pid>`：`su` 只是把请求转给 root 守护进程，真正的 root shell 是守护进程
     * fork 出来的另一个进程，跟我们的 shell 不同 pid，也读不到它的 `/proc`（SELinux）。
     * 但提示符是 shell 自己画的，[PtyShell.PS1] 里 `\$` 在 uid 0 下就是 `#`，
     * 所以「光标行 = 干净的提示符」时按最后一个字符判定即可。
     *
     * 只有整行看起来就是提示符（`:/path #` 或 `:/path $`）时才更新，避免把
     * `echo '#'` 这类输出误判成 root。
     */
    private fun syncRootFromPrompt(target: TerminalSession) {
        val emulator = target.emulator ?: return
        val row = emulator.cursorRow
        if (row < 0 || row >= emulator.mRows) return
        val line = runCatching {
            emulator.screen.getSelectedText(0, row, emulator.mColumns, row + 1)?.toString()
        }.getOrNull() ?: return
        val root = rootFromPromptLine(line) ?: return
        if (_state.value.root != root) {
            _state.value = _state.value.copy(root = root)
        }
    }

    /**
     * 会话回调。[TerminalSession] 的注释明确要求所有回调都在主线程，
     * 它们由会话自己的 `Handler` 派发，所以这里不用切线程。
     */
    private inner class HostSessionClient : TerminalSessionClient {

        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession !== session) return
            view?.onScreenUpdated()
            syncRootFromPrompt(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            if (changedSession !== session) return
            _state.value = _state.value.copy(title = changedSession.title)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession !== session) return
            val exitStatus = finishedSession.exitStatus
            val wasRoot = _state.value.root
            _state.value = _state.value.copy(
                running = false,
                root = false,
                exitStatus = exitStatus,
            )
            // root 会话里 exit：回到普通会话，跟上一版行为一致。
            if (!closing && wasRoot) {
                handler.postDelayed(
                    { if (!closing && session === finishedSession) restart() },
                    ROOT_EXIT_RESTART_MS,
                )
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            runCatching {
                clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val text = runCatching {
                clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(getApplication())
                    ?.toString()
            }.getOrNull()
            if (!text.isNullOrEmpty()) sendText(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            view?.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        override fun logError(tag: String, message: String) {
            Log.e(tag, message)
        }

        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun logInfo(tag: String, message: String) {
            Log.i(tag, message)
        }

        override fun logDebug(tag: String, message: String) {
            Log.d(tag, message)
        }

        override fun logVerbose(tag: String, message: String) {
            Log.v(tag, message)
        }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, "stack trace", e)
        }
    }

    companion object {
        private const val TAG = "VVTerminal"
        private const val EXTERNAL_ROOT = "/storage/emulated/0"
        private const val ROOT_EXIT_RESTART_MS = 350L
        private val CONTROL_C = byteArrayOf(0x03)
    }
}
