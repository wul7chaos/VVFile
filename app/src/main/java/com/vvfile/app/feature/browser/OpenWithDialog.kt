package com.vvfile.app.feature.browser

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.vvfile.app.core.format.MimeTypes
import com.vvfile.app.core.share.FileSharer

/**
 * 「打开方式」：上半排是 VVFile 自己的打开器（强制用某一种方式打开），
 * 下半部分是系统里能处理该类型的应用。
 *
 * 排成网格是为了和 MT 的操作习惯对齐：不用管扩展名，点哪个就用哪个看。
 */
@Composable
fun OpenWithDialog(
    path: String,
    fileName: String,
    onOpenBuiltin: (BuiltinOpener) -> Unit,
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
            Column(Modifier.heightIn(max = 560.dp)) {
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
                    // 内置打开器：固定五项，点哪个就强制用哪个，扩展名对不上也照开
                    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
                        BuiltinOpener.entries.chunked(BUILTIN_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { opener ->
                                    BuiltinOpenerTile(
                                        opener = opener,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            onDismiss()
                                            onOpenBuiltin(opener)
                                        },
                                    )
                                }
                                // 末行不满时补空位，避免图标被拉宽
                                repeat(BUILTIN_COLUMNS - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    if (targets.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = "其他应用",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
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
                                        Toast.makeText(
                                            context,
                                            "无法用 ${target.label} 打开",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
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

/** 内置打开器的一格：圆角色块 + 图标 + 名称，风格对齐 MT 的插件网格。 */
@Composable
private fun BuiltinOpenerTile(
    opener: BuiltinOpener,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = opener.icon,
                contentDescription = opener.label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = opener.label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = opener.subtitle,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 内置打开器每行格数：五项排成 4 + 1。 */
private const val BUILTIN_COLUMNS = 4

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
