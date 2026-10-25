package com.vvfile.app.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormatsTest {

    @Test
    fun `kindOf 按扩展名分类`() {
        assertEquals(MediaKind.AUDIO, MediaFormats.kindOf("/sdcard/a.mp3"))
        assertEquals(MediaKind.AUDIO, MediaFormats.kindOf("/sdcard/a.FLAC"))
        assertEquals(MediaKind.VIDEO, MediaFormats.kindOf("/sdcard/a.mp4"))
        assertEquals(MediaKind.VIDEO, MediaFormats.kindOf("/sdcard/a.MKV"))
        assertEquals(MediaKind.IMAGE, MediaFormats.kindOf("/sdcard/a.jpeg"))
        assertEquals(MediaKind.IMAGE, MediaFormats.kindOf("/sdcard/a.HEIC"))
        assertNull(MediaFormats.kindOf("/sdcard/a.txt"))
        assertNull(MediaFormats.kindOf("/sdcard/noext"))
        assertNull(MediaFormats.kindOf("/sdcard/folder.with.dot/name"))
    }

    @Test
    fun `extensionOf 处理点文件与结尾点`() {
        assertEquals("", MediaFormats.extensionOf("/sdcard/.nomedia"))
        assertEquals("", MediaFormats.extensionOf("/sdcard/trailing."))
        assertEquals("mp3", MediaFormats.extensionOf("/sdcard/song.MP3"))
    }

    @Test
    fun `playlistOf 只收同目录同类型并按名称排序`() {
        val candidates = listOf(
            "/sdcard/Music/b.mp3",
            "/sdcard/Music/A.mp3",
            "/sdcard/Music/cover.jpg",
            "/sdcard/Music/sub/c.mp3",
            "/sdcard/Music/note.txt",
        )
        val playlist = MediaFormats.playlistOf("/sdcard/Music/b.mp3", candidates)
        assertEquals(
            listOf("/sdcard/Music/A.mp3", "/sdcard/Music/b.mp3"),
            playlist,
        )
    }

    @Test
    fun `playlistOf 目标不在候选里时退化成单曲`() {
        val playlist = MediaFormats.playlistOf(
            "/sdcard/Music/missing.mp3",
            listOf("/sdcard/Music/other.mp3"),
        )
        assertEquals(listOf("/sdcard/Music/missing.mp3"), playlist)
    }

    @Test
    fun `playlistOf 对非媒体文件返回自身`() {
        val playlist = MediaFormats.playlistOf("/sdcard/a.txt", emptyList())
        assertEquals(listOf("/sdcard/a.txt"), playlist)
    }

    @Test
    fun `indexOf 找不到时回到 0`() {
        val playlist = listOf("/a.mp3", "/b.mp3")
        assertEquals(1, MediaFormats.indexOf(playlist, "/b.mp3"))
        assertEquals(0, MediaFormats.indexOf(playlist, "/c.mp3"))
    }

    @Test
    fun `parentOf 正确处理根目录`() {
        assertEquals("/sdcard/Music", MediaFormats.parentOf("/sdcard/Music/a.mp3"))
        assertEquals("/", MediaFormats.parentOf("/a.mp3"))
        assertEquals("", MediaFormats.parentOf("a.mp3"))
    }

    @Test
    fun `formatDuration 输出与播放器一致`() {
        assertEquals("00:00", MediaFormats.formatDuration(0))
        assertEquals("00:01", MediaFormats.formatDuration(1_400))
        assertEquals("01:05", MediaFormats.formatDuration(65_000))
        assertEquals("59:59", MediaFormats.formatDuration(3_599_000))
        assertEquals("1:02:03", MediaFormats.formatDuration(3_723_000))
        assertEquals("00:00", MediaFormats.formatDuration(-5_000))
    }

    @Test
    fun `speedLabel 保留一位小数`() {
        assertEquals("1.0x", MediaFormats.speedLabel(1f))
        assertEquals("1.25x", MediaFormats.speedLabel(1.25f))
        assertEquals("2.0x", MediaFormats.speedLabel(2f))
    }

    @Test
    fun `repeatLabel 覆盖三种模式`() {
        assertEquals("不循环", MediaFormats.repeatLabel(MediaFormats.REPEAT_OFF))
        assertEquals("单曲循环", MediaFormats.repeatLabel(MediaFormats.REPEAT_ONE))
        assertEquals("列表循环", MediaFormats.repeatLabel(MediaFormats.REPEAT_ALL))
    }
}
