package com.vvfile.app.core.path

import android.os.Environment
import java.io.File

/**
 * VVFile 的专属工作目录。
 *
 * 参考 MT 管理器的习惯：所有由本应用「生成」的文件都集中在共享存储根目录下的
 * 独立文件夹里，不散落到「下载」等公共目录。当前布局：
 * ```
 * /storage/emulated/0/VVFile/
 * ├── apk/   导出的 APK
 * └── temp/  编辑器「新建临时文本」创建的 txt
 * ```
 * 目录按需创建；共享存储不可用（未授权 / 未挂载）时由调用方回退到应用私有目录。
 */
class WorkspaceLayout(val root: File) {

    val apkDir: File get() = File(root, APK_DIR_NAME)

    val tempDir: File get() = File(root, TEMP_DIR_NAME)

    /** 确保目录存在，成功返回自身，失败返回 null。 */
    fun ensure(dir: File): File? = when {
        dir.isDirectory -> dir
        dir.mkdirs() -> dir
        else -> null
    }

    companion object {
        /** 共享存储根目录下的文件夹名。 */
        const val ROOT_NAME = "VVFile"
        const val APK_DIR_NAME = "apk"
        const val TEMP_DIR_NAME = "temp"

        /** `/storage/emulated/0/VVFile`。 */
        fun default(): WorkspaceLayout = WorkspaceLayout(
            File(Environment.getExternalStorageDirectory(), ROOT_NAME),
        )
    }
}
