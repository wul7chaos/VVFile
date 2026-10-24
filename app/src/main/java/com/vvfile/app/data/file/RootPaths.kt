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
        return ROOT_PREFIXES.any { prefix ->
            normalized == prefix || normalized.startsWith("$prefix/")
        }
    }
}
