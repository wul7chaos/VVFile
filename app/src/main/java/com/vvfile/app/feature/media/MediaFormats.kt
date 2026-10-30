package com.vvfile.app.feature.media

import java.io.File

/** 内置播放器支持的媒体类别。 */
enum class MediaKind { AUDIO, VIDEO, IMAGE }

/**
 * 媒体类型识别、播放列表拼装与时长格式化。
 *
 * 这里只放纯函数：不碰文件系统、不依赖 Context，方便单测。
 * 目录枚举放在 [MediaLibrary]。
 */
object MediaFormats {

    /** 常见音频容器；ExoPlayer 原生支持的放前面，其余仅做识别（点了会提示解码失败）。 */
    val AUDIO: Set<String> = setOf(
        "mp3", "flac", "wav", "aac", "ogg", "oga", "opus", "m4a", "m4b",
        "amr", "mid", "midi", "aiff", "aif", "wma", "ape", "alac", "mka",
    )

    /** 常见视频容器。 */
    val VIDEO: Set<String> = setOf(
        "mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "3g2", "ts",
        "m2ts", "flv", "wmv", "mpg", "mpeg", "rmvb", "rm", "vob", "ogv",
    )

    /**
     * 常见图片格式；heic/avif 需要 Android 9+ 的系统解码器。
     *
     * ico 也在这里：Skia 的 ICO 解码器是系统自带的，内置查看器能直接看，
     * 不该因为扩展名少见就先弹「打开方式」。
     */
    val IMAGE: Set<String> = setOf(
        "jpg", "jpeg", "jfif", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "ico",
    )

    fun extensionOf(path: String): String {
        val name = nameOf(path)
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun kindOf(path: String): MediaKind? = when (extensionOf(path)) {
        in AUDIO -> MediaKind.AUDIO
        in VIDEO -> MediaKind.VIDEO
        in IMAGE -> MediaKind.IMAGE
        else -> null
    }

    fun isMedia(path: String): Boolean = kindOf(path) != null

    /** 同目录同类型的文件按名称排成播放列表；传入的路径一定在里面。 */
    fun playlistOf(path: String, candidates: Collection<String>): List<String> {
        val kind = kindOf(path) ?: return listOf(path)
        val dir = parentOf(path)
        val same = candidates
            .filter { parentOf(it) == dir && kindOf(it) == kind }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { nameOf(it) })
        return if (same.any { it == path }) same else listOf(path)
    }

    fun indexOf(playlist: List<String>, path: String): Int =
        playlist.indexOf(path).takeIf { it >= 0 } ?: 0

    fun nameOf(path: String): String = path.substringAfterLast('/')

    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return when {
            cut > 0 -> trimmed.substring(0, cut)
            cut == 0 -> "/"
            else -> ""
        }
    }

    /** 00:00 / 01:05 / 1:02:03，与 MT 播放器的时间显示一致。 */
    fun formatDuration(milliseconds: Long): String {
        val total = (milliseconds.coerceAtLeast(0L) + 500L) / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** 播放速度按钮上的文案：1.0 -> 1.0x。 */
    fun speedLabel(speed: Float): String {
        val rounded = (speed * 100f).toInt() / 100f
        return if (rounded % 1f == 0f) "${rounded.toInt()}.0x" else "${rounded}x"
    }

    /** 循环模式文案（工具栏长按/菜单提示用）。 */
    fun repeatLabel(repeatMode: Int): String = when (repeatMode) {
        REPEAT_OFF -> "不循环"
        REPEAT_ONE -> "单曲循环"
        else -> "列表循环"
    }

    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2
}

/** 目录枚举：只依赖 java.io.File，失败时退化成单曲播放。 */
object MediaLibrary {

    fun playlist(path: String): List<String> {
        val dir = MediaFormats.parentOf(path)
        if (dir.isEmpty()) return listOf(path)
        val files = runCatching {
            File(dir).listFiles()?.map { it.absolutePath }
        }.getOrNull().orEmpty()
        return MediaFormats.playlistOf(path, files)
    }
}
