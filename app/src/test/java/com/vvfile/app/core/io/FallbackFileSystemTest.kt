package com.vvfile.app.core.io

import com.vvfile.app.core.result.PathAccessDenied
import com.vvfile.app.data.model.FileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackFileSystemTest {

    private class FakeFileSystem(
        private val entries: List<FileEntry> = emptyList(),
        private val denied: Boolean = false,
    ) : FileSystem {
        override suspend fun list(path: String, showHidden: Boolean): List<FileEntry> {
            if (denied) throw PathAccessDenied(path)
            return entries
        }

        override suspend fun stat(path: String): FileEntry? {
            if (denied) throw PathAccessDenied(path)
            return entries.firstOrNull { it.path == path }
        }

        override fun exists(path: String): Boolean = entries.any { it.path == path }
    }

    private val entry = FileEntry("data", "/data", isDirectory = true, size = 0, lastModified = 0)

    @Test
    fun `普通目录走直接通道`() = runBlocking {
        val fs = FallbackFileSystem(
            direct = FakeFileSystem(listOf(entry)),
            root = FakeFileSystem(listOf(entry.copy(name = "root"))),
            rootAvailable = { true },
        )
        assertEquals(listOf("data"), fs.list("/data", false).map { it.name })
    }

    @Test
    fun `权限拒绝且 root 可用时回退`() = runBlocking {
        val fs = FallbackFileSystem(
            direct = FakeFileSystem(denied = true),
            root = FakeFileSystem(listOf(entry)),
            rootAvailable = { true },
        )
        assertEquals(listOf("data"), fs.list("/data", false).map { it.name })
        assertEquals("data", fs.stat("/data")?.name)
    }

    @Test
    fun `没有 root 时保留原始权限异常`() = runBlocking {
        val fs = FallbackFileSystem(
            direct = FakeFileSystem(denied = true),
            root = FakeFileSystem(listOf(entry)),
            rootAvailable = { false },
        )
        var denied = false
        try {
            fs.list("/data", false)
        } catch (_: PathAccessDenied) {
            denied = true
        }
        assertTrue(denied)
    }

    @Test
    fun `exists 不触发 root 探测`() {
        var probed = false
        val fs = FallbackFileSystem(
            direct = FakeFileSystem(),
            root = FakeFileSystem(listOf(entry)),
            rootAvailable = {
                probed = true
                true
            },
        )
        assertFalse(fs.exists("/data"))
        assertFalse(probed)
    }
}
