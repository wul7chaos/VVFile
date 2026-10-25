package com.vvfile.app.core.path

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PathUtilsTest {

    // ---------- normalize ----------

    @Test
    fun `normalize keeps already canonical absolute path`() {
        assertEquals("/storage/emulated/0", PathUtils.normalize("/storage/emulated/0"))
    }

    @Test
    fun `normalize collapses duplicate separators and drops trailing slash`() {
        assertEquals("/storage/emulated/0", PathUtils.normalize("/storage//emulated///0/"))
    }

    @Test
    fun `normalize resolves single dot segments`() {
        assertEquals("/a/b", PathUtils.normalize("/a/./b/."))
        assertEquals("a/b", PathUtils.normalize("./a/b"))
    }

    @Test
    fun `normalize resolves parent segments in absolute paths`() {
        assertEquals("/b", PathUtils.normalize("/a/../b"))
        assertEquals("/", PathUtils.normalize("/../.."))
    }

    @Test
    fun `normalize keeps unresolvable parents in relative paths`() {
        assertEquals("../b", PathUtils.normalize("a/../../b"))
        assertEquals(".", PathUtils.normalize("a/.."))
    }

    @Test
    fun `normalize handles empty and root`() {
        assertEquals("", PathUtils.normalize(""))
        assertEquals("/", PathUtils.normalize("/"))
        assertEquals("/", PathUtils.normalize("//"))
        assertEquals(".", PathUtils.normalize("."))
    }

    @Test
    fun `normalize unifies backslash separators`() {
        assertEquals("C:/Users/y", PathUtils.normalize("C:\\Users\\x\\..\\y"))
        assertEquals("C:/", PathUtils.normalize("C:"))
    }

    @Test
    fun `normalize preserves spaces and unicode names`() {
        assertEquals("/sdcard/我的 文件/a.txt", PathUtils.normalize("/sdcard/我的 文件/a.txt"))
    }

    // ---------- parentOf ----------

    @Test
    fun `parentOf returns immediate parent`() {
        assertEquals("/a", PathUtils.parentOf("/a/b"))
        assertEquals("/", PathUtils.parentOf("/a"))
        assertEquals("a", PathUtils.parentOf("a/b"))
    }

    @Test
    fun `parentOf returns null for root and top-level relative`() {
        assertNull(PathUtils.parentOf("/"))
        assertNull(PathUtils.parentOf(""))
        assertNull(PathUtils.parentOf("a"))
        assertNull(PathUtils.parentOf("."))
    }

    @Test
    fun `parentOf handles drive roots`() {
        assertEquals("C:/", PathUtils.parentOf("C:/Users"))
        assertNull(PathUtils.parentOf("C:/"))
    }

    // ---------- join ----------

    @Test
    fun `join appends child name`() {
        assertEquals("/a/b", PathUtils.join("/a", "b"))
        assertEquals("/a/b", PathUtils.join("/a/", "b"))
        assertEquals("/b", PathUtils.join("/", "b"))
    }

    @Test
    fun `join prefers absolute child`() {
        assertEquals("/x", PathUtils.join("/a", "/x"))
        assertEquals("/x", PathUtils.join("/a", "\\x"))
    }

    @Test
    fun `join normalizes and handles dot segments`() {
        assertEquals("/b", PathUtils.join("/a", "../b"))
        assertEquals("b", PathUtils.join("", "b"))
        assertEquals("/a", PathUtils.join("/a", ""))
        assertEquals("/a", PathUtils.join("/a", "."))
    }

    // ---------- fileName ----------

    @Test
    fun `fileName returns last segment`() {
        assertEquals("b.txt", PathUtils.fileName("/a/b.txt"))
        assertEquals("b", PathUtils.fileName("/a/b/"))
        assertEquals("a", PathUtils.fileName("a"))
    }

    @Test
    fun `fileName is empty for root and empty path`() {
        assertEquals("", PathUtils.fileName("/"))
        assertEquals("", PathUtils.fileName(""))
        assertEquals("", PathUtils.fileName("C:/"))
    }

    // ---------- extensionOf ----------

    @Test
    fun `extensionOf returns lowercase suffix`() {
        assertEquals("txt", PathUtils.extensionOf("a.TXT"))
        assertEquals("gz", PathUtils.extensionOf("archive.tar.gz"))
        assertEquals("txt", PathUtils.extensionOf("/a/b.txt"))
    }

    @Test
    fun `extensionOf is empty without extension`() {
        assertEquals("", PathUtils.extensionOf("README"))
        assertEquals("", PathUtils.extensionOf("a."))
        assertEquals("", PathUtils.extensionOf(""))
    }

    @Test
    fun `extensionOf matches FileEntry semantics for dot files`() {
        // FileEntry.extension 用 substringAfterLast('.')，此处保持一致，避免两套规则
        assertEquals("gitignore", PathUtils.extensionOf(".gitignore"))
    }

    // ---------- isHidden ----------

    @Test
    fun `isHidden detects dot files`() {
        assertTrue(PathUtils.isHidden("/a/.nomedia"))
        assertTrue(PathUtils.isHidden(".nomedia"))
        assertTrue(PathUtils.isHidden(".folder"))
    }

    @Test
    fun `isHidden ignores normal names and dot markers`() {
        assertFalse(PathUtils.isHidden("/a/b.txt"))
        assertFalse(PathUtils.isHidden("/a/."))
        assertFalse(PathUtils.isHidden("/"))
        assertFalse(PathUtils.isHidden(""))
        assertFalse(PathUtils.isHidden("."))
        assertFalse(PathUtils.isHidden(".."))
    }

    // ---------- isAncestor ----------

    @Test
    fun `isAncestor detects nested paths`() {
        assertTrue(PathUtils.isAncestor("/a", "/a/b"))
        assertTrue(PathUtils.isAncestor("/a/", "/a/b/c"))
        assertTrue(PathUtils.isAncestor("/", "/a"))
        assertTrue(PathUtils.isAncestor("/a/b", "/a/b/c/./d"))
    }

    @Test
    fun `isAncestor rejects same path sibling and prefix collision`() {
        assertFalse(PathUtils.isAncestor("/a", "/a"))
        assertFalse(PathUtils.isAncestor("/a/b", "/a/c"))
        assertFalse(PathUtils.isAncestor("/a", "/ab"))
        assertFalse(PathUtils.isAncestor("/a/b", "/a"))
        assertFalse(PathUtils.isAncestor("", "/a"))
    }

    // ---------- validateFileName ----------

    @Test
    fun `validateFileName accepts ordinary names`() {
        assertEquals("a.txt", PathUtils.validateFileName("a.txt"))
        assertEquals("我的文件 1.zip", PathUtils.validateFileName("我的文件 1.zip"))
        assertEquals("...", PathUtils.validateFileName("..."))
        assertEquals("  leading space", PathUtils.validateFileName("  leading space"))
    }

    @Test
    fun `validateFileName rejects empty and blank names`() {
        assertThrows(IllegalArgumentException::class.java) { PathUtils.validateFileName("") }
        assertThrows(IllegalArgumentException::class.java) { PathUtils.validateFileName("   ") }
    }

    @Test
    fun `validateFileName rejects dot and dot dot`() {
        assertThrows(IllegalArgumentException::class.java) { PathUtils.validateFileName(".") }
        assertThrows(IllegalArgumentException::class.java) { PathUtils.validateFileName("..") }
    }

    @Test
    fun `validateFileName rejects path separators and reserved characters`() {
        for (name in listOf("a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b")) {
            assertThrows("应拒绝：$name", IllegalArgumentException::class.java) {
                PathUtils.validateFileName(name)
            }
        }
    }

    @Test
    fun `validateFileName rejects control characters`() {
        for (name in listOf("a\u0000b", "a\nb", "a\tb", "a\u001fb", "a\u007fb")) {
            assertThrows("应拒绝：${name.escape()}", IllegalArgumentException::class.java) {
                PathUtils.validateFileName(name)
            }
        }
    }

    @Test
    fun `isValidFileName mirrors validateFileName`() {
        assertTrue(PathUtils.isValidFileName("photo.jpg"))
        assertFalse(PathUtils.isValidFileName(""))
        assertFalse(PathUtils.isValidFileName(".."))
        assertFalse(PathUtils.isValidFileName("a/b"))
        assertFalse(PathUtils.isValidFileName("a\u0001b"))
    }

    @Test
    fun `suggestionScope 把输入拆成目录与前缀`() {
        assertEquals("/storage" to "emu", PathUtils.suggestionScope("/storage/emu"))
        assertEquals("/" to "sd", PathUtils.suggestionScope("/sd"))
        assertEquals("/sdcard" to "", PathUtils.suggestionScope("/sdcard/"))
        assertEquals("/" to "", PathUtils.suggestionScope("/"))
        assertEquals("" to "abc", PathUtils.suggestionScope("abc"))
        assertEquals("" to "", PathUtils.suggestionScope("   "))
        // 反斜杠按分隔符处理，和路径规范化的约定一致
        assertEquals("/storage" to "emu", PathUtils.suggestionScope("/storage\\emu"))
    }

    private fun String.escape(): String = buildString {
        for (char in this@escape) {
            if (char.code < 0x20 || char.code == 0x7F) append("\\u%04x".format(char.code)) else append(char)
        }
    }
}
