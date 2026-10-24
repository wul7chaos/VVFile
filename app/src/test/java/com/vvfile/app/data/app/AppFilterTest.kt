package com.vvfile.app.data.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppFilterTest {

    private fun app(
        pkg: String,
        label: String,
        system: Boolean = false,
    ) = AppInfo(
        packageName = pkg,
        label = label,
        versionName = "1.0",
        versionCode = 1L,
        isSystem = system,
        apkSizeBytes = 1024L,
        lastUpdateTime = 0L,
        icon = null,
        sourceDir = "/data/app/$pkg/base.apk",
    )

    private val apps = listOf(
        app("com.android.settings", "设置", system = true),
        app("bin.mt.plus", "MT管理器"),
        app("com.vvfile.app.debug", "VVFile"),
    )

    @Test
    fun `默认不包含系统应用`() {
        val result = filterApps(apps, query = "", includeSystem = false)
        assertEquals(listOf("bin.mt.plus", "com.vvfile.app.debug"), result.map { it.packageName })
    }

    @Test
    fun `包含系统应用时返回全部`() {
        assertEquals(3, filterApps(apps, "", includeSystem = true).size)
    }

    @Test
    fun `按名称忽略大小写搜索`() {
        val result = filterApps(apps, "vvfile", includeSystem = false)
        assertEquals(listOf("com.vvfile.app.debug"), result.map { it.packageName })
    }

    @Test
    fun `按包名搜索`() {
        val result = filterApps(apps, "mt.plus", includeSystem = false)
        assertEquals(listOf("bin.mt.plus"), result.map { it.packageName })
    }

    @Test
    fun `空白查询与首尾空格归一`() {
        assertEquals(2, filterApps(apps, "   ", includeSystem = false).size)
        assertEquals(1, filterApps(apps, "  MT  ", includeSystem = false).size)
    }

    @Test
    fun `搜索系统应用需要同时开启系统开关`() {
        assertEquals(0, filterApps(apps, "设置", includeSystem = false).size)
        assertEquals(1, filterApps(apps, "设置", includeSystem = true).size)
    }
}
