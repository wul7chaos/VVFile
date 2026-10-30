package com.vvfile.app.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.core.install.InstallResult
import com.vvfile.app.ui.theme.VVWarn

/**
 * 点开 APK 时先问一句：装 / 看看包里有什么 / 换别的应用打开。
 *
 * 以前 apk 和 zip 一样直接进包内浏览，想装还得长按找入口；这里把「安装」放到第一下就能点到的地方。
 */
@Composable
fun ApkActionDialog(
    fileName: String,
    rootAvailable: Boolean,
    onInstall: () -> Unit,
    onBrowse: () -> Unit,
    onOpenWith: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Column {
                Text(
                    text = fileName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (rootAvailable) {
                        "将用 ROOT 权限静默安装，不经过系统安装器"
                    } else {
                        "将调用系统安装器（ROOT 不可用时只能这样装）"
                    },
                    fontSize = 12.sp,
                    color = if (rootAvailable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        VVWarn
                    },
                )
            }
        },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onBrowse) { Text("浏览包内", fontSize = 13.sp) }
                TextButton(onClick = onOpenWith) { Text("打开方式", fontSize = 13.sp) }
                TextButton(onClick = onInstall) { Text("安装", fontSize = 13.sp) }
            }
        },
        confirmButton = {},
    )
}

/** 安装结果：成功/失败一句话 + `pm` 的原始输出。 */
@Composable
fun InstallResultDialog(
    fileName: String,
    result: InstallResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Column {
                Text(text = fileName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = result.message,
                    fontSize = 11.sp,
                    color = if (result.success) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 320.dp)) {
                Text(
                    text = result.raw.ifBlank { "（无输出）" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
        },
    )
}
