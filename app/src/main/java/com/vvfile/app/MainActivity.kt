package com.vvfile.app

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.core.share.FileOpener
import com.vvfile.app.data.prefs.PrefsRepository
import com.vvfile.app.data.prefs.Settings
import com.vvfile.app.data.prefs.ThemeMode
import com.vvfile.app.feature.apps.AppListScreen
import com.vvfile.app.feature.browser.BrowserScreen
import com.vvfile.app.feature.editor.EditorScreen
import com.vvfile.app.feature.settings.SettingsScreen
import com.vvfile.app.feature.terminal.TerminalScreen
import com.vvfile.app.ui.theme.AccentPresets
import com.vvfile.app.ui.theme.VVFileTheme

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

                Box(Modifier.fillMaxSize()) {
                    BrowserScreen(
                        onOpenFile = { path ->
                            if (MimeTypes.isText(path)) {
                                editingPath = path
                            } else if (!FileOpener.open(this@MainActivity, path)) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "没有可打开该文件的应用",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onToolClick = { tool ->
                            when (tool) {
                                "设置" -> showSettings = true
                                "终端模拟器" -> showTerminal = true
                                "已安装应用" -> showApps = true
                                "文本编辑器" -> Toast.makeText(
                                    this@MainActivity,
                                    "从文件列表点击文本文件即可编辑",
                                    Toast.LENGTH_SHORT,
                                ).show()
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
                        )
                    }
                }
            }
        }
    }
}
