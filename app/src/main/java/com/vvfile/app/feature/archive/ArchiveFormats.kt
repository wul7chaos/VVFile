package com.vvfile.app.feature.archive

/**
 * 压缩包类型。
 *
 * APK 就是个 ZIP（含 `AndroidManifest.xml` 等），所以按压缩包算：
 * 想看包里有什么直接进包内浏览，想安装还是走「打开方式 → 软件包安装程序」。
 */
enum class ArchiveKind { ZIP, TAR, TAR_GZ, SEVEN_Z }

/** 按扩展名判断压缩包类型；不是压缩包返回 null。 */
object ArchiveFormats {

    /**
     * 支持的类型：
     * - `zip` / `apk` / `jar` / `apks` 走 ZIP 通道
     * - `tar` 走 TAR，`tar.gz` / `tgz` 走 GZIP + TAR
     * - `7z` 走 7-Zip 通道（含 LZMA / LZMA2 与 AES-256 加密）
     */
    fun kindOf(path: String): ArchiveKind? {
        val name = path.substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ArchiveKind.TAR_GZ
            name.endsWith(".tar") -> ArchiveKind.TAR
            name.endsWith(".zip") -> ArchiveKind.ZIP
            name.endsWith(".apk") -> ArchiveKind.ZIP
            name.endsWith(".jar") -> ArchiveKind.ZIP
            name.endsWith(".apks") -> ArchiveKind.ZIP
            name.endsWith(".7z") -> ArchiveKind.SEVEN_Z
            else -> null
        }
    }

    fun isArchive(path: String): Boolean = kindOf(path) != null

    /** 能「解压到某个目录」的类型（目前是 ZIP 与 7z）。 */
    fun canExtract(path: String): Boolean = when (kindOf(path)) {
        ArchiveKind.ZIP, ArchiveKind.SEVEN_Z -> true
        else -> false
    }
}
