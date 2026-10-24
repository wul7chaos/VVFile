package com.vvfile.app.core.io

import com.vvfile.app.core.result.NotADirectory
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

class NioFileSystemTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fileSystem = NioFileSystem()

    private fun names(entries: List<FileEntry>): Set<String> = entries.map { it.name }.toSet()

    @Test
    fun `list returns empty for empty directory`() = runBlocking {
        val dir = temp.newFolder("empty")
        assertTrue(fileSystem.list(dir.absolutePath, showHidden = true).isEmpty())
    }

    @Test
    fun `list filters dot entries when showHidden is false`() = runBlocking {
        val dir = temp.newFolder("hidden")
        File(dir, "visible.txt").writeText("hi")
        File(dir, ".secret").writeText("x")
        assertTrue(File(dir, ".folder").mkdir())

        val hiddenOff = fileSystem.list(dir.absolutePath, showHidden = false)
        assertEquals(setOf("visible.txt"), names(hiddenOff))

        val hiddenOn = fileSystem.list(dir.absolutePath, showHidden = true)
        assertEquals(setOf("visible.txt", ".secret", ".folder"), names(hiddenOn))
    }

    @Test
    fun `list tolerates redundant separators and dot segments`() = runBlocking {
        val dir = temp.newFolder("weird")
        File(dir, "a.txt").writeText("a")

        val entries = fileSystem.list(dir.absolutePath + "//.", showHidden = true)
        assertEquals(setOf("a.txt"), names(entries))
    }

    @Test
    fun `list throws PathNotFound for missing directory`() {
        val missing = File(temp.root, "not-here").absolutePath
        assertThrows(PathNotFound::class.java) {
            runBlocking { fileSystem.list(missing, showHidden = true) }
        }
    }

    @Test
    fun `list throws NotADirectory for regular file`() {
        val file = temp.newFile("plain.txt")
        assertThrows(NotADirectory::class.java) {
            runBlocking { fileSystem.list(file.absolutePath, showHidden = true) }
        }
    }

    @Test
    fun `stat describes a regular file`() = runBlocking {
        val file = temp.newFile("note.txt")
        file.writeText("hello")

        val entry = fileSystem.stat(file.absolutePath)
        assertNotNull(entry)
        entry!!
        assertEquals("note.txt", entry.name)
        assertEquals(file.absolutePath, entry.path)
        assertFalse(entry.isDirectory)
        assertFalse(entry.isSymlink)
        assertEquals(5L, entry.size)
        assertTrue(entry.lastModified > 0L)
        assertEquals("txt", entry.extension)
    }

    @Test
    fun `stat marks directories with zero size`() = runBlocking {
        val dir = temp.newFolder("sub")
        val entry = fileSystem.stat(dir.absolutePath)

        assertNotNull(entry)
        assertTrue(entry!!.isDirectory)
        assertEquals(0L, entry.size)
    }

    @Test
    fun `stat returns null for missing path`() = runBlocking {
        assertNull(fileSystem.stat(File(temp.root, "missing/deeper").absolutePath))
    }

    @Test
    fun `list entries expose absolute paths usable by stat`() = runBlocking {
        val dir = temp.newFolder("roundtrip")
        val file = File(dir, "child.bin")
        file.writeText("1234")

        val listed = fileSystem.list(dir.absolutePath, showHidden = true).single { it.name == "child.bin" }
        val statted = fileSystem.stat(listed.path)

        assertEquals(listed, statted)
    }

    @Test
    fun `exists reflects file presence`() = runBlocking {
        val file = temp.newFile("exists.txt")
        assertTrue(fileSystem.exists(file.absolutePath))
        assertFalse(fileSystem.exists(File(temp.root, "absent.txt").absolutePath))
        assertFalse(fileSystem.exists(""))
    }
}
