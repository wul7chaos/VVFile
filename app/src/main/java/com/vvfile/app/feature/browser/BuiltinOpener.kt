package com.vvfile.app.feature.browser

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 「打开方式」里 VVFile 自己的打开器。
 *
 * 与 [com.vvfile.app.feature.browser.BrowserScreen] 的默认路由不同：默认路由按扩展名猜
 * （mp4 → 播放器、txt → 编辑器），这里是你**指定**用哪一个，哪怕扩展名对不上也照开——
 * 比如把一个 `.bin` 丢给文本编辑器看看到底是不是文本。
 *
 * 只保留文本 / 图片 / 音乐 / 视频 / 压缩包五种。MT 那套十六进制、xml、dex、arsc、
 * apk、apks、字体、导入签名、插件信息不在本项目范围内。
 */
enum class BuiltinOpener(
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    TEXT("编辑文本", "内置编辑器", Icons.Outlined.Edit),
    IMAGE("查看图片", "支持缩放", Icons.Outlined.Image),
    AUDIO("播放音乐", "后台播放", Icons.Outlined.MusicNote),
    VIDEO("播放视频", "倍速字幕", Icons.Outlined.Movie),
    ARCHIVE("浏览压缩包", "包内直接看", Icons.Outlined.FolderZip),
}
