package com.vvfile.app.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRenameTest {

    private fun targets(vararg names: String) =
        names.map { RenameTarget(it, "/sdcard/temp/$it", false) }

    private fun results(
        names: List<RenameTarget>,
        rule: RenameRule,
        existing: Set<String> = emptySet(),
    ) = BatchRename.preview(names, rule, existing).map { it.result }

    // ---------------------------------------------------------------- 序号规则 --

    @Test
    fun `留空模板等于原名加序号`() {
        val out = results(targets("a.txt", "b.txt", "c.txt"), RenameRule.Sequence(""))
        assertEquals(listOf("a01.txt", "b02.txt", "c03.txt"), out)
    }

    @Test
    fun `井号换成指定位置的序号`() {
        val out = results(targets("a.txt", "b.txt"), RenameRule.Sequence("IMG_#"))
        assertEquals(listOf("IMG_01.txt", "IMG_02.txt"), out)
    }

    @Test
    fun `双井号强制补零两位`() {
        val out = results(targets("a.txt", "b.txt"), RenameRule.Sequence("p##"))
        assertEquals(listOf("p01.txt", "p02.txt"), out)
    }

    @Test
    fun `写了名称但没写井号时自动补序号避免整批同名`() {
        val out = results(targets("a.txt", "b.txt"), RenameRule.Sequence("旅行"))
        assertEquals(listOf("旅行01.txt", "旅行02.txt"), out)
    }

    @Test
    fun `序号位数随条目数变宽`() {
        val names = ('a'..'l').map { RenameTarget("$it.txt", "/t/$it.txt", false) }
        val out = results(names, RenameRule.Sequence(""))
        assertEquals("a01.txt", out.first())
        assertEquals("l12.txt", out.last())
    }

    @Test
    fun `文件夹不按点拆扩展名`() {
        val out = results(
            listOf(RenameTarget("v1.2", "/t/v1.2", true)),
            RenameRule.Sequence("dir_#"),
        )
        assertEquals(listOf("dir_01"), out)
    }

    @Test
    fun `模板前后空白会被裁掉`() {
        val out = results(targets("a.txt"), RenameRule.Sequence("  笔记  "))
        assertEquals(listOf("笔记01.txt"), out)
    }

    // -------------------------------------------------------------- 查找替换 --

    @Test
    fun `查找替换只作用于主名不动扩展名`() {
        val out = results(
            targets("IMG_001.txt", "IMG_002.txt"),
            RenameRule.Replace("IMG_", "旅行"),
        )
        assertEquals(listOf("旅行001.txt", "旅行002.txt"), out)
    }

    @Test
    fun `扩展名里的相同文字不会被替换`() {
        val out = results(targets("note.log"), RenameRule.Replace("log", "md"))
        assertEquals(listOf("note.log"), out)
    }

    @Test
    fun `替换为空表示删除`() {
        val out = results(targets("a-1.txt"), RenameRule.Replace("-1", ""))
        assertEquals(listOf("a.txt"), out)
    }

    @Test
    fun `查找内容为空时名字不变`() {
        val items = BatchRename.preview(targets("a.txt"), RenameRule.Replace("", "x"), emptySet())
        assertEquals("a.txt", items[0].result)
        assertEquals(RenameIssue.UNCHANGED, items[0].issue)
    }

    // ------------------------------------------------------------------ 校验 --

    @Test
    fun `没改动的条目不算错误也不阻断应用`() {
        val items = BatchRename.preview(targets("a.txt"), RenameRule.Replace("zzz", "y"), emptySet())
        assertEquals(RenameIssue.UNCHANGED, items[0].issue)
        assertEquals("a.txt", items[0].result)
        assertTrue(BatchRename.isApplicable(items))
    }

    @Test
    fun `目录里已有同名时标 EXISTS 且不可应用`() {
        val items = BatchRename.preview(
            targets("a.txt"),
            RenameRule.Replace("a", "b"),
            setOf("a.txt", "b.txt"),
        )
        assertEquals(RenameIssue.EXISTS, items[0].issue)
        assertFalse(BatchRename.isApplicable(items))
    }

    @Test
    fun `非法字符会被拦下`() {
        val items = BatchRename.preview(targets("a.txt"), RenameRule.Sequence("a/b"), emptySet())
        assertEquals(RenameIssue.INVALID, items[0].issue)
        assertFalse(BatchRename.isApplicable(items))
    }

    @Test
    fun `超长名称被判定为非法`() {
        val items = BatchRename.preview(
            targets("a.txt"),
            RenameRule.Sequence("长".repeat(200)),
            emptySet(),
        )
        assertEquals(RenameIssue.INVALID, items[0].issue)
    }

    @Test
    fun `正常的整批改名可以应用`() {
        val items = BatchRename.preview(targets("a.txt", "b.txt"), RenameRule.Sequence("x#"), emptySet())
        assertTrue(items.all { it.issue == null })
        assertTrue(BatchRename.isApplicable(items))
    }

    @Test
    fun `空列表不可应用`() {
        assertFalse(BatchRename.isApplicable(emptyList()))
    }

    @Test
    fun `携带阻断问题的预览不可应用`() {
        val items = listOf(
            RenamePreview("a.txt", "a.txt", RenameIssue.UNCHANGED),
            RenamePreview("b.txt", "", RenameIssue.EMPTY),
        )
        assertFalse(BatchRename.isApplicable(items))
    }

    @Test
    fun `重名只需一条就阻断`() {
        val items = listOf(
            RenamePreview("a.txt", "x.txt", null),
            RenamePreview("b.txt", "x.txt", RenameIssue.DUPLICATE),
        )
        assertFalse(BatchRename.isApplicable(items))
    }

    // ------------------------------------------------------------- 名称拆分 --

    @Test
    fun `隐藏文件保留点开头的完整名称`() {
        val (stem, extension) = BatchRename.splitName(".nomedia", false)
        assertEquals(".nomedia", stem)
        assertEquals("", extension)
    }

    @Test
    fun `多点文件只把最后一段当扩展名`() {
        val (stem, extension) = BatchRename.splitName("a.tar.gz", false)
        assertEquals("a.tar", stem)
        assertEquals(".gz", extension)
    }

    @Test
    fun `无扩展名文件整名作为主名`() {
        val (stem, extension) = BatchRename.splitName("README", false)
        assertEquals("README", stem)
        assertEquals("", extension)
    }

    @Test
    fun `自动位数至少两位`() {
        assertEquals(2, BatchRename.autoWidth(1))
        assertEquals(2, BatchRename.autoWidth(99))
        assertEquals(3, BatchRename.autoWidth(100))
    }

    @Test
    fun `正常条目没有问题标记`() {
        val items = BatchRename.preview(targets("a.txt"), RenameRule.Sequence(""), emptySet())
        assertNull(items[0].issue)
    }
}
