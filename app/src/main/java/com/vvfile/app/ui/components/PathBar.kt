package com.vvfile.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class PathSegment(val label: String, val fullPath: String)

/**
 * 面包屑：只用于**显示**当前位置，整条点一下就打开路径编辑面板。
 *
 * 以前每一段都可点、点哪段跳哪段；但深层路径下小字很容易点错，而且想直接打一串路径
 * 也没地方输入。现在整条只有一个点击区：点开「跳转」面板改路径（带候选），
 * 长按仍然复制完整路径。上层目录用底部的「上级」按钮走。
 */
@Composable
fun PathBar(
    path: String,
    onEditPath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(path) { buildSegments(path) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(path) {
        // 持续跟随可滚动余量变化：布局完成后再滚到尾部，保证当前目录完整可见
        snapshotFlow { scrollState.maxValue }.collect { max ->
            if (max > 0) scrollState.scrollTo(max)
        }
    }
    Row(
        modifier = modifier
            .semantics { contentDescription = "当前路径，点按编辑" }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onEditPath() },
                    onLongPress = {
                        clipboard.setText(AnnotatedString(path))
                        Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                    },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 根入口不参与滚动，任何深度下都能看到路径是从哪开始的
        PathLabel(label = "/", isCurrent = segments.size == 1)
        if (segments.size > 1) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    segments.drop(1).forEachIndexed { index, segment ->
                        if (index > 0) {
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        PathLabel(
                            label = segment.label,
                            isCurrent = index == segments.size - 2,
                        )
                    }
                }
                if (scrollState.value > 0) {
                    // 左侧滚出的层级用渐隐遮罩，避免露出半截文字
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(32.dp)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background,
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PathLabel(label: String, isCurrent: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp),
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
