package com.vvfile.app.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 终端页面的会话管理。
 *
 * 页面只负责渲染与输入；进程、ANSI 解析、环形缓冲与历史都在 [TerminalSession]。
 * 切换会话会关闭旧进程并新建一个（sh ↔ root）。
 *
 * 产品红线：root 会话只通过 `su` 启动，不接入 Shizuku、ADB shell、无障碍。
 */
class TerminalViewModel(
    private val sessionFactory: (TerminalSessionType) -> TerminalSession = { TerminalSession(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var session: TerminalSession? = null
    private var observeJob: Job? = null

    init {
        openSession(TerminalSessionType.SH)
    }

    /** 发送命令：自动补换行，并重置历史浏览位置。 */
    fun send(command: String) {
        val text = command.trim()
        if (text.isEmpty()) return
        val active = session ?: return
        active.write(text)
        _state.update { it.copy(historyIndex = -1) }
    }

    /** 清空当前会话输出。 */
    fun clear() {
        session?.clear()
        _state.update { it.copy(lines = emptyList()) }
    }

    /** 切换 sh / root 会话；同类型且仍在运行时忽略。 */
    fun switchSession(type: TerminalSessionType) {
        val current = session
        if (current?.type == type && current.status.value == TerminalStatus.RUNNING) return
        openSession(type)
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

    private fun openSession(type: TerminalSessionType) {
        observeJob?.cancel()
        session?.close()

        val created = sessionFactory(type)
        session = created
        _state.value = TerminalUiState(
            sessionType = type,
            running = created.status.value == TerminalStatus.RUNNING,
        )

        created.printNotice(noticeFor(type))
        created.printNotice("仅经 su 提权，不接入 Shizuku/ADB/无障碍；输入命令后回车执行。")

        observeJob = viewModelScope.launch {
            launch {
                created.lines.collect { lines ->
                    _state.update { it.copy(lines = lines) }
                }
            }
            launch {
                created.status.collect { status ->
                    _state.update { it.copy(running = status == TerminalStatus.RUNNING) }
                }
            }
            launch {
                created.error.collect { message ->
                    _state.update { it.copy(error = message) }
                }
            }
        }
    }

    private fun noticeFor(type: TerminalSessionType): String = when (type) {
        TerminalSessionType.SH -> "VVFile 终端 · sh 会话（应用自身权限）"
        TerminalSessionType.ROOT -> "VVFile 终端 · root 会话（仅经 su）"
    }
}
