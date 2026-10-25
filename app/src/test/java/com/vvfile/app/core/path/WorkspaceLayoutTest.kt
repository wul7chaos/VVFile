package com.vvfile.app.core.path

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceLayoutTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `默认布局在共享存储根目录下`() {
        val layout = WorkspaceLayout(File("/storage/emulated/0/VVFile"))

        assertEquals("/storage/emulated/0/VVFile/apk", layout.apkDir.path.replace('\\', '/'))
        assertEquals("/storage/emulated/0/VVFile/temp", layout.tempDir.path.replace('\\', '/'))
    }

    @Test
    fun `ensure 创建缺失目录`() {
        val layout = WorkspaceLayout(File(temporaryFolder.root, WorkspaceLayout.ROOT_NAME))

        val temp = layout.ensure(layout.tempDir)
        val apk = layout.ensure(layout.apkDir)

        assertNotNull(temp)
        assertNotNull(apk)
        assertTrue(temp!!.isDirectory)
        assertTrue(apk!!.isDirectory)
        // 已存在时再调用保持幂等
        assertEquals(temp, layout.ensure(layout.tempDir))
    }

    @Test
    fun `父路径被普通文件占用时 ensure 返回 null`() {
        val blocker = File(temporaryFolder.root, "blocked")
        assertTrue(blocker.createNewFile())
        val layout = WorkspaceLayout(File(blocker, WorkspaceLayout.ROOT_NAME))

        assertNull(layout.ensure(layout.tempDir))
        assertNull(layout.ensure(layout.apkDir))
    }
}
