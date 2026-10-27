package com.vvfile.app.feature.archive

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码记忆：只在内存、按路径、LRU 上限 16 条。
 *
 * 不落盘是有意的——用户不会希望压缩包密码躺在某个文件里。
 */
class ArchivePasswordsTest {

    @After
    fun cleanup() {
        ArchivePasswords.clear()
    }

    @Test
    fun `记住之后能取回`() {
        ArchivePasswords.remember("/sdcard/a.zip", "123456")
        assertEquals("123456", ArchivePasswords.get("/sdcard/a.zip"))
    }

    @Test
    fun `没记过的路径返回 null`() {
        assertNull(ArchivePasswords.get("/sdcard/unknown.zip"))
    }

    @Test
    fun `空密码不记`() {
        ArchivePasswords.remember("/sdcard/a.zip", "")
        assertNull(ArchivePasswords.get("/sdcard/a.zip"))
        assertEquals(0, ArchivePasswords.size())
    }

    @Test
    fun `不同包互不串味`() {
        ArchivePasswords.remember("/sdcard/a.zip", "aaa")
        ArchivePasswords.remember("/sdcard/b.7z", "bbb")
        assertEquals("aaa", ArchivePasswords.get("/sdcard/a.zip"))
        assertEquals("bbb", ArchivePasswords.get("/sdcard/b.7z"))
    }

    @Test
    fun `忘掉之后取不到`() {
        ArchivePasswords.remember("/sdcard/a.zip", "123456")
        ArchivePasswords.forget("/sdcard/a.zip")
        assertNull(ArchivePasswords.get("/sdcard/a.zip"))
    }

    @Test
    fun `超过上限时淘汰最久没用的那条`() {
        repeat(16) { index -> ArchivePasswords.remember("/sdcard/p$index.zip", "pw$index") }
        // 触碰第 0 条，让它变成「最近使用」
        assertEquals("pw0", ArchivePasswords.get("/sdcard/p0.zip"))
        ArchivePasswords.remember("/sdcard/new.zip", "pw-new")

        assertEquals(16, ArchivePasswords.size())
        assertEquals("pw-new", ArchivePasswords.get("/sdcard/new.zip"))
        // 淘汰的是第 1 条（最久没用），不是第 0 条
        assertNull(ArchivePasswords.get("/sdcard/p1.zip"))
        assertEquals("pw0", ArchivePasswords.get("/sdcard/p0.zip"))
    }

    @Test
    fun `清空后一条不剩`() {
        ArchivePasswords.remember("/sdcard/a.zip", "123456")
        ArchivePasswords.clear()
        assertTrue(ArchivePasswords.size() == 0)
    }
}
