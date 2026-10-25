package com.vvfile.app.data.app

import android.graphics.Bitmap

/**
 * 「应用信息」弹窗的数据。
 *
 * 字段参照 MT 管理器的属性页，但不做：签名状态、加固状态、UID（要解析签名块 / 读内核表），
 * 也不做目标系统 / 最低系统（对「能不能装上、能不能跑」没有直接指导意义），
 * 改为展示 APK 里的原生架构与 ARM / x86 运行结论。
 */
data class AppDetails(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val apkSizeBytes: Long,
    /** APK 内 `lib/<abi>/` 的架构列表；空 = 纯 Java/Kotlin。 */
    val nativeAbis: List<String>,
    /** `/data/user/0/<pkg>`。 */
    val dataDir: String?,
    /** `/storage/emulated/0/Android/data/<pkg>`。 */
    val externalDataDir: String?,
    val apkPath: String,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val icon: Bitmap?,
) {
    val versionText: String
        get() = if (versionName.isBlank()) "v$versionCode" else "v$versionName"

    val pureJava: Boolean get() = nativeAbis.isEmpty()

    companion object {
        /** PackageManager 看不到该包时的兜底：只填列表里已有的信息。 */
        fun from(app: AppInfo, nativeAbis: List<String> = emptyList()): AppDetails = AppDetails(
            packageName = app.packageName,
            label = app.label,
            versionName = app.versionName,
            versionCode = app.versionCode,
            apkSizeBytes = app.apkSizeBytes,
            nativeAbis = nativeAbis,
            dataDir = null,
            externalDataDir = null,
            apkPath = app.sourceDir,
            firstInstallTime = 0L,
            lastUpdateTime = app.lastUpdateTime,
            icon = app.icon,
        )
    }
}

/** 判断「这个 APK 能不能在 ARM / x86 设备上跑」。 */
enum class AbiArch(val label: String) {
    ARM("ARM"),
    X86("x86"),
}

/**
 * 该架构能否直接运行：
 * - 纯 Java/Kotlin（没有 `lib/`）：两边都能跑；
 * - 有原生库：只有对应架构的 so 才能直接跑，否则要靠系统/模拟器的转译层。
 */
fun runsNatively(nativeAbis: List<String>, arch: AbiArch): Boolean {
    if (nativeAbis.isEmpty()) return true
    return nativeAbis.any { abi ->
        when (arch) {
            AbiArch.ARM -> abi.startsWith("arm")
            AbiArch.X86 -> abi.startsWith("x86")
        }
    }
}

/** 给 UI 用的一句话结论。 */
fun abiSummary(details: AppDetails, arch: AbiArch): String = when {
    details.pureJava -> "可直接运行（纯 Java/Kotlin）"
    runsNatively(details.nativeAbis, arch) -> "可直接运行"
    else -> "需转译，可能装不上或闪退"
}

/**
 * 从 ZIP 条目名解析原生架构，条目名形如 `lib/arm64-v8a/libfoo.so`。
 *
 * 只认「`lib/<abi>/xxx.so`」三段式的条目，避免把 `lib/README`、`lib/` 这类
 * 非 ABI 目录也算成架构。纯函数，便于单测。
 */
internal fun parseAbisFromEntries(entries: Sequence<String>): List<String> =
    entries.asSequence()
        .filter { it.startsWith("lib/") }
        .map { it.split('/') }
        .filter { parts -> parts.size >= 3 && parts[2].endsWith(".so", ignoreCase = true) }
        .map { parts -> parts[1] }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toList()
