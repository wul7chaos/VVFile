package com.vvfile.app.feature.browser

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vvfile.app.data.model.FileEntry

/** 长按菜单的目标：条目 + 它所在的窗格（决定复制/移动的方向文案）。 */
data class FileActionTarget(val entry: FileEntry, val pane: PaneId)

/**
 * 长按文件弹出的操作菜单，浮在画面正中（对齐 MT）。
 *
 * 复制/移动的方向文案随窗格变化：左窗格条目是「复制 ->」「移动 ->」，
 * 右窗格条目是「<- 复制」「<- 移动」。
 * 压缩包（zip/apk）多出「解压」「解压到…」。
 */
@Composable
fun FileActionMenu(
    target: FileActionTarget,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onCompress: () -> Unit,
    onUnzip: () -> Unit,
    onUnzipTo: () -> Unit,
    onProperties: () -> Unit,
    onShare: () -> Unit,
    onChmod: () -> Unit,
    onOpenWith: () -> Unit,
) {
    val entry = target.entry
    val pane = target.pane
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var toolsOpen by remember { mutableStateOf(false) }

    val isArchive = !entry.isDirectory && entry.extension in ARCHIVE_EXTENSIONS
    val copyLabel = if (pane == PaneId.LEFT) "复制 ->" else "<- 复制"
    val moveLabel = if (pane == PaneId.LEFT) "移动 ->" else "<- 移动"

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.heightIn(max = 520.dp)) {
                Text(
                    text = entry.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (toolsOpen) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MenuRow(
                            icon = Icons.Outlined.Lock,
                            label = "修改权限",
                            onClick = {
                                onDismiss()
                                onChmod()
                            },
                        )
                        MenuRow(
                            icon = Icons.Outlined.ContentCopy,
                            label = "复制路径",
                            onClick = {
                                clipboard.setText(AnnotatedString(entry.path))
                                Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                        )
                        MenuRow(
                            icon = Icons.Outlined.Build,
                            label = "返回",
                            onClick = { toolsOpen = false },
                        )
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val actions = buildList {
                            add(MenuAction(copyLabel, Icons.Outlined.ContentCopy) { onCopy() })
                            add(
                                MenuAction(
                                    moveLabel,
                                    Icons.AutoMirrored.Outlined.DriveFileMove,
                                ) { onMove() },
                            )
                            add(MenuAction("删除", Icons.Outlined.DeleteOutline, dangerous = true) { onDelete() })
                            add(MenuAction("重命名", Icons.Outlined.Edit) { onRename() })
                            add(MenuAction("工具", Icons.Outlined.Build) { toolsOpen = true })
                            add(MenuAction("压缩", Icons.Outlined.Archive) { onCompress() })
                            add(MenuAction("属性", Icons.Outlined.Info) { onProperties() })
                            add(MenuAction("分享", Icons.Outlined.Share) { onShare() })
                            if (isArchive) {
                                add(MenuAction("解压", Icons.Outlined.Unarchive) { onUnzip() })
                                add(MenuAction("解压到…", Icons.Outlined.FolderOpen) { onUnzipTo() })
                            }
                            add(MenuAction("打开方式…", Icons.Outlined.OpenInNew) { onOpenWith() })
                        }
                        actions.chunked(2).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { action ->
                                    MenuCell(
                                        action = action,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            onDismiss()
                                            action.onClick()
                                        },
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private class MenuAction(
    val label: String,
    val icon: ImageVector,
    val dangerous: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun MenuCell(
    action: MenuAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = if (action.dangerous) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = action.label,
            fontSize = 14.sp,
            color = if (action.dangerous) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "apk")
