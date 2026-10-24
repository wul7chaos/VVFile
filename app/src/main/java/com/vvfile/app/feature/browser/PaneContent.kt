package com.vvfile.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.ui.components.FileRow

/** 单个窗格：路径头 + 列表 + 加载/空/错误态。 */
@Composable
fun PaneContent(
    pane: PaneState,
    active: Boolean,
    onActivate: () -> Unit,
    onOpenDir: (String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(
                if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onActivate,
            ),
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
                .height(34.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = pane.path.ifEmpty { "…" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${pane.entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                pane.error != null -> ErrorState(pane.error, onRetry)
                pane.loading && pane.entries.isEmpty() -> SkeletonList()
                pane.entries.isEmpty() -> EmptyState()
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    items(pane.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) onOpenDir(entry.path) else onOpenFile(entry)
                            },
                        )
                    }
                }
            }
            if (pane.loading && pane.entries.isNotEmpty()) {
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
private fun SkeletonList() {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        repeat(7) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "此文件夹为空",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxHeight().fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
