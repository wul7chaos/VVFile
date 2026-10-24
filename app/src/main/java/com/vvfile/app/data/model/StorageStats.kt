package com.vvfile.app.data.model

/** 分区容量。 */
data class StorageStats(
    val totalBytes: Long,
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)
    val usedFraction: Float
        get() = if (totalBytes <= 0) 0f else usedBytes.toFloat() / totalBytes.toFloat()
}
