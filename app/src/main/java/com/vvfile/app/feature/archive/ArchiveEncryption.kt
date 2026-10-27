package com.vvfile.app.feature.archive

import com.vvfile.app.core.archive.ArchivePasswordException
import com.vvfile.app.core.archive.SevenZipArchive
import com.vvfile.app.core.archive.ZipCentralDirectory
import com.vvfile.app.core.archive.ZipEntryReader
import java.io.File

/**
 * 「这个压缩包要不要密码 / 这个密码对不对」的统一判断。
 *
 * 解压前先问一句，才能决定是弹密码框还是直接开干；密码输完再验一条，
 * 才能在弹窗里当场说「密码不正确」，而不是让用户等整个解压跑完再收一堆失败项。
 */
object ArchiveEncryption {

    /** 包里是否有加密内容。ZIP 看中央目录标志位，7z 需真读一个字节（见 [SevenZipArchive.requiresPassword]）。 */
    fun needsPassword(path: String): Boolean = runCatching {
        when (ArchiveFormats.kindOf(path)) {
            ArchiveKind.ZIP -> ZipCentralDirectory.hasEncryptedEntry(File(path))
            ArchiveKind.SEVEN_Z -> SevenZipArchive.requiresPassword(File(path))
            else -> false
        }
    }.getOrDefault(false)

    /** 密码能不能解开第一个加密条目；包本身没加密时直接返回 true。 */
    fun verify(path: String, password: String): Boolean = runCatching {
        when (ArchiveFormats.kindOf(path)) {
            ArchiveKind.ZIP -> verifyZip(File(path), password)
            ArchiveKind.SEVEN_Z -> verifySevenZip(File(path), password)
            else -> true
        }
    }.getOrDefault(false)

    private fun verifyZip(file: File, password: String): Boolean {
        val entry = ZipCentralDirectory.read(file)
            ?.firstOrNull { it.encrypted && !it.isDirectory }
            ?: return true
        return try {
            ZipEntryReader.open(file, entry, password).use { it.read() }
            true
        } catch (_: ArchivePasswordException) {
            false
        }
    }

    private fun verifySevenZip(file: File, password: String): Boolean {
        val session = try {
            SevenZipArchive.open(file, password)
        } catch (_: ArchivePasswordException) {
            return false
        }
        session.use { opened ->
            val entry = opened.entries.firstOrNull { !it.isDirectory && it.size > 0 } ?: return true
            return try {
                opened.open(entry.name).use { it.read() }
                true
            } catch (_: ArchivePasswordException) {
                false
            }
        }
    }
}
