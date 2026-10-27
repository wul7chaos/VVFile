package com.vvfile.app.feature.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOpeningTest {

    @Test
    fun `压缩包有已知打开方式`() {
        assertFalse(FileOpening.needsChooser("/sdcard/a.zip"))
        assertFalse(FileOpening.needsChooser("/sdcard/base.apk"))
        assertFalse(FileOpening.needsChooser("/sdcard/a.tar"))
        assertFalse(FileOpening.needsChooser("/sdcard/a.tar.gz"))
    }

    @Test
    fun `文本与媒体有已知打开方式`() {
        assertFalse(FileOpening.needsChooser("/sdcard/note.txt"))
        assertFalse(FileOpening.needsChooser("/sdcard/Demo.kt"))
        assertFalse(FileOpening.needsChooser("/sdcard/config.json"))
        assertFalse(FileOpening.needsChooser("/sdcard/pic.png"))
        assertFalse(FileOpening.needsChooser("/sdcard/movie.mp4"))
        assertFalse(FileOpening.needsChooser("/sdcard/song.mp3"))
    }

    @Test
    fun `未知类型要弹打开方式面板`() {
        assertTrue(FileOpening.needsChooser("/sdcard/lib.so"))
        assertTrue(FileOpening.needsChooser("/sdcard/data.bin"))
        assertTrue(FileOpening.needsChooser("/sdcard/无扩展名"))
    }
}
