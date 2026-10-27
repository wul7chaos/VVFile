package com.vvfile.app.core.shell

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * root 探测结果。
 *
 * - [Unavailable]：设备上没有可用的 `su`（未 root，或 su 不在执行 PATH 中）；
 * - [Denied]：`su` 能启动，但用户拒绝、授权超时或没有拿到 uid 0；
 * - [Available]：`su -c` 成功执行且返回 uid 0。
 */
sealed class RootState {
    data object Unavailable : RootState()

    data class Denied(val reason: String) : RootState()

    data object Available : RootState()
}

/**
 * root 可用性检测与授权状态缓存。
 *
 * 本类只通过 `su -c` 探测。
 *
 * 探测命令固定为 `su -c id -u`：退出码为 0 且 stdout 为 `0` 才视为可用。
 * 结果缓存在进程内；用户拒绝后不会反复弹窗，需要重试时调用 [invalidate]。
 */
object RootManager {
    private const val DETECT_TIMEOUT_MS = 10_000L
    private const val ROOT_UID_OUTPUT = "0"

    private val candidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
    )

    private val executor = ShellExecutor()
    private val detectMutex = Mutex()

    @Volatile
    private var cachedState: RootState? = null

    /**
     * 同步便捷判断，供 UI 初始化时使用（签名由 M0 冻结，不能修改）。
     *
     * 已有 [detect] 缓存时以缓存为准；否则退化为 su 二进制的静态存在性检查，
     * 真正的授权探测请用 `suspend detect()`。
     */
    fun isAvailable(): Boolean {
        val cached = cachedState
        if (cached != null) return cached is RootState.Available
        return hasSuBinary()
    }

    /**
     * 真实探测 root：执行 `su -c id -u`。
     *
     * 成功结果（可用 / 拒绝 / 无 su）都会被缓存；并发调用只会探测一次。
     * 这里只使用 `su`。
     */
    suspend fun detect(): RootState = detectMutex.withLock {
        cachedState?.let { return it }

        val state = probe()
        cachedState = state
        state
    }

    /** 清除缓存，下次 [detect] / [isAvailable] 重新探测（例如用户去授权后重试）。 */
    fun invalidate() {
        cachedState = null
    }

    private suspend fun probe(): RootState {
        val result = executor.exec(
            command = "id -u",
            asRoot = true,
            timeoutMs = DETECT_TIMEOUT_MS,
        )

        if (!result.started) {
            // su 进程无法启动：Android 上等价于「没有可用的 su」。
            return RootState.Unavailable
        }
        if (result.timedOut) {
            return RootState.Denied("root 授权超时：su 在 ${DETECT_TIMEOUT_MS / 1000}s 内无响应")
        }

        val uid = result.stdout.trim()
        if (result.isSuccess && uid == ROOT_UID_OUTPUT) {
            return RootState.Available
        }

        val detail = result.combinedOutput.trim()
        if (looksLikeMissingSu(detail, result.exitCode)) {
            return RootState.Unavailable
        }
        if (result.isSuccess) {
            return RootState.Denied("su 可执行但 uid=${uid.ifBlank { "未知" }}，未获得 root")
        }
        return RootState.Denied(detail.ifBlank { "su 退出码 ${result.exitCode}" })
    }

    private fun hasSuBinary(): Boolean = candidates.any { path ->
        runCatching { File(path).exists() }.getOrDefault(false)
    }

    private fun looksLikeMissingSu(detail: String, exitCode: Int): Boolean {
        val normalized = detail.lowercase()
        return exitCode == 127 ||
            normalized.contains("not found") ||
            normalized.contains("no such file") ||
            normalized.contains("inaccessible or not found")
    }
}
