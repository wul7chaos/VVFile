package com.vvfile.app.data.file

import com.vvfile.app.core.result.ConflictInfo
import com.vvfile.app.core.result.ConflictPolicy
import com.vvfile.app.core.result.OperationEvent
import com.vvfile.app.core.result.OperationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackFileOperatorTest {

    /** 只记录走了哪条通道；操作本身固定成功。 */
    private class FakeOperator(private val name: String) : FileOperator {
        val calls = mutableListOf<String>()

        private fun flowOf(kind: String): Flow<OperationEvent> = flow {
            calls += "$name:$kind"
            emit(OperationEvent.Finished(OperationResult(succeeded = listOf("ok"))))
        }

        override fun copy(
            sources: List<String>,
            targetDir: String,
            policy: ConflictPolicy,
        ) = flowOf("copy")

        override fun move(
            sources: List<String>,
            targetDir: String,
            policy: ConflictPolicy,
        ) = flowOf("move")

        override fun delete(paths: List<String>) = flowOf("delete")

        override fun zip(sources: List<String>, targetZip: String, level: Int) = flowOf("zip")

        override fun unzip(
            zipPath: String,
            targetDir: String,
            policy: ConflictPolicy,
            password: String?,
        ) = flowOf("unzip")

        override suspend fun rename(path: String, newName: String): OperationResult {
            calls += "$name:rename"
            return OperationResult(succeeded = listOf(path))
        }

        override suspend fun createDirectory(parent: String, name: String) = rename(parent, name)

        override suspend fun createFile(parent: String, name: String) = rename(parent, name)

        override suspend fun chmod(path: String, mode: String) = rename(path, mode)

        override suspend fun findConflicts(
            sources: List<String>,
            targetDir: String,
        ): List<ConflictInfo> = emptyList()

        override suspend fun findZipConflicts(
            zipPath: String,
            targetDir: String,
            password: String?,
        ): List<ConflictInfo> = emptyList()
    }

    private fun operator(rootAvailable: Boolean): Triple<FallbackFileOperator, FakeOperator, FakeOperator> {
        val local = FakeOperator("local")
        val root = FakeOperator("root")
        val fallback = FallbackFileOperator(
            local = local,
            root = root,
            rootAvailable = { rootAvailable },
        )
        return Triple(fallback, local, root)
    }

    @Test
    fun `Android data 里的删除走 root 通道`() = runBlocking {
        val (fallback, local, root) = operator(rootAvailable = true)
        val events = fallback
            .delete(listOf("/storage/emulated/0/Android/data/com.tencent.mobileqq/a.apk"))
            .toList()

        assertEquals(listOf("root:delete"), root.calls)
        assertTrue(local.calls.isEmpty())
        assertTrue(events.isNotEmpty())
    }

    @Test
    fun `没有 root 时给出明确原因而不是走本地报文件不存在`() = runBlocking {
        val (fallback, local, root) = operator(rootAvailable = false)
        val result = (fallback
            .delete(listOf("/storage/emulated/0/Android/data/com.example/cache"))
            .toList()
            .last() as OperationEvent.Finished).result

        assertTrue(local.calls.isEmpty())
        assertTrue(root.calls.isEmpty())
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.first().reason.contains("ROOT"))
    }

    @Test
    fun `普通目录仍走本地通道`() = runBlocking {
        val (fallback, local, root) = operator(rootAvailable = true)
        fallback.delete(listOf("/storage/emulated/0/Download/a.zip")).toList()

        assertEquals(listOf("local:delete"), local.calls)
        assertTrue(root.calls.isEmpty())
    }

    @Test
    fun `跨分区复制按目标或源是否需要 root 决定通道`() = runBlocking {
        val (fallback, local, root) = operator(rootAvailable = true)
        fallback
            .copy(
                sources = listOf("/data/adb/sjzesp/start.sh"),
                targetDir = "/storage/emulated/0/Download",
                policy = ConflictPolicy.RENAME,
            )
            .toList()

        assertEquals(listOf("root:copy"), root.calls)
        assertTrue(local.calls.isEmpty())
    }

    @Test
    fun `重命名同样按需走 root`() = runBlocking {
        val (fallback, local, root) = operator(rootAvailable = true)
        fallback.rename("/storage/emulated/0/Android/data/com.example/a.txt", "b.txt")

        assertEquals(listOf("root:rename"), root.calls)
        assertTrue(local.calls.isEmpty())
    }
}
