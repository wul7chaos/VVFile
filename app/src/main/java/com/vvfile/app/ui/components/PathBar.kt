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
import kotlinx.coroutines.flow.firstOrNull

data class PathSegment(val label: String, val fullPath: String)

/** 面包屑：根目录 + 逐级路径，超长横向滚动。 */
@Composable
fun PathBar(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(path) { buildSegments(path) }
    val scrollState = rememberScrollState()
    LaunchedEffect(path) {
        // 路径变化后滚到最右，保证当前目录可见
        val target = snapshotFlow { scrollState.maxValue }.firstOrNull { it > 0 }
        if (target != null) scrollState.scrollTo(target)
    }
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            val isLast = index == segments.lastIndex
            Text(
                text = segment.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onNavigate(segment.fullPath) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
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
