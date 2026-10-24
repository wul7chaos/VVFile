package com.vvfile.app

import android.app.Application
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
            fileRepository = FileRepository()
        }
        if (!::fileOperator.isInitialized) {
            fileOperator = LocalFileOperator()
        }
    }
}
