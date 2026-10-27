package com.vvfile.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.R
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.feature.archive.ArchiveEntryInfo
import com.vvfile.app.ui.components.FileRow

/**
 * 窗格里的压缩包浏览：把包内条目铺成和目录一样的列表，不再另开一页。
 *
 * 布局跟着 [PaneContent] 走（顶部选中线、统计行、列表），区别只有数据来源与
 * 「点条目 ≈ 解出来再打开」。这一层不碰文件系统，解压与密码都在 ViewModel 里。
 */
@Composable
fun ArchivePaneView(
    archive: ArchiveView,
    active: Boolean,
    showDetails: Boolean,
    onActivate: () -> Unit,
    onEntryClick: (ArchiveEntryInfo) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val visible = archive.visibleEntries
    val listState = rememberLazyListState()
    val encryptedCount = archive.entries?.count { it.encrypted } ?: 0

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(
                if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
            )
            .pointerInput(active) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!active) onActivate()
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) accent else Color.Transparent),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append("${visible.count { it.isDirectory }} 个文件夹")
                    append(" · ${visible.count { !it.isDirectory }} 个文件")
                    when {
                        archive.extracting != null -> append(" · 正在解开")
                        archive.unlocked -> append(" · 已解锁")
                        encryptedCount > 0 -> append(" · 含加密条目")
                    }
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (archive.loading && archive.entries != null) {
                Text(
                    text = "正在刷新…",
                    fontSize = 10.sp,
                    color = accent,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                archive.error != null -> ArchiveErrorState(archive.error, onRetry)
                archive.entries == null -> ArchiveLoadingState()
                visible.isEmpty() -> ArchiveEmptyState()
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    items(visible, key = { it.name }) { entry ->
                        FileRow(
                            entry = FileEntry(
                                // 加密条目在名字前挂把锁：一眼看出哪些要密码
                                name = if (entry.encrypted) "🔒 ${entry.displayName}" else entry.displayName,
                                path = entry.name,
                                isDirectory = entry.isDirectory,
                                size = entry.size,
                                lastModified = entry.modifiedAt,
                            ),
                            isSelected = false,
                            showDetails = showDetails,
                            // 包内条目还没落盘，没有可解码的源文件，别去试缩略图
                            showThumbnail = false,
                            onClick = { onEntryClick(entry) },
                        )
                    }
                }
            }
            if (archive.loading && archive.entries != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArchiveLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "正在读取压缩包…",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        repeat(5) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(13.dp)
                    .padding(vertical = 3.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(9.dp))
        }
    }
}

@Composable
private fun ArchiveEmptyState() {
    Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderZip,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ui_empty_title),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.ui_empty_subtitle),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchiveErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onRetry) { Text("重试", fontSize = 12.sp) }
    }
}
