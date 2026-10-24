package com.vvfile.app.data.file

import com.vvfile.app.core.io.FileSystem
import com.vvfile.app.core.result.PathNotFound
import com.vvfile.app.data.model.FileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val repository = FileRepository()

    @Test
    fun `list keeps m0 public api and reads real directory`() = runBlocking {
        val dir = temp.newFolder("repo")
        File(dir, "a.txt").writeText("a")
        File(dir, "b").mkdir()

        val entries = repository.list(dir.absolutePath, showHidden = false)

        assertEquals(setOf("a.txt", "b"), entries.map { it.name }.toSet())
    }

    @Test
    fun `list honours showHidden flag`() = runBlocking {
        val dir = temp.newFolder("repo-hidden")
        File(dir, "visible").writeText("v")
        File(dir, ".hidden").writeText("h")

        assertFalse(repository.list(dir.absolutePath, showHidden = false).any { it.name == ".hidden" })
        assertTrue(repository.list(dir.absolutePath, showHidden = true).any { it.name == ".hidden" })
    }

    @Test
    fun `stat returns entry for existing path`() = runBlocking {
        val file = temp.newFile("data.bin")
        file.writeText("12345")

        val entry = repository.stat(file.absolutePath)

        assertNotNull(entry)
        assertEquals(5L, entry!!.size)
        assertFalse(entry.isDirectory)
    }

    @Test
    fun `stat returns null for missing path`() = runBlocking {
        assertNull(repository.stat(File(temp.root, "ghost").absolutePath))
    }

    @Test
    fun `exists mirrors file system`() = runBlocking {
        val file = temp.newFile("present.txt")
        assertTrue(repository.exists(file.absolutePath))
        assertFalse(repository.exists(File(temp.root, "absent.txt").absolutePath))
    }

    @Test
    fun `list propagates domain error for missing directory`() {
        val missing = File(temp.root, "gone").absolutePath
        assertThrows(PathNotFound::class.java) {
            runBlocking { repository.list(missing, showHidden = true) }
        }
    }

    @Test
    fun `repository delegates to injected file system`() = runBlocking {
        val fakeEntry = FileEntry(
            name = "fake.txt",
            path = "/fake/fake.txt",
            isDirectory = false,
            size = 42L,
            lastModified = 1L,
        )
        val fake = FakeFileSystem(entries = listOf(fakeEntry), statResult = fakeEntry)
        val injected = FileRepository(fake)

        assertEquals(listOf(fakeEntry), injected.list("/fake", showHidden = true))
        assertEquals("/fake", fake.lastPath)

        assertEquals(fakeEntry, injected.stat("/fake/fake.txt"))
        assertEquals("/fake/fake.txt", fake.lastPath)

        assertTrue(injected.exists("/fake/fake.txt"))
    }

    private class FakeFileSystem(
        private val entries: List<FileEntry> = emptyList(),
        private val statResult: FileEntry? = null,
    ) : FileSystem {

        var lastPath: String? = null

        override suspend fun list(path: String, showHidden: Boolean): List<FileEntry> {
            lastPath = path
            return entries
        }

        override suspend fun stat(path: String): FileEntry? {
            lastPath = path
            return statResult
        }

        override fun exists(path: String): Boolean {
            lastPath = path
            return statResult != null
        }
    }
}
