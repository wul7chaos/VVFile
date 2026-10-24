package com.vvfile.app.data.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 已安装应用数据源：PackageManager 查询、启动、应用信息、卸载、导出 APK。
 * 所有重 IO 操作都在 Dispatchers.IO 上执行；图标按包名缓存。
 */
class AppRepository(private val context: Context) {

    private val packageManager = context.packageManager
    private val iconCache = HashMap<String, Bitmap?>()
    private val collator = Collator.getInstance(Locale.CHINA)

    @Suppress("DEPRECATION")
    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        packageManager.getInstalledPackages(0).mapNotNull { info ->
            val application = info.applicationInfo ?: return@mapNotNull null
            val packageName = info.packageName
            AppInfo(
                packageName = packageName,
                label = runCatching { application.loadLabel(packageManager).toString() }
                    .getOrDefault(packageName),
                versionName = info.versionName.orEmpty(),
                versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                },
                isSystem = (application.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                apkSizeBytes = runCatching { File(application.sourceDir).length() }.getOrDefault(0L),
                lastUpdateTime = info.lastUpdateTime,
                icon = loadIcon(packageName, application),
                sourceDir = application.sourceDir.orEmpty(),
            )
        }.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    private fun loadIcon(
        packageName: String,
        application: android.content.pm.ApplicationInfo,
    ): Bitmap? {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]
        val bitmap = runCatching {
            application.loadIcon(packageManager).toBitmap(width = 96, height = 96)
        }.getOrNull()
        iconCache[packageName] = bitmap
        return bitmap
    }

    /** 返回 false 表示没有可启动的 Activity。 */
    fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun requestUninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 导出 APK 到系统下载目录，返回目标文件；文件名冲突时追加 (n)。
     * [onProgress] 以 0..1 回调。
     */
    suspend fun exportApk(
        app: AppInfo,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val source = File(app.sourceDir)
        require(source.isFile) { "找不到 APK 文件" }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) error("无法创建下载目录")
        val baseName = sanitize(app.label) + "-" + sanitize(app.versionName.ifBlank { app.versionCode.toString() })
        var target = File(dir, "$baseName.apk")
        var index = 1
        while (target.exists()) {
            target = File(dir, "$baseName (${index++}).apk")
        }
        val total = source.length().coerceAtLeast(1L)
        var copied = 0L
        source.inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
                output.flush()
            }
        }
        onProgress(1f)
        target
    }

    private fun sanitize(raw: String): String {
        val trimmed = raw.trim().ifBlank { "app" }
        return trimmed.map { char ->
            if (char in "\\/:*?\"<>|") '_' else char
        }.joinToString("")
    }
}
