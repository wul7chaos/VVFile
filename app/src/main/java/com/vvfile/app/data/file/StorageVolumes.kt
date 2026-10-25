package com.vvfile.app.data.file

/**
 * 容量卡片代表哪个卷。
 *
 * 直接对当前路径 `StatFs` 会踩坑：`/storage` 是 tmpfs（模拟器/真机上常见 4~8 GB），
 * `/`、`/proc`、`/sys` 更是伪文件系统。用户看到的「内部存储 7.4 GB」就是这么来的。
 *
 * 这里只做一件事：命中虚拟挂载点时，把统计目标换成共享存储根
 * （`/storage/emulated/0`），其余路径原样使用 —— `/data`、`/system`、SD 卡
 * 这些真实分区各自显示自己的容量。
 */
object StorageVolumes {

    /** 没有真实容量、或容量与用户预期无关的挂载点。 */
    private val VIRTUAL_MOUNTS = setOf(
        "/",
        "/storage",
        "/storage/emulated",
        "/mnt",
        "/mnt/user",
        "/mnt/runtime",
        "/mnt/installer",
        "/acct",
        "/proc",
        "/sys",
        "/dev",
        "/data_mirror",
    )

    /**
     * @param path 当前浏览目录
     * @param sharedRoot 共享存储根，通常是 `/storage/emulated/0`
     * @return 交给 `StatFs` 的路径
     */
    fun resolve(path: String, sharedRoot: String): String {
        val normalized = normalize(path)
        val usableShared = sharedRoot.isNotBlank() && normalize(sharedRoot) !in VIRTUAL_MOUNTS
        return when {
            normalized.isEmpty() || isVirtual(normalized) -> {
                if (usableShared) normalize(sharedRoot) else normalized
            }

            else -> normalized
        }
    }

    /** 精确命中虚拟挂载点，或落在伪文件系统的子树里（`/proc/…`、`/sys/…`）。 */
    private fun isVirtual(normalized: String): Boolean =
        normalized in VIRTUAL_MOUNTS ||
            VIRTUAL_PREFIXES.any { normalized.startsWith(it) }

    private val VIRTUAL_PREFIXES = listOf("/proc/", "/sys/", "/dev/", "/acct/")

    private fun normalize(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return ""
        val collapsed = trimmed.trimEnd('/')
        return collapsed.ifEmpty { "/" }
    }
}
