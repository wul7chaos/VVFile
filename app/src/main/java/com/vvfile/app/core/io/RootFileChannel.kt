package com.vvfile.app.core.io

/** [RootFileChannel.stat] 的结果：目录标记、字节数与修改时间（秒）。 */
data class RootMeta(
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long = 0L,
)

/**
 * 应用 uid 读不到内容时的备用读取通道。
 *
 * 为什么需要它：浏览、删除、改名都经 `su` 执行，**读文件内容**却是应用自己的
 * `FileInputStream`/`ZipFile`/`BitmapFactory`。两边权限不一样，于是 `/data/adb`
 * 这类只有 root 能读的目录「能列、能删，点开文件却报没有权限」。
 *
 * 生产实现是 [SuRootFileChannel]；测试里注入假实现，避免单测依赖设备上的 su。
 */
interface RootFileChannel {

    /**
     * 读元数据；不存在或读不到返回 null（调用方据此给出「文件不存在」或原始错误）。
     */
    suspend fun stat(path: String): RootMeta?

    /**
     * 流式读出前 [limit] 字节。超出上限直接掐掉子进程，不会把大文件读进内存。
     */
    suspend fun readPrefix(path: String, limit: Long): ByteArray

    /**
     * 整体覆盖写回 [path]；父目录不存在或不允许写时抛异常。
     */
    suspend fun writeAll(path: String, bytes: ByteArray)

    /**
     * 把文件原样复制到 [target]（应用自己的目录），供只认文件路径的组件使用
     * （`ZipFile`、`BitmapFactory`、ExoPlayer）。失败返回 false，不抛异常。
     */
    suspend fun copyTo(path: String, target: java.io.File): Boolean
}
