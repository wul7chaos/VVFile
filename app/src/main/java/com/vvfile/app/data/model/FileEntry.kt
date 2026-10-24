package com.vvfile.app.data.model

/** 文件列表中的一个条目。 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isSymlink: Boolean = false,
    /** rwxr-xr-x 形式；FUSE/不支持 POSIX 的卷上为 null。 */
    val permissions: String? = null,
    val owner: String? = null,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()
}
