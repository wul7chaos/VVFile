package com.vvfile.app.data.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFilterTest {

    private fun app(
        pkg: String,
        label: String,
        system: Boolean = false,
        size: Long = 1024L,
        firstInstall: Long = 0L,
        lastUpdate: Long = 0L,
    ) = AppInfo(
        packageName = pkg,
        label = label,
        versionName = "1.0",
        versionCode = 1L,
        isSystem = system,
        apkSizeBytes = size,
        lastUpdateTime = lastUpdate,
        icon = null,
        sourceDir = "/data/app/$pkg/base.apk",
        firstInstallTime = firstInstall,
    )

    private val apps = listOf(
        app("com.android.settings", "设置", system = true),
        app("bin.mt.plus", "MT管理器"),
        app("com.vvfile.app.debug", "VVFile"),
    )

    @Test
    fun `用户页签不含系统应用`() {
        val result = filterApps(apps, query = "", tab = AppTab.USER)
        assertEquals(listOf("bin.mt.plus", "com.vvfile.app.debug"), result.map { it.packageName })
    }

    @Test
    fun `系统页签只含系统应用`() {
        val result = filterApps(apps, "", tab = AppTab.SYSTEM)
        assertEquals(listOf("com.android.settings"), result.map { it.packageName })
    }

    @Test
    fun `按名称忽略大小写搜索`() {
        val result = filterApps(apps, "vvfile", tab = AppTab.USER)
        assertEquals(listOf("com.vvfile.app.debug"), result.map { it.packageName })
    }

    @Test
    fun `按包名搜索`() {
        val result = filterApps(apps, "mt.plus", tab = AppTab.USER)
        assertEquals(listOf("bin.mt.plus"), result.map { it.packageName })
    }

    @Test
    fun `空白查询与首尾空格归一`() {
        assertEquals(2, filterApps(apps, "   ", AppTab.USER).size)
        assertEquals(1, filterApps(apps, "  MT  ", AppTab.USER).size)
    }

    @Test
    fun `系统应用只在系统页签里搜到`() {
        assertEquals(0, filterApps(apps, "设置", AppTab.USER).size)
        assertEquals(1, filterApps(apps, "设置", AppTab.SYSTEM).size)
    }

    @Test
    fun `按名称排序忽略大小写`() {
        val list = listOf(
            app("a", "zoom"),
            app("b", "Alpha"),
            app("c", "beta"),
        )
        val sorted = sortApps(list, AppSortField.NAME, descending = false)
        assertEquals(listOf("Alpha", "beta", "zoom"), sorted.map { it.label })
    }

    @Test
    fun `按大小与时间排序可逆向`() {
        val list = listOf(
            app("a", "A", size = 300L, firstInstall = 30L, lastUpdate = 3L),
            app("b", "B", size = 100L, firstInstall = 10L, lastUpdate = 1L),
            app("c", "C", size = 200L, firstInstall = 20L, lastUpdate = 2L),
        )
        assertEquals(
            listOf("B", "C", "A"),
            sortApps(list, AppSortField.SIZE, descending = false).map { it.label },
        )
        assertEquals(
            listOf("A", "C", "B"),
            sortApps(list, AppSortField.FIRST_INSTALL, descending = true).map { it.label },
        )
        assertEquals(
            listOf("A", "C", "B"),
            sortApps(list, AppSortField.LAST_UPDATE, descending = true).map { it.label },
        )
    }

    @Test
    fun `按包名排序`() {
        val list = listOf(app("z.pkg", "Z"), app("a.pkg", "A"), app("m.pkg", "M"))
        assertEquals(
            listOf("a.pkg", "m.pkg", "z.pkg"),
            sortApps(list, AppSortField.PACKAGE, descending = false).map { it.packageName },
        )
    }

    @Test
    fun `visibleAppsOf 先过滤再排序`() {
        val list = apps + app("com.extra.app", "aaa")
        val result = visibleAppsOf(list, "", AppTab.USER, AppSortField.NAME, descending = false)
        assertEquals(listOf("com.extra.app", "bin.mt.plus", "com.vvfile.app.debug"), result.map { it.packageName })
    }
}
