package com.vvfile.app.core.thumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图纯计算：采样率、缩放尺寸、缓存键。
 *
 * 这几个公式错了不会崩，只会「图糊了」或者「换了文件还显示旧图」，
 * 很难在真机上肉眼发现，所以在单测里钉死。
 */
class ThumbnailMathTest {

    @Test
    fun `非法尺寸不参与采样`() {
        assertEquals(1, ThumbnailMath.inSampleSize(0, 0))
        assertEquals(1, ThumbnailMath.inSampleSize(-10, 100))
        assertEquals(1, ThumbnailMath.inSampleSize(100, 100, 0))
    }

    @Test
    fun `小图不降采样`() {
        assertEquals(1, ThumbnailMath.inSampleSize(100, 80))
        assertEquals(1, ThumbnailMath.inSampleSize(128, 128))
        // 刚好在「目标的 2 倍」边界上：256 不动，再大就降一半
        assertEquals(1, ThumbnailMath.inSampleSize(256, 256))
        assertEquals(2, ThumbnailMath.inSampleSize(300, 300))
    }

    @Test
    fun `手机照片降到目标的 1 到 2 倍之间`() {
        // 4000x3000：16 -> 250x187，再缩到 128x96
        assertEquals(16, ThumbnailMath.inSampleSize(4000, 3000))
        // 1080x2400 截图：8 -> 135x300
        assertEquals(8, ThumbnailMath.inSampleSize(1080, 2400))
        // 1920x1080 视频帧：8 -> 240x135
        assertEquals(8, ThumbnailMath.inSampleSize(1920, 1080))
    }

    @Test
    fun `长截图只看长边会把宽度压没`() {
        // 4000x100 的长条：如果只按长边算会降到 3 像素高，必须保持原始分辨率
        assertEquals(1, ThumbnailMath.inSampleSize(4000, 100))
        // 1080x20000 的滚动长截图：8 -> 135x2500
        assertEquals(8, ThumbnailMath.inSampleSize(1080, 20000))
    }

    @Test
    fun `像素总数超限时继续降采样`() {
        // 30000x200 只有 100 高，但 600 万像素太贵 -> 降到 15000x100
        assertEquals(2, ThumbnailMath.inSampleSize(30000, 200))
    }

    @Test
    fun `采样率始终是 2 的幂`() {
        val samples = listOf(
            ThumbnailMath.inSampleSize(4000, 3000),
            ThumbnailMath.inSampleSize(1080, 2400),
            ThumbnailMath.inSampleSize(8000, 6000),
            ThumbnailMath.inSampleSize(700, 700),
        )
        for (sample in samples) {
            assertEquals(0, sample and (sample - 1))
        }
    }

    @Test
    fun `缩放尺寸等比且不超过目标`() {
        assertEquals(128 to 96, ThumbnailMath.scaledSize(4000, 3000))
        assertEquals(128 to 16, ThumbnailMath.scaledSize(400, 50))
        // 小图不放大
        assertEquals(100 to 50, ThumbnailMath.scaledSize(100, 50))
        assertEquals(128 to 128, ThumbnailMath.scaledSize(128, 128))
    }

    @Test
    fun `缩放尺寸最小保留 1 像素`() {
        assertEquals(128 to 1, ThumbnailMath.scaledSize(4000, 3))
        assertEquals(1 to 1, ThumbnailMath.scaledSize(0, 0))
    }

    @Test
    fun `缓存键同输入相同`() {
        val a = ThumbnailMath.cacheKey("/sdcard/a.jpg", 1024L, 1_700_000_000_000L)
        val b = ThumbnailMath.cacheKey("/sdcard/a.jpg", 1024L, 1_700_000_000_000L)
        assertEquals(a, b)
    }

    @Test
    fun `缓存键随大小和修改时间变化`() {
        val base = ThumbnailMath.cacheKey("/sdcard/a.jpg", 1024L, 1_700_000_000_000L)
        assertNotEquals(base, ThumbnailMath.cacheKey("/sdcard/a.jpg", 2048L, 1_700_000_000_000L))
        assertNotEquals(base, ThumbnailMath.cacheKey("/sdcard/a.jpg", 1024L, 1_700_000_001_000L))
        assertNotEquals(base, ThumbnailMath.cacheKey("/sdcard/b.jpg", 1024L, 1_700_000_000_000L))
    }

    @Test
    fun `缓存键可直接当文件名`() {
        val key = ThumbnailMath.cacheKey("/sdcard/我的 照片/a.jpg", 1024L, 1L)
        assertEquals(40, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
    }
}
