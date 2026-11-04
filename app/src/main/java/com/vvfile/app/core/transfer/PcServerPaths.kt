package com.vvfile.app.core.transfer

import java.io.File

/**
 * 「电脑 → 手机」落盘的路径规则：逐段净化，绝不允许越出接收目录。
 *
 * 与电脑端 `SafeRelative` 对齐，两边各写一份（一个 Kotlin 一个 C#），
 * 单测覆盖同一批用例，避免两边对「奇怪文件名」的判断跑偏。
 */
object PcServerPaths {

    private const val MAX_SEGMENT = 120
    private const val MAX_DEPTH = 24

    /** 把 `照片/2024/a.jpg` 净化成安全的相对路径；全被过滤掉时退回 [fallback]。 */
    fun safeRelative(rawPath: String?, fallback: String = ""): String {
        val decoded = rawPath?.let { percentDecode(it) }.orEmpty()
        if (decoded.isBlank()) return safeName(fallback)
        val segments = decoded.replace('\\', '/').split('/').mapNotNull { segment ->
            val cleaned = safeName(segment)
            cleaned.ifEmpty { null }
        }
        if (segments.isEmpty()) return safeName(fallback)
        return segments.take(MAX_DEPTH).joinToString("/")
    }

    /** 单段净化：过滤非法字符、去掉首尾空白与尾部点、拦 `..`。 */
    fun safeName(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "." || text == "..") return ""
        val builder = StringBuilder(text.length)
        for (c in text) {
            builder.append(if (c in INVALID) '_' else c)
        }
        val cleaned = builder.toString().trim().trimEnd('.')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return ""
        return if (cleaned.length > MAX_SEGMENT) cleaned.substring(0, MAX_SEGMENT) else cleaned
    }

    /** 重名不覆盖：`a.apk` → `a (1).apk`。 */
    fun uniqueTarget(file: File): File {
        if (!file.exists()) return file
        val dir = file.parentFile ?: return file
        val name = file.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (index < 10_000) {
            val candidate = File(dir, "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index += 1
        }
        return File(dir, "$base-${System.currentTimeMillis()}$ext")
    }

    /** 日志/提示里显示相对接收目录的短路径。 */
    fun relative(root: File, file: File): String {
        val rootPath = root.absolutePath.trimEnd('/')
        val path = file.absolutePath
        return if (path.startsWith("$rootPath/")) path.removePrefix("$rootPath/") else file.name
    }

    /** 只要非保留字符之外的内容都按 UTF-8 百分号编码还原；解不开就原样用。 */
    internal fun percentDecode(raw: String): String {
        if (!raw.contains('%')) return raw
        val out = java.io.ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c == '%' && index + 2 < raw.length) {
                val value = raw.substring(index + 1, index + 3).toIntOrNull(16)
                if (value != null) {
                    out.write(value)
                    index += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            index += 1
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private val INVALID: Set<Char> = buildSet {
        add('/'); add('\\'); add(':'); add('*'); add('?'); add('"'); add('<'); add('>'); add('|')
        for (c in 0..31) add(c.toChar())
    }
}
