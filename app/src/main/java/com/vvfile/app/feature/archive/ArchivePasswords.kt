package com.vvfile.app.feature.archive

/**
 * 本次运行记住的压缩包密码。
 *
 * 只在内存里，按压缩包**路径**做键：同一个包点开第二遍、或者解完一个条目再点另一个，
 * 不该每次都让人重输。上限 16 条（LRU），进程结束即忘——不落盘是有意的，
 * 用户不会希望密码躺在某个文件里。
 */
object ArchivePasswords {

    private const val LIMIT = 16

    private val cache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > LIMIT
    }

    @Synchronized
    fun get(path: String): String? = cache[path]

    @Synchronized
    fun remember(path: String, password: String) {
        if (password.isNotEmpty()) cache[path] = password
    }

    @Synchronized
    fun forget(path: String) {
        cache.remove(path)
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    @Synchronized
    fun size(): Int = cache.size
}
