package com.vvfile.app.data.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageListingParserTest {

    @Test
    fun `解析标准行与系统应用`() {
        val output = """
            package:/data/app/~~9s8f==/com.foo.bar-1/base.apk=com.foo.bar uid:10234
            package:/system/priv-app/Settings/Settings.apk=com.android.settings uid:1000
        """.trimIndent()

        val entries = parsePackageListing(output)

        assertEquals(2, entries.size)
        assertEquals("com.foo.bar", entries[0].packageName)
        assertEquals("/data/app/~~9s8f==/com.foo.bar-1/base.apk", entries[0].apkPath)
        assertEquals("com.android.settings", entries[1].packageName)
        assertEquals("/system/priv-app/Settings/Settings.apk", entries[1].apkPath)
    }

    @Test
    fun `没有 uid 段也能解析`() {
        val entries = parsePackageListing("package:/data/app/com.x-1/base.apk=com.x")

        assertEquals(1, entries.size)
        assertEquals("com.x", entries[0].packageName)
        assertEquals("/data/app/com.x-1/base.apk", entries[0].apkPath)
    }

    @Test
    fun `路径含等号时按最后一个等号切分`() {
        val entries = parsePackageListing("package:/data/app/a=b/base.apk=com.eq uid:1")

        assertEquals(1, entries.size)
        assertEquals("/data/app/a=b/base.apk", entries[0].apkPath)
        assertEquals("com.eq", entries[0].packageName)
    }

    @Test
    fun `重复包名只保留一条`() {
        val output = """
            package:/data/app/com.a-1/base.apk=com.a uid:1
            package:/data/app/com.a-2/base.apk=com.a uid:1
        """.trimIndent()

        val entries = parsePackageListing(output)

        assertEquals(1, entries.size)
        assertEquals("/data/app/com.a-1/base.apk", entries[0].apkPath)
    }

    @Test
    fun `忽略空行与非法行`() {
        val output = """
            
            WARNING: linker: something
            package:no-separator
            package:=com.broken
            package:/data/app/com.ok-1/base.apk=com.ok uid:7
        """.trimIndent()

        val entries = parsePackageListing(output)

        assertEquals(1, entries.size)
        assertEquals("com.ok", entries[0].packageName)
    }

    @Test
    fun `空输出返回空列表`() {
        assertTrue(parsePackageListing("").isEmpty())
        assertTrue(parsePackageListing("   \n  ").isEmpty())
    }

    /** 真机（Android 17 / KernelSU）`su -c pm list packages -f -U --user 0` 的实际输出。 */
    @Test
    fun `解析真机 root 输出样例`() {
        val output = javaClass.classLoader!!
            .getResourceAsStream("pm-list-sample.txt")!!
            .bufferedReader()
            .readText()

        val entries = parsePackageListing(output)

        assertEquals(7, entries.size)
        assertEquals("com.android.providers.media.module", entries[0].packageName)
        assertTrue(entries[0].apkPath.startsWith("/apex/"))
        // 路径里带 "==" 的 split APK 目录不能切错
        assertEquals("com.xiaomi.type", entries[1].packageName)
        assertEquals(
            "/data/app/~~qu53UUJCO9v58Nuuu-_zlA==/com.xiaomi.type-8tXlPIPF5alCISsWm19ORw==/base.apk",
            entries[1].apkPath,
        )
        assertTrue(entries.any { it.packageName == "com.miui.contentextension" })
    }
}
