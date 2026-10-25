package com.vvfile.app.feature.apps

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.data.app.AbiArch
import com.vvfile.app.data.app.AppDetails
import com.vvfile.app.data.app.abiSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「应用信息」弹窗，字段参照 MT 管理器的属性页。
 *
 * 刻意不做签名状态 / 加固状态 / UID：这三项要么得解析 APK 签名块，要么得读内核 uid 表，
 * 与应用本身「文件管理」的定位不符。
 *
 * 路径类字段一律单行 + 横向滚动，避免长包名把整行撑爆。
 */
@Composable
fun AppDetailsDialog(
    details: AppDetails,
    onDismiss: () -> Unit,
    onMore: () -> Unit,
    onExtract: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = details.icon
                if (icon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(44.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = details.label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${details.versionText} · 本机 ${deviceAbi()}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Column(Modifier.padding(vertical = 8.dp)) {
                DetailRow("包名", details.packageName)
                DetailRow("版本号", details.versionCode.toString())
                DetailRow("安装包大小", SizeFormatter.format(details.apkSizeBytes))
                DetailRow(
                    label = "目标架构",
                    value = if (details.pureJava) {
                        "纯 Java/Kotlin（无原生库）"
                    } else {
                        details.nativeAbis.joinToString(", ")
                    },
                    scrollable = false,
                )
                DetailRow("ARM 运行", abiSummary(details, AbiArch.ARM))
                DetailRow("x86 运行", abiSummary(details, AbiArch.X86))
                details.dataDir?.takeIf { it.isNotBlank() }?.let { DetailRow("数据目录", it) }
                details.externalDataDir?.takeIf { it.isNotBlank() }?.let {
                    DetailRow("数据目录 2", it)
                }
                if (details.apkPath.isNotBlank()) DetailRow("APK 路径", details.apkPath)
                if (details.firstInstallTime > 0L) {
                    DetailRow("首次安装", formatTime(details.firstInstallTime))
                }
                if (details.lastUpdateTime > 0L) {
                    DetailRow("最后更新", formatTime(details.lastUpdateTime))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onMore) { Text("更多", fontSize = 13.sp) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onExtract) { Text("提取安装包", fontSize = 13.sp) }
            }
        }
    }
}

/** 一行「标签 + 值」；[scrollable] 为 true 时单行横向滚动，否则按需换行。 */
@Composable
private fun DetailRow(label: String, value: String, scrollable: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(84.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (scrollable) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = value,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochMillis))

/** 当前设备首选 ABI，用来对照「目标架构」判断是否要转译。 */
private fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"
