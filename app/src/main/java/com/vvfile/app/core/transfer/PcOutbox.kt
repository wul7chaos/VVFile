package com.vvfile.app.core.transfer

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「选中的路径」摊平成一条条待发送的条目：文件原样发，文件夹连结构一起发。
 *
 * 以前的实现是把文件夹压成 zip 再发，等于替用户改了他想发的东西；现在照原样搬——
 * 文件夹里的每个文件带着自己的相对路径发过去（`照片/2024/a.jpg`），
 * 空目录也单独发一条「建目录」请求，电脑端按同样的结构摆好。
 */
object PcOutbox {

    sealed interface Item {
        /** 相对接收根目录的路径，用 `/` 分隔；单个文件就是文件名本身。 */
        val relativePath: String

        data class FileItem(
            val file: File,
            val name: String,
            override val relativePath: String,
        ) : Item

        /** 只用来在电脑端建目录（空文件夹也要还原）。 */
        data class DirItem(override val relativePath: String) : Item
    }

    /** 先目录后文件：确保建目录请求在写文件之前到达。 */
    fun expand(paths: List<String>): List<Item> = buildList {
        val dirs = mutableListOf<Item.DirItem>()
        val files = mutableListOf<Item.FileItem>()
        for (path in paths) {
            val source = File(path)
            when {
                source.isFile -> files += Item.FileItem(source, source.name, source.name)
                source.isDirectory -> {
                    val root = source.absolutePath.trimEnd('/')
                    val base = source.name.ifBlank { "folder" }
                    // 文件夹本身也是一条：空文件夹靠它还原
                    dirs += Item.DirItem(base)
                    source.walkTopDown()
                        .onEnter { it == source || !isSymlink(it) }
                        .forEach { entry ->
                            if (entry == source) return@forEach
                            // 相对路径统一用 `/`；Windows 上 absolutePath 是反斜杠
                            val relative = entry.absolutePath
                                .removePrefix(root)
                                .trimStart('\\', '/')
                                .replace('\\', '/')
                            if (relative.isEmpty()) return@forEach
                            val joined = "$base/$relative"
                            when {
                                entry.isDirectory -> dirs += Item.DirItem(joined)
                                entry.isFile -> files += Item.FileItem(entry, entry.name, joined)
                            }
                        }
                }
            }
        }
        addAll(dirs)
        addAll(files)
    }

    /** 只统计真正的文件字节；目录不占体积。 */
    fun totalBytes(items: List<Item>): Long = items.sumOf { item ->
        when (item) {
            is Item.FileItem -> item.file.length().coerceAtLeast(0)
            is Item.DirItem -> 0L
        }
    }

    suspend fun expandAsync(paths: List<String>): List<Item> = withContext(Dispatchers.IO) {
        expand(paths)
    }

    private fun isSymlink(file: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    }.getOrDefault(false)
}
