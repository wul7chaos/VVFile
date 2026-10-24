package com.vvfile.app.core.format

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormatter {
    private val fullPattern = SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault())
    private val shortPattern = SimpleDateFormat("MM-dd", Locale.getDefault())

    @Synchronized
    fun format(epochMillis: Long): String {
        if (epochMillis <= 0L) return "-"
        return fullPattern.format(Date(epochMillis))
    }

    /** 列表行使用的紧凑格式（窄窗格不截断）。 */
    @Synchronized
    fun formatShort(epochMillis: Long): String {
        if (epochMillis <= 0L) return "-"
        return shortPattern.format(Date(epochMillis))
    }
}
