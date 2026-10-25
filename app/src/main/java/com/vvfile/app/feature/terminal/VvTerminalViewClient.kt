package com.vvfile.app.feature.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/**
 * [com.termux.view.TerminalView] 的客户端适配。
 *
 * Termux 原版这个接口在 activity 里实现，顺带处理 ExtraKeysView、字体缩放、剪贴板等；
 * 我们只要「粘滞修饰键 + 双指缩放改字号 + 单指点击唤出输入法」这三件事，
 * 其余按原版默认值给。
 *
 * 长按返回 false，交给 TerminalView 自带的选区手柄（拖动选择 + 复制/粘贴菜单）。
 */
internal class VvTerminalViewClient(
    private val ctrlHeld: () -> Boolean,
    private val altHeld: () -> Boolean,
    private val onModifierConsumed: () -> Unit,
    private val onTextSizeStep: (delta: Int) -> Unit,
    private val onSingleTap: () -> Unit,
    private val onEmulatorReady: () -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // ±10% 以内当作抖动忽略，避免手指轻微滑动就改字号。
        if (scale >= 0.90f && scale <= 1.10f) return scale
        onTextSizeStep(if (scale > 1f) 1 else -1)
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) = onSingleTap()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** 部分中文输入法需要 char-based 输入才能正确上屏。 */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    /** false = 交给 TerminalView 自带的选区手柄。 */
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlHeld()

    override fun readAltKey(): Boolean = altHeld()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // 粘滞修饰键：这一笔字符已经带出去了，随后释放。
        onModifierConsumed()
        return false
    }

    override fun onEmulatorSet() {
        onEmulatorReady()
    }

    override fun logError(tag: String, message: String) = Unit

    override fun logWarn(tag: String, message: String) = Unit

    override fun logInfo(tag: String, message: String) = Unit

    override fun logDebug(tag: String, message: String) = Unit

    override fun logVerbose(tag: String, message: String) = Unit

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit

    override fun logStackTrace(tag: String, e: Exception) = Unit
}
