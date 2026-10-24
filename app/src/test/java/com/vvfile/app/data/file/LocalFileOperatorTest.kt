package com.vvfile.app.data.file

import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalFileOperatorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val operator = LocalFileOperator()

    private fun collect(result: kotlinx.coroutines.flow.Flow<OperationEvent>): OperationResult {
        val events = runBlocking { result.toList() }
        return (events.last() as OperationEvent.Finished).result
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `copy file into target keeps content`() {
        val source = temp.newFile("a.txt").apply { writeText("hello") }
        val target = temp.newFolder("dst")

        val result = collect(operator.copy(listOf(source.absolutePath), target.absolutePath))

        assertTrue(result.isSuccess)
        assertEquals("hello", File(target, "a.txt").readText())
        assertTrue(source.exists())
    }

    @Test
    fun `copy conflict renames target`() {
        val source = temp.newFile("a.txt").apply { writeText("new") }
        val target = temp.newFolder("dst")
        write(File(target, "a.txt"), "old")

        val result = collect(
            operator.copy(listOf(source.absolutePath), target.absolutePath, ConflictPolicy.RENAME),
        )

        assertTrue(result.isSuccess)
        assertEquals("old", File(target, "a.txt").readText())
        assertEquals("new", File(target, "a (1).txt").readText())
    }

    @Test
    fun `copy conflict skips source`() {
        val source = temp.newFile("a.txt").apply { writeText("new") }
        val target = temp.newFolder("dst")
        write(File(target, "a.txt"), "old")

        val result = collect(
            operator.copy(listOf(source.absolutePath), target.absolutePath, ConflictPolicy.SKIP),
        )

        assertEquals(listOf(source.absolutePath), result.skipped)
        assertEquals("old", File(target, "a.txt").readText())
    }

    @Test
    fun `move removes source`() {
        val source = temp.newFile("a.txt").apply { writeText("data") }
        val target = temp.newFolder("dst")

        val result = collect(operator.move(listOf(source.absolutePath), target.absolutePath))

        assertTrue(result.isSuccess)
        assertFalse(source.exists())
        assertEquals("data", File(target, "a.txt").readText())
    }

    @Test
    fun `copy directory copies nested files`() {
        val source = temp.newFolder("src")
        write(File(source, "sub/inner.txt"), "inner")
        val target = temp.newFolder("dst")

        val result = collect(operator.copy(listOf(source.absolutePath), target.absolutePath))

        assertTrue(result.isSuccess)
        assertEquals("inner", File(target, "src/sub/inner.txt").readText())
    }

    @Test
    fun `delete removes directory recursively`() {
        val dir = temp.newFolder("gone")
        write(File(dir, "a/b.txt"), "x")

        val result = collect(operator.delete(listOf(dir.absolutePath)))

        assertTrue(result.isSuccess)
        assertFalse(dir.exists())
    }

    @Test
    fun `rename rejects existing name`() {
        val source = temp.newFile("a.txt").apply { writeText("a") }
        write(File(temp.root, "b.txt"), "b")

        val result = runBlocking { operator.rename(source.absolutePath, "b.txt") }

        assertFalse(result.isSuccess)
        assertTrue(source.exists())
    }

    @Test
    fun `create directory and file`() {
        val parent = temp.newFolder("p")
        assertEquals(1, runBlocking { operator.createDirectory(parent.absolutePath, "docs") }.succeeded.size)
        assertEquals(1, runBlocking { operator.createFile(parent.absolutePath, "note.txt") }.succeeded.size)
        assertTrue(File(parent, "docs").isDirectory)
        assertTrue(File(parent, "note.txt").isFile)
    }

    @Test
    fun `zip then unzip round trip`() {
        val dir = temp.newFolder("files")
        write(File(dir, "a.txt"), "alpha")
        write(File(dir, "sub/b.txt"), "beta")
        val zip = File(temp.root, "out.zip")

        val zipResult = collect(operator.zip(listOf(dir.absolutePath), zip.absolutePath))
        assertTrue(zipResult.isSuccess)
        assertTrue(zip.exists())

        val out = temp.newFolder("unzipped")
        val unzipResult = collect(operator.unzip(zip.absolutePath, out.absolutePath))
        assertTrue(unzipResult.isSuccess)
        assertEquals("alpha", File(out, "files/a.txt").readText())
        assertEquals("beta", File(out, "files/sub/b.txt").readText())
    }

    @Test
    fun `unzip rejects zip slip entry`() {
        val zip = File(temp.root, "evil.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("bad".toByteArray())
            zos.closeEntry()
        }
        val out = temp.newFolder("safe")

        val result = collect(operator.unzip(zip.absolutePath, out.absolutePath))

        assertFalse(result.failures.isEmpty())
        assertFalse(File(temp.root, "evil.txt").exists())
    }

    @Test
    fun `find conflicts lists existing targets`() {
        val target = temp.newFolder("dst")
        write(File(target, "a.txt"), "old")
        val source = temp.newFile("a.txt").apply { writeText("new") }

        val conflicts = runBlocking {
            operator.findConflicts(listOf(source.absolutePath), target.absolutePath)
        }

        assertEquals(1, conflicts.size)
        assertEquals(source.absolutePath, conflicts.first().sourcePath)
    }
}
