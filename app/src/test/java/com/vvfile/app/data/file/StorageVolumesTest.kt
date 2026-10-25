package com.vvfile.app.data.file

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageVolumesTest {

    private val shared = "/storage/emulated/0"

    @Test
    fun `tmpfs 挂载点回退到共享存储`() {
        assertEquals(shared, StorageVolumes.resolve("/storage", shared))
        assertEquals(shared, StorageVolumes.resolve("/storage/emulated", shared))
    }

    @Test
    fun `根目录与伪文件系统回退到共享存储`() {
        assertEquals(shared, StorageVolumes.resolve("/", shared))
        assertEquals(shared, StorageVolumes.resolve("/proc", shared))
        assertEquals(shared, StorageVolumes.resolve("/sys/fs/selinux", shared))
    }

    @Test
    fun `真实目录保持原样`() {
        assertEquals("/storage/emulated/0/Download", StorageVolumes.resolve("/storage/emulated/0/Download", shared))
        assertEquals("/data", StorageVolumes.resolve("/data", shared))
        assertEquals("/system", StorageVolumes.resolve("/system", shared))
    }

    @Test
    fun `尾部斜杠与空串会被规范化`() {
        assertEquals("/storage/emulated/0/Download", StorageVolumes.resolve("/storage/emulated/0/Download/", shared))
        // 空路径按「未知卷」处理，回退共享存储
        assertEquals(shared, StorageVolumes.resolve("", shared))
    }

    @Test
    fun `共享存储根不可用时保持原路径`() {
        assertEquals("/storage", StorageVolumes.resolve("/storage", ""))
    }
}
