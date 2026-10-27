package com.vvfile.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.core.io.RootStaging
import com.vvfile.app.core.path.WorkspaceLayout
import com.vvfile.app.core.share.FileOpener
import com.vvfile.app.core.shell.RootAccess
import com.vvfile.app.data.prefs.PrefsRepository
import com.vvfile.app.data.prefs.Settings
import com.vvfile.app.data.prefs.ThemeMode
import com.vvfile.app.feature.apps.AppListScreen
import com.vvfile.app.feature.archive.ArchiveFormats
import com.vvfile.app.feature.archive.ArchiveScreen
import com.vvfile.app.feature.browser.BuiltinOpener
import com.vvfile.app.feature.browser.BrowserScreen
import com.vvfile.app.feature.editor.EditorScreen
import com.vvfile.app.feature.media.AudioEngine
import com.vvfile.app.feature.media.AudioMiniBar
import com.vvfile.app.feature.media.AudioPlayerPanel
import com.vvfile.app.feature.media.ImageViewerScreen
import com.vvfile.app.feature.media.MediaFormats
import com.vvfile.app.feature.media.MediaKind
import com.vvfile.app.feature.media.VideoPlayerScreen
import com.vvfile.app.feature.onboarding.AgreementScreen
import com.vvfile.app.feature.settings.SettingsScreen
import com.vvfile.app.feature.terminal.TerminalScreen
import com.vvfile.app.ui.theme.AccentPresets
import com.vvfile.app.ui.theme.VVFileTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** root-only 文件的落地缓存，只给只读打开用（播放器/解码器/压缩包）。 */
    private val rootStaging = RootStaging()

    /** 从「定位所在目录」带上来的目标文件；Compose 消费后清空。 */
    private val locatePath = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        locatePath.value = intent?.getStringExtra(EXTRA_LOCATE_PATH)
        setContent {
            val context = LocalContext.current
            val settingsState by PrefsRepository.get(context).settings
                .collectAsStateWithLifecycle(initialValue = null)
            // 还没读到设置时先当默认值用；协议页是否显示只看 agreementAccepted
            val settings = settingsState ?: Settings.DEFAULT
            val darkTheme = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK, ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
            }
            val view = LocalView.current
            SideEffect {
                RootAccess.policy = settings.rootPolicy
                val window = (context as? ComponentActivity)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            VVFileTheme(
                darkTheme = darkTheme,
                pureBlack = settings.theme == ThemeMode.BLACK,
                accent = AccentPresets.getOrNull(settings.accentIndex),
            ) {
                // 首次启动只显示使用协议页，同意之后才进主界面
                val accepted = settingsState?.agreementAccepted
                if (accepted != true) {
                    if (accepted == false) {
                        AgreementScreen(
                            onAccept = {
                                lifecycleScope.launch {
                                    PrefsRepository.get(this@MainActivity).setAgreementAccepted(true)
                                }
                            },
                            onExit = { finish() },
                        )
                    }
                    return@VVFileTheme
                }
                // 屏幕开关用 rememberSaveable：旋转屏幕后仍停留在同一页
                // （终端的 ViewModel 会跨配置变更存活，PTY 会话不中断）。
                var editingPath by rememberSaveable { mutableStateOf<String?>(null) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showTerminal by rememberSaveable { mutableStateOf(false) }
                var showApps by rememberSaveable { mutableStateOf(false) }
                // 内置播放器：音频走可最小化的面板，视频/图片整页覆盖
                var audioPath by rememberSaveable { mutableStateOf<String?>(null) }
                var audioMinimized by rememberSaveable { mutableStateOf(false) }
                var videoPath by rememberSaveable { mutableStateOf<String?>(null) }
                var imagePath by rememberSaveable { mutableStateOf<String?>(null) }
                // 「应用信息」点数据目录后带到浏览器的目标路径
                var browseTarget by rememberSaveable { mutableStateOf<String?>(null) }
                // 压缩包浏览：非空时整页覆盖在文件列表之上
                var archivePath by rememberSaveable { mutableStateOf<String?>(null) }

                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                fun ensureNotificationPermission() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                /**
                 * 只读打开前先确认应用自己读得到：root-only 目录（`/data/adb` 等）里的
                 * 文件先复制一份到缓存再交给播放器/解码器——它们只认路径，不会用 su。
                 *
                 * 文本编辑器刻意不走这里：它要改的是**原文件**，
                 * 拿缓存副本编辑再把保存写到缓存里，等于改了没生效。
                 */
                fun openReadOnly(path: String, onReady: (String) -> Unit) {
                    val readable = runCatching { File(path).canRead() }.getOrDefault(false)
                    if (readable || !RootAccess.allowed()) {
                        onReady(path)
                        return
                    }
                    lifecycleScope.launch {
                        val staged = withContext(Dispatchers.IO) { rootStaging.stage(path) }
                        onReady(staged?.absolutePath ?: path)
                    }
                }

                /**
                 * 「打开方式」里指定了内置打开器：不按扩展名猜，直接进对应的查看器。
                 *
                 * 扩展名对不上也照开——把一个 .bin 交给文本编辑器看看到底是不是文本，
                 * 是这类「强制打开」的典型用法。
                 */
                fun openAs(path: String, opener: BuiltinOpener) {
                    when (opener) {
                        BuiltinOpener.TEXT -> editingPath = path
                        BuiltinOpener.IMAGE -> openReadOnly(path) { ready -> imagePath = ready }
                        BuiltinOpener.AUDIO -> {
                            ensureNotificationPermission()
                            openReadOnly(path) { ready ->
                                audioPath = ready
                                audioMinimized = false
                            }
                        }
                        BuiltinOpener.VIDEO -> {
                            if (AudioEngine.state.value.playing) {
                                AudioEngine.togglePlayPause()
                            }
                            openReadOnly(path) { ready -> videoPath = ready }
                        }
                        BuiltinOpener.ARCHIVE -> openReadOnly(path) { ready -> archivePath = ready }
                    }
                }

                /**
                 * 按类型打开一个真实路径：音视频/图片进内置播放器，文本进编辑器，
                 * 其余交给系统。压缩包浏览里解出来的临时文件也走这里。
                 */
                fun openPath(path: String) {
                    // 压缩包（zip/apk/tar/tar.gz）一律先进包内浏览：想把 APK 交给安装器、
                    // 或交给别的应用，走长按「打开方式」
                    if (ArchiveFormats.isArchive(path)) {
                        openReadOnly(path) { ready -> archivePath = ready }
                        return
                    }
                    when (MediaFormats.kindOf(path)) {
                        MediaKind.AUDIO -> {
                            ensureNotificationPermission()
                            openReadOnly(path) { ready ->
                                audioPath = ready
                                audioMinimized = false
                            }
                        }
                        MediaKind.VIDEO -> {
                            // 看视频时把后台音频收住，避免两个声音叠在一起
                            if (AudioEngine.state.value.playing) {
                                AudioEngine.togglePlayPause()
                            }
                            openReadOnly(path) { ready -> videoPath = ready }
                        }
                        MediaKind.IMAGE -> openReadOnly(path) { ready -> imagePath = ready }
                        null -> if (MimeTypes.isText(path)) {
                            editingPath = path
                        } else {
                            openReadOnly(path) { ready ->
                                if (!FileOpener.open(this@MainActivity, ready)) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "没有可打开该文件的应用",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    }
                }

                // 从外部「定位所在目录」进来时，先把盖在浏览器上的页面收起来
                LaunchedEffect(locatePath.value) {
                    if (locatePath.value == null) return@LaunchedEffect
                    editingPath = null
                    archivePath = null
                    showSettings = false
                    showTerminal = false
                    showApps = false
                }

                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(
                        navigateTo = browseTarget,
                        onNavigateHandled = { browseTarget = null },
                        locateFile = locatePath.value,
                        onLocateHandled = { locatePath.value = null },
                        onOpenBuiltin = { path, opener -> openAs(path, opener) },
                        // 点压缩包直接进去看，而不是弹「选择打开方式」；判断统一在 openPath 里做
                        onOpenFile = { path -> openPath(path) },
                        onToolClick = { tool ->
                            when (tool) {
                                "设置" -> showSettings = true
                                "终端模拟器" -> showTerminal = true
                                "已安装应用" -> showApps = true
                                "文本编辑器" -> lifecycleScope.launch {
                                    val path = withContext(Dispatchers.IO) {
                                        createScratchDocument(this@MainActivity)
                                    }
                                    if (path != null) {
                                        editingPath = path
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "无法创建临时文件",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                else -> Toast.makeText(
                                    this@MainActivity,
                                    "$tool · 后续里程碑接入",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )

                    archivePath?.let { path ->
                        ArchiveScreen(
                            archivePath = path,
                            onClose = { archivePath = null },
                            onOpenEntry = { extracted -> openPath(extracted) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    editingPath?.let { path ->
                        EditorScreen(
                            path = path,
                            onClose = { editingPath = null },
                            modifier = Modifier.fillMaxSize(),
                            fontSizeSp = settings.editorFontSize.toFloat(),
                        )
                    }

                    if (showSettings) {
                        SettingsScreen(
                            onClose = { showSettings = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (showTerminal) {
                        TerminalScreen(
                            onClose = { showTerminal = false },
                            modifier = Modifier.fillMaxSize(),
                            fontSizeSp = settings.terminalFontSize.toFloat(),
                            onFontSizeChange = { size ->
                                lifecycleScope.launch {
                                    PrefsRepository.get(this@MainActivity).setTerminalFontSize(size)
                                }
                            },
                        )
                    }

                    if (showApps) {
                        AppListScreen(
                            onClose = { showApps = false },
                            modifier = Modifier.fillMaxSize(),
                            onOpenPath = { path ->
                                showApps = false
                                browseTarget = path
                            },
                        )
                    }

                    // 音频面板：最小化后换成底部的迷你条，播放本身在服务里继续
                    val playingAudio = audioPath
                    if (playingAudio != null && !audioMinimized) {
                        AudioPlayerPanel(
                            path = playingAudio,
                            onMinimize = { audioMinimized = true },
                            onClose = {
                                audioPath = null
                                audioMinimized = false
                            },
                        )
                    }
                    if (playingAudio != null && audioMinimized) {
                        AudioMiniBar(
                            onExpand = { audioMinimized = false },
                            onClose = {
                                audioPath = null
                                audioMinimized = false
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }

                    imagePath?.let { path ->
                        ImageViewerScreen(path = path, onClose = { imagePath = null })
                    }

                    videoPath?.let { path ->
                        VideoPlayerScreen(path = path, onClose = { videoPath = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_LOCATE_PATH)?.let { locatePath.value = it }
    }

    /**
     * 侧栏「文本编辑器」：新建一个临时 txt 并直接进入编辑。
     *
     * 文件放在应用专属目录 `/storage/emulated/0/VVFile/temp/新建文本-时间戳.txt`，
     * 与导出的 APK 一起集中在 VVFile 文件夹下，不污染「下载」等公共目录；
     * 共享存储不可用时回退到应用缓存目录。
     */
    private fun createScratchDocument(context: Context): String? = runCatching {
        val layout = WorkspaceLayout.default()
        val dir = layout.ensure(layout.tempDir)
            ?: layout.ensure(File(context.cacheDir, SCRATCH_DIR))
            ?: return@runCatching null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val file = File(dir, "新建文本-$stamp.txt")
        if (!file.exists() && !file.createNewFile()) return@runCatching null
        file.absolutePath
    }.getOrNull()

    companion object {
        private const val SCRATCH_DIR = "editor"

        /** 「定位所在目录」传来的目标文件绝对路径。 */
        const val EXTRA_LOCATE_PATH = "com.vvfile.app.extra.LOCATE_PATH"
    }
}
