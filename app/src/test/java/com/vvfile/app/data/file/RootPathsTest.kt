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

    @Test
    fun `storage emulated 父目录与多用户目录需要 root`() {
        assertTrue(RootPaths.isRootOnly("/storage/emulated"))
        assertTrue(RootPaths.isRootOnly("/storage/self"))
        assertTrue(RootPaths.isRootOnly("/storage/self/primary"))
        assertTrue(RootPaths.isRootOnly("/storage/emulated/999"))
        assertTrue(RootPaths.isRootOnly("/storage/emulated/999/Android/data/foo"))
    }

    @Test
    fun `主用户存储及其子目录不需要 root`() {
        assertFalse(RootPaths.isRootOnly("/storage/emulated/0"))
        assertFalse(RootPaths.isRootOnly("/storage/emulated/0/Download"))
    }

    @Test
    fun `Android data 与 obb 需要 root`() {
        // Android 11 起 FUSE 对应用一律返 ENOENT：能列出来也改不了，只能走 root
        assertTrue(RootPaths.isRootOnly("/storage/emulated/0/Android/data"))
        assertTrue(
            RootPaths.isRootOnly(
                "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/a.apk",
            ),
        )
        assertTrue(RootPaths.isRootOnly("/storage/emulated/0/Android/obb/com.example"))
        assertTrue(RootPaths.isRootOnly("/sdcard/Android/data/com.example/cache"))
        // Android/media 是应用自己的可写目录，别误判
        assertFalse(
            RootPaths.isRootOnly("/storage/emulated/0/Android/media/com.vvfile.app"),
        )
        // 名字里带 Android 的普通目录也不该算进来
        assertFalse(RootPaths.isRootOnly("/storage/emulated/0/Download/Android/notes"))
    }
}
