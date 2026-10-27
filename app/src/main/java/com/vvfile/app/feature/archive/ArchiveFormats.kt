package com.vvfile.app.feature.archive

import com.vvfile.app.core.archive.ArchiveSignature
import java.io.File

/**
 * 压缩包类型。
 *
 * APK 就是个 ZIP（含 `AndroidManifest.xml` 等），所以按压缩包算：
 * 想看包里有什么直接进包内浏览，想安装还是走「打开方式 → 软件包安装程序」。
 */
enum class ArchiveKind { ZIP, TAR, TAR_GZ, SEVEN_Z, RAR }

/** 按扩展名 + 文件头判断压缩包类型；不是压缩包返回 null。 */
object ArchiveFormats {

    /**
     * 支持的类型：
     * - `zip` / `apk` / `jar` / `apks` 走 ZIP 通道
     * - `tar` 走 TAR，`tar.gz` / `tgz` 走 GZIP + TAR
     * - `7z` 走 7-Zip 通道（含 LZMA / LZMA2 与 AES-256 加密）
     * - `rar` 走 RAR 通道（RAR4 / RAR5，含密码）
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
            name.endsWith(".rar") -> ArchiveKind.RAR
            else -> null
        }
    }

    /**
     * 真正的类型判定：扩展名优先，认不出再看文件头。
     *
     * 自解压包（`.rar.exe` / `.zip.exe`）扩展名是 exe，只能靠这一步认出来；
     * 普通 exe、图片、文本都会返回 null，不会被误当成压缩包。
     */
    fun of(file: File): ArchiveKind? {
        kindOf(file.path)?.let { return it }
        return when (ArchiveSignature.of(file)) {
            ArchiveSignature.Container.ZIP -> ArchiveKind.ZIP
            ArchiveSignature.Container.SEVEN_Z -> ArchiveKind.SEVEN_Z
            ArchiveSignature.Container.RAR -> ArchiveKind.RAR
            null -> null
        }
    }

    fun isArchive(path: String): Boolean = kindOf(path) != null

    fun isArchive(file: File): Boolean = of(file) != null

    /** 能「解压到某个目录」的类型（ZIP / 7z / RAR；TAR 系列暂只支持包内查看）。 */
    fun canExtract(file: File): Boolean = when (of(file)) {
        ArchiveKind.ZIP, ArchiveKind.SEVEN_Z, ArchiveKind.RAR -> true
        else -> false
    }

    /**
     * 同 [canExtract]，但给 UI 的「当前选中项」用：只对 `.exe` 真去读文件头。
     *
     * 菜单/工具条会在组合期调用它，不能对每个文件都扫一遍——自解压包只可能是 exe，
     * 其它的按扩展名就能判定。
     */
    fun canExtract(path: String): Boolean {
        kindOf(path)?.let { kind -> return kind == ArchiveKind.ZIP || kind == ArchiveKind.SEVEN_Z || kind == ArchiveKind.RAR }
        if (!path.substringAfterLast('/').endsWith(".exe", ignoreCase = true)) return false
        return canExtract(File(path))
    }
}
