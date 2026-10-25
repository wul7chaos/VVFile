package com.vvfile.app.core.format

import java.util.Locale

/** 人类可读大小：1.2 KB / 345 MB / 6.7 GB。 */
object SizeFormatter {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** MT 风格的紧凑写法：23.14M / 345K / 1.20G，用于应用列表第二行。 */
    fun formatCompact(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val letter = units[unit].take(1)
        return if (unit == 1) {
            String.format(Locale.US, "%.1f%s", value, letter)
        } else {
            String.format(Locale.US, "%.2f%s", value, letter)
        }
    }

    fun format(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (value >= 100) {
            String.format(Locale.US, "%.0f %s", value, units[unit])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }
}
