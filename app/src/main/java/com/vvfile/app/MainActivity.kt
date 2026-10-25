package com.vvfile.app

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.vvfile.app.core.path.WorkspaceLayout
import com.vvfile.app.core.share.FileOpener
import com.vvfile.app.core.shell.RootAccess
import com.vvfile.app.data.prefs.PrefsRepository
import com.vvfile.app.data.prefs.Settings
import com.vvfile.app.data.prefs.ThemeMode
import com.vvfile.app.feature.apps.AppListScreen
import com.vvfile.app.feature.browser.BrowserScreen
import com.vvfile.app.feature.editor.EditorScreen
import com.vvfile.app.feature.media.AudioEngine
import com.vvfile.app.feature.media.AudioMiniBar
import com.vvfile.app.feature.media.AudioPlayerPanel
import com.vvfile.app.feature.media.ImageViewerScreen
import com.vvfile.app.feature.media.MediaFormats
import com.vvfile.app.feature.media.MediaKind
import com.vvfile.app.feature.media.VideoPlayerScreen
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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val settings by PrefsRepository.get(context).settings
                .collectAsStateWithLifecycle(initialValue = Settings.DEFAULT)
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
                var editingPath by remember { mutableStateOf<String?>(null) }
                var showSettings by remember { mutableStateOf(false) }
                var showTerminal by remember { mutableStateOf(false) }
                var showApps by remember { mutableStateOf(false) }
                // 内置播放器：音频走可最小化的面板，视频/图片整页覆盖
                var audioPath by remember { mutableStateOf<String?>(null) }
                var audioMinimized by remember { mutableStateOf(false) }
                var videoPath by remember { mutableStateOf<String?>(null) }
                var imagePath by remember { mutableStateOf<String?>(null) }
                // 「应用信息」点数据目录后带到浏览器的目标路径
                var browseTarget by remember { mutableStateOf<String?>(null) }

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

                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(
                        navigateTo = browseTarget,
                        onNavigateHandled = { browseTarget = null },
                        onOpenFile = { path ->
                            when (MediaFormats.kindOf(path)) {
                                MediaKind.AUDIO -> {
                                    ensureNotificationPermission()
                                    audioPath = path
                                    audioMinimized = false
                                }
                                MediaKind.VIDEO -> {
                                    // 看视频时把后台音频收住，避免两个声音叠在一起
                                    if (AudioEngine.state.value.playing) {
                                        AudioEngine.togglePlayPause()
                                    }
                                    videoPath = path
                                }
                                MediaKind.IMAGE -> imagePath = path
                                null -> if (MimeTypes.isText(path)) {
                                    editingPath = path
                                } else if (!FileOpener.open(this@MainActivity, path)) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "没有可打开该文件的应用",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
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

    private companion object {
        private const val SCRATCH_DIR = "editor"
    }
}
