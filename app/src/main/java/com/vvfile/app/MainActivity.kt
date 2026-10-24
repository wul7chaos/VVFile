package com.vvfile.app

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.core.share.FileOpener
import com.vvfile.app.feature.browser.BrowserScreen
import com.vvfile.app.feature.editor.EditorScreen
import com.vvfile.app.ui.theme.VVFileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            VVFileTheme {
                var editingPath by remember { mutableStateOf<String?>(null) }
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
                            val message = when (tool) {
                                "文本编辑器" -> "从文件列表点击文本文件即可编辑"
                                "终端模拟器" -> "终端将在下一里程碑接入"
                                else -> "$tool · 后续里程碑接入"
                            }
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                        },
                    )

                    editingPath?.let { path ->
                        EditorScreen(
                            path = path,
                            onClose = { editingPath = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
