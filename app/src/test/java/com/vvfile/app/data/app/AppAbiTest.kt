package com.vvfile.app.data.app

import org.junit.Assert.assertTrue
import org.junit.Test

class AppAbiTest {

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

        assertTrue(
            parseAbisFromEntries(entries) == listOf("arm64-v8a", "armeabi-v7a", "x86"),
        )
    }

    @Test
    fun `同一架构多个 so 只保留一条`() {
        val entries = sequenceOf(
            "lib/arm64-v8a/liba.so",
            "lib/arm64-v8a/libb.so",
            "lib/arm64-v8a/libc.so",
        )

        assertTrue(parseAbisFromEntries(entries) == listOf("arm64-v8a"))
    }

    @Test
    fun `纯 Java 应用没有原生架构`() {
        assertTrue(parseAbisFromEntries(sequenceOf("classes.dex", "resources.arsc")).isEmpty())
    }

    @Test
    fun `lib 目录下没有 so 时忽略`() {
        assertTrue(parseAbisFromEntries(sequenceOf("lib/", "lib/README")).isEmpty())
    }

    @Test
    fun `APK 自带 lib 时给出四种架构`() {
        val entries = sequenceOf(
            "lib/arm64-v8a/liba.so",
            "lib/armeabi-v7a/liba.so",
            "lib/x86/liba.so",
            "lib/x86_64/liba.so",
        )

        assertTrue(
            parseAbisFromEntries(entries) ==
                listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
        )
    }
}
