package com.vvfile.app.feature.browser

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.core.share.FileSharer

/**
 * 「打开方式」：内置（编辑文本）+ 系统里能处理该类型的应用。
 *
 * 按需求不做十六进制 / xml / dex / arsc / apk / apks / 查看字体 / 导入签名 /
 * 插件信息 / .9 编辑 / jar / class / 对比结果这些内置查看器。
 */
@Composable
fun OpenWithDialog(
    path: String,
    fileName: String,
    onEditText: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val targets = remember(path) { FileSharer.resolveOpenWith(context, path) }
    val mime = remember(path) { MimeTypes.forPath(path) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.heightIn(max = 520.dp)) {
                Text(
                    text = "打开方式",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                Text(
                    text = fileName,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (onEditText != null) {
                        OpenWithRow(
                            label = "编辑文本",
                            subtitle = "内置编辑器",
                            icon = { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                onDismiss()
                                onEditText()
                            },
                        )
                    }
                    targets.forEach { target ->
                        OpenWithRow(
                            label = target.label,
                            subtitle = target.packageName,
                            icon = {
                                val drawable = target.icon
                                if (drawable != null) {
                                    Image(
                                        bitmap = remember(target.packageName) {
                                            drawable.toBitmap(
                                                width = 96,
                                                height = 96,
                                            ).asImageBitmap()
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.InsertDriveFile,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                val ok = FileSharer.openWith(context, path, target.packageName)
                                if (ok) {
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "无法用 ${target.label} 打开", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            },
                        )
                    }
                    if (targets.isEmpty() && onEditText == null) {
                        Text(
                            text = "没有可用的打开方式（$mime）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "类型：$mime",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun OpenWithRow(
    label: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
