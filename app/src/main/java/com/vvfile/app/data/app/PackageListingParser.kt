package com.vvfile.app.data.app

/** `pm list packages -f -U` 的单行结果：包名 + APK 路径。 */
internal data class PackageEntry(
    val packageName: String,
    val apkPath: String,
)

/**
 * 解析 `pm list packages -f -U --user 0` 的输出。
 *
 * 常见行格式（Android 8+）：
 * ```
 * package:/data/app/~~9s8f...==/com.foo.bar-1/base.apk=com.foo.bar uid:10234
 * package:/system/priv-app/Settings/Settings.apk=com.android.settings uid:1000
 * ```
 * 老版本没有 `uid:` 段，`-f` 也可能缺失（路径为空），两种都要兼容。
 *
 * @return 按包名去重后的条目；无法识别的行直接跳过。
 */
internal fun parsePackageListing(output: String): List<PackageEntry> {
    if (output.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    val result = ArrayList<PackageEntry>()
    output.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (!line.startsWith(PACKAGE_PREFIX)) return@forEach
        val body = line.removePrefix(PACKAGE_PREFIX)
        // 包名里不会出现 '='，用最后一个 '=' 切分，路径里即使有 '=' 也不受影响。
        val separator = body.lastIndexOf('=')
        if (separator < 0) return@forEach
        val apkPath = body.substring(0, separator)
        val rest = body.substring(separator + 1)
        // 包名后面可能跟 " uid:10123" 之类的附加字段。
        val packageName = rest.substringBefore(' ').trim()
        // `-f` 一定带路径；两者任一为空说明这行不是有效的包记录。
        if (packageName.isEmpty() || apkPath.isBlank()) return@forEach
        if (!seen.add(packageName)) return@forEach
        result += PackageEntry(packageName = packageName, apkPath = apkPath)
    }
    return result
}

private const val PACKAGE_PREFIX = "package:"
