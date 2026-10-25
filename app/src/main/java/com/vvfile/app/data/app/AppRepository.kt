package com.vvfile.app.data.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import com.vvfile.app.core.path.WorkspaceLayout
import com.vvfile.app.core.shell.CommandRunner
import com.vvfile.app.core.shell.RootAccess
import com.vvfile.app.core.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 已安装应用数据源：PackageManager 查询、启动、应用信息、卸载、导出 APK。
 *
 * 列表来源分两条路：
 * - 有 root：`su -c pm list packages -f -U` 枚举，能拿到 PackageManager 因可见性
 *   限制看不到的包，再用 PackageManager 补齐名称 / 图标 / 版本（拿不到就退回包名）；
 * - 无 root：走 PackageManager（依赖 `QUERY_ALL_PACKAGES` 才能看到全部包）。
 *
 * 所有重 IO 操作都在 Dispatchers.IO 上执行；图标按包名缓存。
 */
class AppRepository(
    private val context: Context,
    private val runner: CommandRunner = ShellExecutor(),
) {

    private val packageManager = context.packageManager
    private val iconCache = HashMap<String, Bitmap?>()
    private val collator = Collator.getInstance(Locale.CHINA)

    /**
     * 读取已安装应用列表。
     *
     * 有缓存且 PackageManager 报告「自上次扫描以来没有包变化」时直接返回缓存，
     * 避免每次打开列表都跑一遍 `pm list packages`（root 通道尤其慢）。
     * [force] 为 true（下拉刷新）或缓存失效时重新全量扫描。
     */
    suspend fun loadApps(force: Boolean = false): List<AppInfo> = withContext(Dispatchers.IO) {
        if (!force) {
            cachedApps()?.let { snapshot ->
                if (!hasPackageChanges()) return@withContext snapshot
            }
        }
        val fromRoot = if (RootAccess.allowed()) {
            runCatching { loadFromRoot() }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        val apps = if (fromRoot.isNotEmpty()) fromRoot else loadFromPackageManager()
        val sorted = apps.sortedWith { left, right -> collator.compare(left.label, right.label) }
        storeCache(sorted)
        sorted
    }

    /**
     * 包变化检测：`getChangedPackages(seq)` 只返回序列号之后的包名，
     * 比重新枚举全量包轻得多。返回 null（序列太旧 / 不可用）时按「有变化」处理。
     */
    private fun hasPackageChanges(): Boolean {
        val sequence = cachedSequence
        if (sequence < 0) return true
        val changed = runCatching { packageManager.getChangedPackages(sequence) }.getOrNull()
            ?: return true
        return changed.packageNames.isNotEmpty()
    }

    /** 当前包序列号；API 26+ 才有，取 0 号序列会返回全量变更的快照。 */
    private fun currentPackageSequence(): Int = runCatching {
        packageManager.getChangedPackages(0)?.sequenceNumber ?: 0
    }.getOrDefault(0)

    private fun storeCache(apps: List<AppInfo>) {
        synchronized(cacheLock) {
            appCache = apps
            cachedSequence = currentPackageSequence()
            cacheTime = System.currentTimeMillis()
        }
    }


    /**
     * root 通道：`pm list packages -f -U` 拿到全量包名与 APK 路径。
     * 失败（无 su、授权被拒、超时）返回空列表，调用方回退到 PackageManager。
     */
    private suspend fun loadFromRoot(): List<AppInfo> {
        val result = runner.exec(ROOT_LIST_COMMAND, asRoot = true, timeoutMs = ROOT_TIMEOUT_MS)
        if (!result.isSuccess) return emptyList()
        val entries = parsePackageListing(result.stdout)
        if (entries.isEmpty()) return emptyList()
        return entries.map { entry -> buildAppInfo(entry) }
    }

    /** 用 PackageManager 补齐 root 枚举到的包的展示信息；不可见时退回最小信息。 */
    private fun buildAppInfo(entry: PackageEntry): AppInfo {
        val info = runCatching { packageManager.getPackageInfo(entry.packageName, 0) }.getOrNull()
        val application = info?.applicationInfo
        val apkPath = application?.sourceDir?.takeIf { it.isNotBlank() } ?: entry.apkPath
        val label = application?.let { app ->
            runCatching { app.loadLabel(packageManager).toString() }.getOrNull()
        }.takeUnless { it.isNullOrBlank() } ?: entry.packageName
        return AppInfo(
            packageName = entry.packageName,
            label = label,
            versionName = info?.versionName.orEmpty(),
            versionCode = versionCodeOf(info),
            isSystem = application?.let { isSystemApp(it) } ?: isSystemPath(apkPath),
            apkSizeBytes = runCatching { File(apkPath).length() }.getOrDefault(0L),
            lastUpdateTime = info?.lastUpdateTime ?: 0L,
            icon = application?.let { loadIcon(entry.packageName, it) },
            sourceDir = apkPath,
            firstInstallTime = info?.firstInstallTime ?: 0L,
        )
    }

    @Suppress("DEPRECATION")
    private fun loadFromPackageManager(): List<AppInfo> =
        packageManager.getInstalledPackages(0).mapNotNull { info ->
            val application = info.applicationInfo ?: return@mapNotNull null
            val packageName = info.packageName
            AppInfo(
                packageName = packageName,
                label = runCatching { application.loadLabel(packageManager).toString() }
                    .getOrDefault(packageName),
                versionName = info.versionName.orEmpty(),
                versionCode = versionCodeOf(info),
                isSystem = isSystemApp(application),
                apkSizeBytes = runCatching { File(application.sourceDir).length() }.getOrDefault(0L),
                lastUpdateTime = info.lastUpdateTime,
                icon = loadIcon(packageName, application),
                sourceDir = application.sourceDir.orEmpty(),
                firstInstallTime = info.firstInstallTime,
            )
        }

    private fun versionCodeOf(info: PackageInfo?): Long {
        if (info == null) return 0L
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun isSystemApp(application: ApplicationInfo): Boolean =
        (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (application.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** PM 查不到时，用 APK 路径推断是否系统应用。 */
    private fun isSystemPath(apkPath: String): Boolean =
        SYSTEM_PREFIXES.any { apkPath.startsWith(it) }

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

    /** 打开系统「应用信息」页（弹窗里的「更多」）。 */
    fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 读取「应用信息」弹窗需要的字段。
     *
     * PackageManager 查不到（root 枚举到、但应用层不可见的包）时退回 [AppDetails.from]，
     * 只展示列表里已有的信息，不编造签名 / 加固 / UID 等拿不到的内容。
     */
    @Suppress("DEPRECATION")
    suspend fun loadDetails(app: AppInfo): AppDetails = withContext(Dispatchers.IO) {
        val info = runCatching { packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val application = info?.applicationInfo
        val apkPath = application?.sourceDir?.takeIf { it.isNotBlank() } ?: app.sourceDir
        val nativeAbis = readNativeAbis(apkPath)
        if (info == null) return@withContext AppDetails.from(app, nativeAbis)
        AppDetails(
            packageName = app.packageName,
            label = runCatching { application?.loadLabel(packageManager)?.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: app.label,
            versionName = info.versionName.orEmpty(),
            versionCode = versionCodeOf(info),
            apkSizeBytes = runCatching { File(apkPath).length() }.getOrDefault(app.apkSizeBytes),
            nativeAbis = nativeAbis,
            dataDir = application?.dataDir,
            externalDataDir = File(
                Environment.getExternalStorageDirectory(),
                "Android/data/${app.packageName}",
            ).absolutePath,
            apkPath = apkPath,
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime,
            icon = application?.let { loadIcon(app.packageName, it) } ?: app.icon,
        )
    }

    /** 从 APK 的 `lib/<abi>/` 读出原生架构；读不到（纯 Java、路径不可读）返回空列表。 */
    private fun readNativeAbis(apkPath: String): List<String> = runCatching {
        java.util.zip.ZipFile(apkPath).use { zip ->
            parseAbisFromEntries(zip.entries().asSequence().map { it.name })
        }
    }.getOrDefault(emptyList())

    fun requestUninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 导出 APK 到应用专属目录 `/storage/emulated/0/VVFile/apk`（共享存储不可用时回退到
     * 应用外部私有目录），文件名冲突时追加 (n)。[onProgress] 以 0..1 回调。
     */
    suspend fun exportApk(
        app: AppInfo,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val source = File(app.sourceDir)
        require(source.isFile) { "找不到 APK 文件" }
        val layout = WorkspaceLayout.default()
        val dir = layout.ensure(layout.apkDir)
            ?: context.getExternalFilesDir(WorkspaceLayout.APK_DIR_NAME)?.let { layout.ensure(it) }
            ?: error("无法创建 ${WorkspaceLayout.ROOT_NAME}/${WorkspaceLayout.APK_DIR_NAME} 目录，请检查存储权限")
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

    companion object {
        private const val ROOT_LIST_COMMAND = "pm list packages -f -U --user 0"
        private const val ROOT_TIMEOUT_MS = 20_000L
        private val SYSTEM_PREFIXES = listOf(
            "/system/",
            "/system_ext/",
            "/product/",
            "/vendor/",
            "/odm/",
            "/oem/",
            "/apex/",
            "/my_product/",
            "/my_heytap/",
        )

        // 进程级缓存：每次进入「已安装应用」都 new 一个 repository，所以缓存放在 companion。
        private val cacheLock = Any()

        @Volatile
        private var appCache: List<AppInfo>? = null

        /** `getChangedPackages` 的序列号；-1 表示还没有有效缓存。 */
        @Volatile
        private var cachedSequence: Int = -1

        @Volatile
        private var cacheTime: Long = 0L

        /** 只读快照；没有缓存返回 null。 */
        fun cachedApps(): List<AppInfo>? = appCache

        fun cacheAgeMillis(): Long =
            if (appCache == null) -1L else System.currentTimeMillis() - cacheTime

        /** 包变化广播 / 卸载 / 安装后调用，下一次 [loadApps] 会重新全量扫描。 */
        fun invalidateCache() {
            synchronized(cacheLock) {
                appCache = null
                cachedSequence = -1
                cacheTime = 0L
            }
        }
    }
}
