package com.vvfile.app.data.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAbiTest {

    private fun details(abis: List<String>) = AppDetails(
        packageName = "com.example",
        label = "Example",
        versionName = "1.0",
        versionCode = 1,
        apkSizeBytes = 1024,
        nativeAbis = abis,
        dataDir = null,
        externalDataDir = null,
        apkPath = "/data/app/com.example-1/base.apk",
        firstInstallTime = 0L,
        lastUpdateTime = 0L,
        icon = null,
    )

    @Test
    fun `从 zip 条目解析架构`() {
        val entries = sequenceOf(
            "AndroidManifest.xml",
            "classes.dex",
            "lib/arm64-v8a/libfoo.so",
            "lib/armeabi-v7a/libfoo.so",
            "lib/x86/libfoo.so",
            "res/drawable/icon.png",
        )

        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "x86"), parseAbisFromEntries(entries))
    }

    @Test
    fun `同一架构多个 so 只保留一条`() {
        val entries = sequenceOf(
            "lib/arm64-v8a/liba.so",
            "lib/arm64-v8a/libb.so",
            "lib/arm64-v8a/libc.so",
        )

        assertEquals(listOf("arm64-v8a"), parseAbisFromEntries(entries))
    }

    @Test
    fun `纯 Java 应用没有原生架构`() {
        assertTrue(parseAbisFromEntries(sequenceOf("classes.dex", "resources.arsc")).isEmpty())
    }

    @Test
    fun `lib 目录下没有子目录时忽略`() {
        assertTrue(parseAbisFromEntries(sequenceOf("lib/", "lib/README")).isEmpty())
    }

    @Test
    fun `纯 Java 在两种架构上都可直接运行`() {
        assertTrue(runsNatively(emptyList(), AbiArch.ARM))
        assertTrue(runsNatively(emptyList(), AbiArch.X86))
    }

    @Test
    fun `只有 ARM 原生库时 x86 需转译`() {
        val abis = listOf("arm64-v8a", "armeabi-v7a")

        assertTrue(runsNatively(abis, AbiArch.ARM))
        assertFalse(runsNatively(abis, AbiArch.X86))
    }

    @Test
    fun `只有 x86 原生库时 ARM 需转译`() {
        val abis = listOf("x86", "x86_64")

        assertTrue(runsNatively(abis, AbiArch.X86))
        assertFalse(runsNatively(abis, AbiArch.ARM))
    }

    @Test
    fun `同时带两种架构时两边都能直接运行`() {
        val abis = listOf("arm64-v8a", "x86_64")

        assertTrue(runsNatively(abis, AbiArch.ARM))
        assertTrue(runsNatively(abis, AbiArch.X86))
    }

    @Test
    fun `结论文案区分三种情况`() {
        assertEquals("可直接运行（纯 Java/Kotlin）", abiSummary(details(emptyList()), AbiArch.X86))
        assertEquals("可直接运行", abiSummary(details(listOf("x86_64")), AbiArch.X86))
        assertEquals(
            "需转译，可能装不上或闪退",
            abiSummary(details(listOf("arm64-v8a")), AbiArch.X86),
        )
    }
}
