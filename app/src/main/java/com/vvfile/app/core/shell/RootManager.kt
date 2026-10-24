package com.vvfile.app.core.shell

import java.io.File

/**
 * root 可用性探测（M0 仅做静态判断，不触发授权弹窗）。
 * M3 会替换为 `su -c id -u` 的真实探测与授权策略。
 */
object RootManager {
    private val candidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
    )

    fun isAvailable(): Boolean = candidates.any { runCatching { File(it).exists() }.getOrDefault(false) }
}
