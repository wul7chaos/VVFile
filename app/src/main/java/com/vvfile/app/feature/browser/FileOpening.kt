package com.vvfile.app.feature.browser

import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.feature.archive.ArchiveFormats
import com.vvfile.app.feature.media.MediaFormats

/**
 * 单击一个文件时该不该直接弹「打开方式」。
 *
 * 规则：**没有已知打开方式**的文件（`.bin`、`.dat`、`.so` 这类）不要再默默交给系统、
 * 也不要不吭声，直接弹 VVFile 的打开方式面板——上半排是自己的五个打开器，
 * 下半排是系统里能处理它的应用，用户点一下就有结果。
 *
 * 有已知方式的照旧一键直达：压缩包进包内浏览、文本进编辑器、音视频进内置播放器。
 */
object FileOpening {

    /** 有默认打开方式（内置或按扩展名直达）返回 true。 */
    fun hasKnownOpener(path: String): Boolean =
        ArchiveFormats.isArchive(path) ||
            MimeTypes.isText(path) ||
            MediaFormats.kindOf(path) != null

    /** 单击就要弹「打开方式」面板。 */
    fun needsChooser(path: String): Boolean = !hasKnownOpener(path)
}
