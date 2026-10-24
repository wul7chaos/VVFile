package com.vvfile.app.data.model

enum class SortField { NAME, DATE, SIZE, TYPE }

/**
 * 排序规则：默认目录优先，其余按字段比较；同字段再次选择时切换升降序。
 */
data class SortSpec(
    val field: SortField = SortField.NAME,
    val ascending: Boolean = true,
    val directoriesFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileEntry> {
        val primary: Comparator<FileEntry> = when (field) {
            SortField.NAME -> compareBy { it.name.lowercase() }
            SortField.DATE -> compareBy { it.lastModified }
            SortField.SIZE -> compareBy { it.size }
            SortField.TYPE -> compareBy<FileEntry> { it.extension }.thenBy { it.name.lowercase() }
        }
        val ordered = if (ascending) primary else primary.reversed()
        return if (directoriesFirst) {
            compareByDescending<FileEntry> { it.isDirectory }.then(ordered)
        } else {
            ordered
        }
    }
}
