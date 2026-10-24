package com.vvfile.app.core.result

/** 目标已存在时的处理策略。 */
enum class ConflictPolicy { RENAME, REPLACE, SKIP }

/** 复制/移动前的冲突描述，用于 UI 提示。 */
data class ConflictInfo(
    val sourcePath: String,
    val targetPath: String,
)

enum class OperationPhase { PREPARING, RUNNING, FINISHED, CANCELLED, FAILED }

/** 批量文件操作的统一进度。 */
data class OperationProgress(
    val phase: OperationPhase = OperationPhase.PREPARING,
    val currentName: String = "",
    val processedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val processedItems: Int = 0,
    val totalItems: Int = 0,
) {
    val fraction: Float
        get() = when {
            totalBytes > 0L -> (processedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            totalItems > 0 -> (processedItems.toFloat() / totalItems).coerceIn(0f, 1f)
            phase == OperationPhase.FINISHED -> 1f
            else -> 0f
        }
}

data class OperationFailure(
    val path: String,
    val reason: String,
)

data class OperationResult(
    val succeeded: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val failures: List<OperationFailure> = emptyList(),
    val cancelled: Boolean = false,
) {
    val isSuccess: Boolean get() = failures.isEmpty() && !cancelled
}

sealed interface OperationEvent {
    data class Progress(val progress: OperationProgress) : OperationEvent
    data class Finished(val result: OperationResult) : OperationEvent
}
