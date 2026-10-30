package com.vvfile.app.data.file

import com.vvfile.app.core.path.PathUtils

/** 判断路径是否属于应用自身无权写入、需要 root 通道的分区。 */
object RootPaths {

    private val ROOT_PREFIXES = listOf(
        "/data",
        "/system",
        "/vendor",
        "/product",
        "/odm",
        "/sbin",
        "/root",
        "/etc",
        "/proc",
        "/sys",
        "/dev",
        "/apex",
    )

    fun isRootOnly(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = PathUtils.normalize(path)
        if (normalized == "/") return false
        // /data/media 是 /sdcard 的真实路径，应用自身可访问
        if (normalized == "/data/media" || normalized.startsWith("/data/media/")) return false
        // Android 11 起共享存储里的 Android/data 与 Android/obb 对应用一律返回 ENOENT：
        // 有的机型能列出来（只读放行），但改动一律失败，而且报的是「文件不存在」。
        // 这两个目录只能走 root 通道。注意 Android/media 是应用自己的可写目录，不能算进来。
        if (isRestrictedSharedDir(normalized)) return true
        // /storage/emulated 本身、第二用户（999 等）与 /storage/self 在 FUSE 层对应用不可见，
        // 只有 /storage/emulated/0 是应用的共享存储，这类路径要走 root
        if (normalized == STORAGE_EMULATED || normalized == STORAGE_SELF) return true
        if (normalized.startsWith("$STORAGE_EMULATED/")) {
            val user = normalized.removePrefix("$STORAGE_EMULATED/").substringBefore('/')
            if (user != PRIMARY_USER) return true
        }
        if (normalized.startsWith("$STORAGE_SELF/")) return true
        return ROOT_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        }
    }

    /** `<共享存储根>/Android/data` 与 `<共享存储根>/Android/obb`（含 `/sdcard` 等价写法）。 */
    private fun isRestrictedSharedDir(normalized: String): Boolean {
        if (SHARED_STORAGE_PREFIXES.none { normalized.startsWith(it) }) return false
        val segments = normalized.split('/')
        val index = segments.indexOf(ANDROID_DIR)
        if (index < 0 || index + 1 >= segments.size) return false
        val name = segments[index + 1]
        return name == APP_DATA_DIR || name == APP_OBB_DIR
    }

    private val SHARED_STORAGE_PREFIXES = listOf(
        "$STORAGE_EMULATED/",
        "$STORAGE_SELF/",
        "/sdcard/",
    )

    private const val STORAGE_EMULATED = "/storage/emulated"
    private const val STORAGE_SELF = "/storage/self"
    private const val PRIMARY_USER = "0"
    private const val ANDROID_DIR = "Android"
    private const val APP_DATA_DIR = "data"
    private const val APP_OBB_DIR = "obb"
}
