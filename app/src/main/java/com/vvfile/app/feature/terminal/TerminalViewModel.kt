package com.vvfile.app.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.core.shell.RootState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 终端页面的会话管理。
 *
 * 页面只负责渲染与输入；进程、ANSI 解析、环形缓冲与历史都在 [TerminalSession]。
 *
 * 身份切换不再走顶部按钮，而是像真终端一样敲命令：
 * - `su`：把当前会话切到 root（同一个窗口、同一份滚动历史）；
 * - `sudo <cmd>`：只让这一条命令以 root 执行，会话身份不变；
 * - root 会话里 `exit`：进程结束，自动回到普通会话。
 *
 * 产品红线：root 只通过 `su` 二进制，不接入 Shizuku、ADB shell、无障碍。
 */
class TerminalViewModel(
    private val sessionFactory: (TerminalSessionType) -> TerminalSession = { TerminalSession(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var session: TerminalSession? = null
    private var observeJob: Job? = null

    /** 主动关闭会话时置位，避免「退出 root 自动回 sh」把窗口又救活。 */
    private var closing = false

    init {
        openSession(TerminalSessionType.SH)
    }

    /** 发送命令：先回显，再按 su / sudo / 普通命令分流。 */
    fun send(command: String) {
        val text = command.trim()
        if (text.isEmpty()) return
        val active = session ?: return
        if (active.status.value != TerminalStatus.RUNNING) return

        // clear / cls 是 shell 内建之外的屏幕动作：无 PTY 时 clear 输出的转义序列会被剥离，
        // 所以这里直接清屏，效果和真终端一致。
        if (isClearCommand(text)) {
            clear()
            _state.update { it.copy(historyIndex = -1) }
            return
        }

        active.echo(_state.value.prompt, text)
        _state.update { it.copy(historyIndex = -1) }

        when {
            isSuCommand(text) -> {
                active.remember(text)
                requestRoot(active)
            }

            text == "sudo" || text.startsWith("sudo ") -> {
                active.remember(text)
                val inner = text.removePrefix("sudo").trim()
                if (inner.isEmpty()) {
                    active.printNotice("[用法：sudo <命令>]")
                } else {
                    active.runOnce(inner)
                }
            }

            else -> active.write(text)
        }
    }

    /** 功能键：Esc / Tab 直接写进 stdin。 */
    fun sendKey(raw: String) {
        session?.sendKey(raw)
    }

    /** 清空当前会话输出。 */
    fun clear() {
        session?.clear()
        _state.update { it.copy(lines = emptyList()) }
    }

    /** 菜单「重置」：同一身份重启 shell（缓冲保留）。 */
    fun reset() {
        val active = session
        closing = false
        if (active == null) {
            openSession(TerminalSessionType.SH)
            return
        }
        if (active.status.value == TerminalStatus.RUNNING) {
            active.reset()
        } else {
            openSession(active.type.value)
        }
    }

    /** 菜单「结束」：杀掉当前 shell，窗口保留。 */
    fun closeSession() {
        closing = true
        session?.close()
        _state.update { it.copy(running = false) }
    }

    /** 上一条历史命令；没有历史时返回 null（输入框保持原样）。 */
    fun historyUp(): String? {
        val active = session ?: return null
        val index = active.historyUpIndex(_state.value.historyIndex)
        _state.update { it.copy(historyIndex = index) }
        return if (index >= 0) active.historyAt(index) else null
    }

    /** 下一条历史命令；回到草稿位时返回空串（输入框清空）。 */
    fun historyDown(): String? {
        val active = session ?: return null
        val index = active.historyDownIndex(_state.value.historyIndex)
        _state.update { it.copy(historyIndex = index) }
        return if (index >= 0) active.historyAt(index) else ""
    }

    override fun onCleared() {
        observeJob?.cancel()
        observeJob = null
        session?.close()
        session = null
        super.onCleared()
    }

    private fun isClearCommand(text: String): Boolean =
        text == "clear" || text == "cls" || text == "clear -x" || text == "cls -x"

    private fun isSuCommand(text: String): Boolean =
        text == "su" || text == "su -" || text == "sudo su" || text == "su root"

    /** 真的去探测一次 su（会弹授权框），可用才切换会话。 */
    private fun requestRoot(active: TerminalSession) {
        if (active.type.value == TerminalSessionType.ROOT) {
            active.printNotice("[已经在 root 会话]")
            return
        }
        viewModelScope.launch {
            val rootState = withContext(Dispatchers.IO) { RootManager.detect() }
            when (rootState) {
                RootState.Available -> active.switchTo(TerminalSessionType.ROOT)
                is RootState.Denied -> active.printNotice("[root 授权被拒绝：${rootState.reason}]")
                RootState.Unavailable -> active.printNotice("[没有可用的 su]")
            }
        }
    }

    private fun openSession(type: TerminalSessionType) {
        observeJob?.cancel()
        session?.close()
        closing = false

        val created = sessionFactory(type)
        session = created
        _state.value = TerminalUiState(
            sessionType = type,
            running = created.status.value == TerminalStatus.RUNNING,
            cwd = created.cwd.value,
        )

        observeJob = viewModelScope.launch {
            launch {
                created.lines.collect { lines -> _state.update { it.copy(lines = lines) } }
            }
            launch {
                created.type.collect { current -> _state.update { it.copy(sessionType = current) } }
            }
            launch {
                created.cwd.collect { path -> _state.update { it.copy(cwd = path) } }
            }
            launch {
                created.error.collect { message -> _state.update { it.copy(error = message) } }
            }
            launch {
                created.status.collect { status ->
                    _state.update { it.copy(running = status == TerminalStatus.RUNNING) }
                    // root 会话里 exit：回普通会话，滚动历史还在
                    if (!closing && status == TerminalStatus.EXITED &&
                        created.type.value == TerminalSessionType.ROOT
                    ) {
                        created.switchTo(TerminalSessionType.SH)
                        created.printNotice("[已退出 root，回到普通会话]")
                    }
                }
            }
        }
    }
}
