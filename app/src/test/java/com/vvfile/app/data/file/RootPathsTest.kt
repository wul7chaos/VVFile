package com.vvfile.app.data.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPathsTest {

    @Test
    fun `系统分区判定为 root-only`() {
        assertTrue(RootPaths.isRootOnly("/system/app"))
        assertTrue(RootPaths.isRootOnly("/data/data/com.example"))
        assertTrue(RootPaths.isRootOnly("/vendor/lib64"))
        assertTrue(RootPaths.isRootOnly("/apex/com.android.runtime"))
    }

    @Test
    fun `内部存储与 data media 不需要 root`() {
        assertFalse(RootPaths.isRootOnly("/sdcard/Download"))
        assertFalse(RootPaths.isRootOnly("/storage/emulated/0"))
        assertFalse(RootPaths.isRootOnly("/data/media/0/Download"))
        assertFalse(RootPaths.isRootOnly("/"))
    }

    @Test
    fun `前缀相似但不同分区不误判`() {
        assertFalse(RootPaths.isRootOnly("/systematic"))
        assertFalse(RootPaths.isRootOnly("/database"))
    }
}
