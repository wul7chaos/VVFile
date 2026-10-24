package com.vvfile.app.core.shell

/** root 请求策略：自动使用 / 每次会话首次询问 / 完全禁用。 */
enum class RootPolicy { AUTO, ASK, DISABLED }

/**
 * 全局 root 策略闸门。
 *
 * - [AUTO]：检测到 su 即使用，不再打扰用户；
 * - [ASK]：进入系统分区前由 UI 弹一次确认，本进程内记住选择；
 * - [DISABLED]：任何路径都不走 su，普通分区读写不受影响。
 */
object RootAccess {
    @Volatile
    var policy: RootPolicy = RootPolicy.AUTO

    @Volatile
    var confirmed: Boolean = false
        private set

    fun confirm() {
        confirmed = true
    }

    fun resetConfirmation() {
        confirmed = false
    }

    /** 供 Fallback 通道判断是否允许尝试 root。 */
    fun allowed(): Boolean = policy != RootPolicy.DISABLED && RootManager.isAvailable()

    /** ASK 策略下，本次会话是否还需要弹确认。 */
    fun needsConfirmation(): Boolean = policy == RootPolicy.ASK && !confirmed
}
