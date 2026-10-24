package com.vvfile.app

import android.app.Application
import com.vvfile.app.core.io.FallbackFileSystem
import com.vvfile.app.core.io.NioFileSystem
import com.vvfile.app.core.io.ShellFileSystem
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.data.file.FileOperator
import com.vvfile.app.data.file.FileRepository
import com.vvfile.app.data.file.LocalFileOperator

class VVFileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init()
    }
}

/** 轻量服务定位器；模块拆分后再替换为依赖注入。 */
object ServiceLocator {
    lateinit var fileRepository: FileRepository
        private set
    lateinit var fileOperator: FileOperator
        private set

    fun init() {
        if (!::fileRepository.isInitialized) {
            // 普通目录走 NIO；无权限且 root 可用时自动回退到 su 只读通道
            fileRepository = FileRepository(
                FallbackFileSystem(
                    direct = NioFileSystem(),
                    root = ShellFileSystem(),
                    rootAvailable = RootManager::isAvailable,
                ),
            )
        }
        if (!::fileOperator.isInitialized) {
            fileOperator = LocalFileOperator()
        }
    }
}
