package com.vvfile.app.feature.browser

/**
 * 目录滚动位置记忆：按「窗格 + 目录」记下上次停在哪一行，退回该目录时滚回原处。
 *
 * 刻意不做成 Compose 状态：只在导航副作用里读写，写进去不触发重组。
 * 目录内容一变（排序、过滤、显示隐藏文件）行号就不再对应原来那条记录，
 * 这几种情况由调用方整片清掉，宁可回到顶部也不要停在错位的行上。
 */
class ScrollMemory(private val limit: Int = DEFAULT_LIMIT) {

    /** 落点：列表首行的索引与它在视口内的偏移，两者一起才是一次准确的还原。 */
    data class Position(val index: Int, val offset: Int)

    // accessOrder = true：读写都算访问，超出上限时淘汰最久没碰过的目录
    private val map = LinkedHashMap<String, Position>(16, 0.75f, true)

    /** 记下 [pane] 停在 [path] 的哪一行；停在顶部（0,0）视作没有落点，顺手清掉。 */
    fun remember(pane: PaneId, path: String, index: Int, offset: Int) {
        if (path.isBlank()) return
        if (index <= 0 && offset <= 0) {
            map.remove(key(pane, path))
            return
        }
        map[key(pane, path)] = Position(index, offset)
        trim()
    }

    fun position(pane: PaneId, path: String): Position? =
        if (path.isBlank()) null else map[key(pane, path)]

    fun clearPane(pane: PaneId) {
        val prefix = "${pane.name}:"
        map.keys.removeAll { it.startsWith(prefix) }
    }

    fun clear() = map.clear()

    /** 已记住的目录数，仅用于测试与诊断。 */
    val size: Int get() = map.size

    private fun trim() {
        while (map.size > limit) {
            val oldest = map.keys.firstOrNull() ?: return
            map.remove(oldest)
        }
    }

    private fun key(pane: PaneId, path: String) = "${pane.name}:$path"

    companion object {
        /** 两个窗格各留一份，几十个目录足够覆盖来回翻的路径，再多也没人回得去。 */
        const val DEFAULT_LIMIT = 64
    }
}
