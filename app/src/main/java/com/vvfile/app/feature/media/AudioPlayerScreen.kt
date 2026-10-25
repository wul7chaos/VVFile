package com.vvfile.app.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 音频播放面板（对齐 MT 的音频播放页，但按需求调整）：
 * - 右上角是「最小化」「关闭」，没有「定时关闭」
 * - 底部 循环 / 上一首 / 播放暂停 / 下一首 / 列表，另加播放速度
 * - 播放状态留在通知栏，最小化后浏览器里挂一条迷你条
 */
@Composable
fun AudioPlayerPanel(
    path: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val state by AudioEngine.state.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        AudioEngine.connect(context)
        // 从迷你条恢复时不要从头重播
        if (AudioEngine.state.value.path != path) {
            AudioEngine.play(context, path)
        }
    }

    Surface(
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.title.ifEmpty { MediaFormats.nameOf(path) },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onMinimize) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "最小化",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    AudioEngine.stop()
                    onClose()
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(0.6f))

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(88.dp),
                    )
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    text = state.title.ifEmpty { MediaFormats.nameOf(path) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.error ?: state.subtitle,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (state.count > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${state.index + 1} / ${state.count}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.weight(0.5f))

                SeekSection(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek = AudioEngine::seekTo,
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = AudioEngine::cyclePlayMode) {
                        Icon(
                            imageVector = when {
                                state.shuffle -> Icons.Outlined.Shuffle
                                state.repeatMode == MediaFormats.REPEAT_ONE -> Icons.Outlined.RepeatOne
                                else -> Icons.Outlined.Repeat
                            },
                            contentDescription = if (state.shuffle) {
                                "随机播放（同目录）"
                            } else {
                                MediaFormats.repeatLabel(state.repeatMode)
                            },
                            tint = if (state.shuffle || state.repeatMode != MediaFormats.REPEAT_OFF) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(onClick = AudioEngine::previous) {
                        Icon(
                            imageVector = Icons.Outlined.SkipPrevious,
                            contentDescription = "上一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable(onClick = AudioEngine::togglePlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (state.playing) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    IconButton(onClick = AudioEngine::next) {
                        Icon(
                            imageVector = Icons.Outlined.SkipNext,
                            contentDescription = "下一首",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                            contentDescription = "播放列表",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                SpeedRow(
                    speed = state.speed,
                    onSpeed = AudioEngine::setSpeed,
                )

                Spacer(Modifier.weight(0.6f))
            }
        }
    }

    if (showQueue) {
        AudioQueueDialog(
            queue = state.queue,
            currentIndex = state.index,
            onPick = {
                AudioEngine.jumpTo(it)
                showQueue = false
            },
            onDismiss = { showQueue = false },
        )
    }
}

/** 最小化后挂在浏览器底部的迷你条。 */
@Composable
fun AudioMiniBar(
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by AudioEngine.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onExpand)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.title.ifEmpty { "未在播放" },
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (state.playing) "正在播放" else "已暂停",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = AudioEngine::togglePlayPause) {
                Icon(
                    imageVector = if (state.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (state.playing) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onExpand) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                AudioEngine.stop()
                onClose()
            }) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeekSection(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging) dragValue else positionMs.toFloat()

    Slider(
        value = shown.coerceIn(0f, max),
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            onSeek(dragValue.toLong())
            dragging = false
        },
        valueRange = 0f..max,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = MediaFormats.formatDuration(shown.toLong()),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = MediaFormats.formatDuration(durationMs),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun SpeedRow(speed: Float, onSpeed: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SPEEDS.forEach { value ->
            val selected = kotlin.math.abs(speed - value) < 0.01f
            Text(
                text = MediaFormats.speedLabel(value),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable { onSpeed(value) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun AudioQueueDialog(
    queue: List<String>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.heightIn(max = 460.dp)) {
                Text(
                    text = "播放列表 · ${queue.size}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    queue.forEachIndexed { index, path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .background(
                                    if (index == currentIndex) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                )
                                .clickable { onPick(index) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (index == currentIndex) "▶" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(18.dp),
                            )
                            Text(
                                text = MediaFormats.nameOf(path),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
