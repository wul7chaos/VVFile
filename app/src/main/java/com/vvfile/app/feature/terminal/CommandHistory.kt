package com.vvfile.app.feature.terminal

/**
 * 命令历史（内存，最多 [capacity] 条），以及上下键导航的纯逻辑。
 *
 * 索引约定：-1 表示「不在历史中」（当前草稿）；0..size-1 为从旧到新的历史项。
 * 上键：-1 → 最新一条；已有索引则向前；到最旧后停住。
 * 下键：到最新一条后再按回到 -1（清空输入）；-1 时保持 -1。
 */
internal class CommandHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<String>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @get:Synchronized
    val size: Int get() = entries.size

    @Synchronized
    fun record(command: String) {
        val value = command.trim()
        if (value.isEmpty()) return
        entries.addLast(value)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun get(index: Int): String? = entries.getOrNull(index)

    @Synchronized
    fun upIndex(current: Int): Int = when {
        entries.isEmpty() -> -1
        current < 0 -> entries.size - 1
        current > 0 -> current - 1
        else -> 0
    }

    @Synchronized
    fun downIndex(current: Int): Int = when {
        entries.isEmpty() || current < 0 -> -1
        current >= entries.size - 1 -> -1
        else -> current + 1
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
