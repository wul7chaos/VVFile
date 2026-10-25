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

    private const val STORAGE_EMULATED = "/storage/emulated"
    private const val STORAGE_SELF = "/storage/self"
    private const val PRIMARY_USER = "0"
}
