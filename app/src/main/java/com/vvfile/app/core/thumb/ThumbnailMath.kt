package com.vvfile.app.core.thumb

import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * 缩略图的纯计算部分：采样率、目标尺寸、缓存文件名。
 *
 * 单独抽出来是为了能脱离 Android 跑单测——真正的解码要 BitmapFactory / MediaMetadataRetriever，
 * 在 JVM 单测里跑不起来，但这几个公式最容易算错，必须先钉死。
 */
object ThumbnailMath {

    /**
     * 缩略图长边像素。128 在 3x 密度屏上足够铺满列表里 30dp 的格子（约 90px），
     * 留一点余量，等高密度屏或将来放大格子时也不会糊。
     */
    const val TARGET_PX = 128

    /** 单张解码的像素上限；超过就继续降采样，免得一张全景图吃掉几十 MB 堆内存。 */
    const val MAX_DECODE_PIXELS = 2_000_000L

    /**
     * BitmapFactory 的 inSampleSize（只取 2 的幂）：把长边降到目标的 1~2 倍之间。
     *
     * 宽高都要参与判断——只看长边会把 4000x100 的长截图压成 3 像素高，
     * 那样出来的「缩略图」彻底没法看。像素总数超限时也会继续降。
     */
    fun inSampleSize(width: Int, height: Int, target: Int = TARGET_PX): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (true) {
            val w = width / sample
            val h = height / sample
            val stillBig = w > target * 2 && h > target * 2
            val tooManyPixels = w.toLong() * h > MAX_DECODE_PIXELS
            if (!stillBig && !tooManyPixels) return sample
            sample *= 2
        }
    }

    /** 等比缩放到长边不超过 [target]（只缩不放）。 */
    fun scaledSize(width: Int, height: Int, target: Int = TARGET_PX): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || target <= 0) return 1 to 1
        val longest = maxOf(width, height)
        if (longest <= target) return width to height
        val scale = target.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * 缓存文件名：路径 + 大小 + 修改时间。
     *
     * 把大小和修改时间也算进去，同一个路径被覆盖成新文件时 key 会变，
     * 不会拿上次的旧图糊弄人；同时（不像直接用路径）也避免文件名里
     * 出现 `/`、`:` 这类在文件系统上非法的字符。
     */
    fun cacheKey(path: String, size: Long, lastModified: Long): String =
        sha1("$path|$size|$lastModified")

    private fun sha1(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
