package com.vvfile.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect

data class PathSegment(val label: String, val fullPath: String)

/**
 * 面包屑：根目录固定在最左侧（始终可点），其余层级横向滚动。
 * 路径变化后滚动到尾部，保证当前目录可见；深层路径也不会把「/」挤出可视区。
 */
@Composable
fun PathBar(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(path) { buildSegments(path) }
    val scrollState = rememberScrollState()
    LaunchedEffect(path) {
        // 持续跟随可滚动余量变化：布局完成后再滚到尾部，保证当前目录完整可见
        snapshotFlow { scrollState.maxValue }.collect { max ->
            if (max > 0) scrollState.scrollTo(max)
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 根入口不参与滚动，任何深度下都能一键回 "/"
        PathLabel(
            label = "/",
            isCurrent = segments.size == 1,
            onClick = { onNavigate("/") },
        )
        if (segments.size > 1) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                segments.drop(1).forEachIndexed { index, segment ->
                    if (index > 0) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    PathLabel(
                        label = segment.label,
                        isCurrent = index == segments.size - 2,
                        onClick = { onNavigate(segment.fullPath) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PathLabel(
    label: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

fun buildSegments(path: String): List<PathSegment> {
    val normalized = path.trimEnd('/').ifEmpty { "/" }
    if (normalized == "/") return listOf(PathSegment("/", "/"))
    val result = mutableListOf(PathSegment("/", "/"))
    var acc = ""
    normalized.split('/').filter { it.isNotEmpty() }.forEach { part ->
        acc += "/$part"
        result += PathSegment(part, acc)
    }
    return result
}
