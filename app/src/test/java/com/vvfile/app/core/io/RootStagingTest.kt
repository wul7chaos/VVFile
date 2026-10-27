package com.vvfile.app.core.io

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RootStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeChannel(
        var meta: RootMeta? = RootMeta(isDirectory = false, size = 5L, modifiedAt = 1_700_000_000L),
        var content: ByteArray = "hello".toByteArray(Charsets.UTF_8),
        var copyWorks: Boolean = true,
    ) : RootFileChannel {
        var copies = 0

        override suspend fun stat(path: String): RootMeta? = meta

        override suspend fun readPrefix(path: String, limit: Long): ByteArray = content

        override suspend fun writeAll(path: String, bytes: ByteArray) = Unit

        override suspend fun copyTo(path: String, target: File): Boolean {
            copies++
            if (!copyWorks) return false
            target.writeBytes(content)
            return true
        }
    }

    private fun staging(channel: FakeChannel, root: File = temp.newFolder("cache")) =
        RootStaging(channel = channel, cacheRoot = root)

    @Test
    fun `unreadable file is copied into cache once`() = runBlocking {
        val channel = FakeChannel()
        val staging = staging(channel)

        val first = staging.stage("/data/adb/magisk/config")
        assertNotNull(first)
        assertEquals("hello", first!!.readText())
        assertEquals(1, channel.copies)

        // 第二次命中缓存，不再复制
        val second = staging.stage("/data/adb/magisk/config")
        assertEquals(first.absolutePath, second?.absolutePath)
        assertEquals(1, channel.copies)
    }

    @Test
    fun `readable file needs no staging`() = runBlocking {
        val readable = temp.newFile("plain.txt").apply { writeText("x") }
        val channel = FakeChannel()
        val staging = staging(channel)

        assertNull(staging.stage(readable.absolutePath))
        assertEquals(0, channel.copies)
    }

    @Test
    fun `directory is not staged`() = runBlocking {
        val channel = FakeChannel(meta = RootMeta(isDirectory = true, size = 0))
        assertNull(staging(channel).stage("/data/adb"))
    }

    @Test
    fun `missing metadata means nothing to stage`() = runBlocking {
        val channel = FakeChannel(meta = null)
        assertNull(staging(channel).stage("/data/adb/nothing"))
    }

    @Test
    fun `failed copy returns null so callers keep the original path`() = runBlocking {
        val channel = FakeChannel(copyWorks = false)
        assertNull(staging(channel).stage("/data/adb/magisk/config"))
    }

    @Test
    fun `size change invalidates the cached copy`() = runBlocking {
        val channel = FakeChannel()
        val staging = staging(channel)
        val first = staging.stage("/data/adb/config")
        assertNotNull(first)

        channel.meta = RootMeta(isDirectory = false, size = 6L, modifiedAt = 1_700_000_100L)
        channel.content = "hello!".toByteArray(Charsets.UTF_8)
        val second = staging.stage("/data/adb/config")

        assertEquals(2, channel.copies)
        assertEquals("hello!", second!!.readText())
    }
}
