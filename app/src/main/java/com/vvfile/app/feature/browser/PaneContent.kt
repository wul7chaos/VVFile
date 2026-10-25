package com.vvfile.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.R
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.ui.components.FileRow

/**
 * 单个窗格：列表 + 加载/空/错误态。
 *
 * 列表滚动位置由调用方持有（每窗格一份 LazyListState），路径变化时由调用方回到顶部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneContent(
    pane: PaneState,
    active: Boolean,
    listState: LazyListState,
    onActivate: () -> Unit,
    onOpenDir: (String) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onToggleSelect: (FileEntry) -> Unit,
    onRetry: () -> Unit,
    onNewFolder: () -> Unit,
    onPullRefresh: () -> Unit,
    showDetails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val visibleEntries = pane.visibleEntries
    Column(
        modifier = modifier
            .fillMaxHeight()
            // 选中窗格用更深的底色，未选中用浅一档：反过来的话深色主题下「选中」反而更亮
            .background(
                if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface,
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

        if (pane.entries.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${visibleEntries.count { it.isDirectory }} 个文件夹 · " +
                        "${visibleEntries.count { !it.isDirectory }} 个文件",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pane.filterActive) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "已过滤",
                        fontSize = 10.sp,
                        color = accent,
                    )
                }
            }
        }

        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pane.refreshing,
            onRefresh = onPullRefresh,
            state = pullState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            indicator = {
                val fraction = pullState.distanceFraction.coerceIn(0f, 1f)
                if (pane.refreshing || fraction > 0.02f) {
                    PullHint(
                        fraction = fraction,
                        refreshing = pane.refreshing,
                        accent = accent,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                when {
                    pane.error != null -> ErrorState(pane.error, onRetry)
                    pane.loading && pane.entries.isEmpty() -> LoadingState()
                    pane.entries.isEmpty() -> EmptyState(onNewFolder)
                    visibleEntries.isEmpty() -> FilterEmptyState()
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        items(visibleEntries, key = { it.path }) { entry ->
                            FileRow(
                                entry = entry,
                                isSelected = entry.path in pane.selected,
                                showDetails = showDetails,
                                onClick = {
                                    if (pane.selectionMode) {
                                        onToggleSelect(entry)
                                    } else if (entry.isDirectory) {
                                        onOpenDir(entry.path)
                                    } else {
                                        onOpenFile(entry)
                                    }
                                },
                                onLongClick = { onToggleSelect(entry) },
                                onSwipeSelect = { onToggleSelect(entry) },
                            )
                        }
                    }
                }
                // 下拉刷新自己会画顶部细线，别叠两条
                if (pane.loading && pane.entries.isNotEmpty() && !pane.refreshing) {
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
}

/**
 * 下拉过程中的小提示（模仿 MT：拖到阈值前提示「下拉刷新」，越过后提示「释放刷新」）。
 *
 * [fraction] 是 0..1 的拉动比例，箭头随比例旋转，整块提示也跟着手指下移一点，
 * 松开后由 [refreshing] 接管，显示「刷新中…」和一条矩形进度条。
 */
@Composable
private fun PullHint(
    fraction: Float,
    refreshing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val armed = fraction >= 1f
    val shift = if (refreshing) 0f else fraction * 22f
    Row(
        modifier = modifier
            .graphicsLayer { translationY = shift.dp.toPx() }
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .width(26.dp)
                    .height(2.dp),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surface,
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = null,
                tint = accent,
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = if (armed) 180f else fraction * 160f },
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = when {
                refreshing -> "刷新中…"
                armed -> "释放刷新"
                else -> "下拉刷新"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingState() {    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.ui_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        repeat(6) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(vertical = 2.dp)
                    .clip(RectangleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun FilterEmptyState() {
    // 空/错状态也套一层可滚动容器，否则下拉刷新没有可消费的滚动事件
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "没有匹配的条目",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(onNewFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RectangleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ui_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.ui_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(
            onClick = onNewFolder,
            shape = RectangleShape,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_empty_new_folder))
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 18.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RectangleShape)
                    .background(error.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.ui_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.ui_retry))
            }
        }
    }
}


