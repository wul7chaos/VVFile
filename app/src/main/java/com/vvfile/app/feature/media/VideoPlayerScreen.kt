package com.vvfile.app.feature.media

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.delay

/**
 * 视频播放页：整页黑底 + 自绘控件 + MT 风格的设置面板。
 *
 * 按需求：不驻留通知栏，也没有「定时关闭」；其余选项（铺满方式、横竖屏、
 * 循环/随机/自动下一集、音轨字幕、倍速）都保留。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    path: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val playlist = remember(path) { MediaLibrary.playlist(path) }
    val startIndex = remember(path, playlist) { MediaFormats.indexOf(playlist, path) }

    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
    }

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var sheetOpen by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf<TrackKind?>(null) }
    var title by remember { mutableStateOf(MediaFormats.nameOf(path)) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 显示
    var fillScreen by remember { mutableStateOf(false) }
    var stretch by remember { mutableStateOf(false) }
    var autoRotate by remember { mutableStateOf(true) }
    // 播放
    var loopPlay by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var autoNext by remember { mutableStateOf(true) }
    // 速度
    var speed by remember { mutableFloatStateOf(1f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                playing = p.isPlaying
                buffering = p.playbackState == Player.STATE_BUFFERING
                durationMs = p.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
                positionMs = p.currentPosition.coerceAtLeast(0L)
                p.currentMediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotEmpty() }
                    ?.let { title = it }
                if (p.playbackState == Player.STATE_ENDED) controlsVisible = true
            }

            override fun onPlayerError(error: PlaybackException) {
                errorText = describePlaybackError(error)
                controlsVisible = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, playlist) {
        val items = playlist.map { item ->
            MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(File(item)))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(MediaFormats.nameOf(item))
                        .build(),
                )
                .build()
        }
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare()
        player.play()
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 进度轮询：只在播放中刷新，暂停时由事件驱动
    LaunchedEffect(player, playing) {
        while (playing) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(400)
        }
    }

    // 控件 4 秒后自动隐藏
    LaunchedEffect(controlsVisible, playing, sheetOpen) {
        if (controlsVisible && playing) {
            delay(4000)
            if (!sheetOpen) controlsVisible = false
        }
    }

    LaunchedEffect(loopPlay) {
        player.repeatMode = if (loopPlay) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(shuffle) { player.shuffleModeEnabled = shuffle }
    LaunchedEffect(autoNext) { player.pauseAtEndOfMediaItems = !autoNext }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    // 播放期间跟设备横竖屏走；退出恢复应用原来的方向
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        onDispose {
            activity?.requestedOrientation =
                original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(autoRotate, activity) {
        activity?.requestedOrientation = if (autoRotate) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 全屏沉浸 + 常亮
    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler {
        if (sheetOpen) sheetOpen = false else onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AndroidColor.BLACK.toComposeColor())
            .pointerInput(Unit) {
                detectTapGestures { controlsVisible = !controlsVisible }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setBackgroundColor(AndroidColor.BLACK)
                    this.player = player
                }
            },
            update = { playerView ->
                playerView.player = player
                playerView.resizeMode = when {
                    stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    fillScreen -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            VideoTopBar(title = title, error = errorText, onClose = onClose, onOpenSheet = { sheetOpen = true })
            VideoBottomBar(
                positionMs = positionMs,
                durationMs = durationMs,
                playing = playing,
                buffering = buffering,
                speed = speed,
                onSeek = {
                    player.seekTo(it)
                    positionMs = it
                },
                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                onPrev = { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekToDefaultPosition() },
                onNext = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekToDefaultPosition() },
                onSpeed = { speed = it },
                onSheet = { sheetOpen = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (buffering) {
            Text(
                text = "缓冲中…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (sheetOpen) {
            PlayerSettingsSheet(
                fillScreen = fillScreen,
                stretch = stretch,
                autoRotate = autoRotate,
                loopPlay = loopPlay,
                shuffle = shuffle,
                autoNext = autoNext,
                speed = speed,
                audioTracks = player.audioTrackLabels(),
                subtitleTracks = player.textTrackLabels(),
                onFillScreen = {
                    fillScreen = !fillScreen
                    if (fillScreen) stretch = false
                },
                onStretch = {
                    stretch = !stretch
                    if (stretch) fillScreen = false
                },
                onAutoRotate = { autoRotate = it },
                onLoop = { loopPlay = it },
                onShuffle = { shuffle = it },
                onAutoNext = { autoNext = it },
                onSpeed = { speed = it },
                onPickAudio = { trackDialog = TrackKind.AUDIO },
                onPickSubtitle = { trackDialog = TrackKind.TEXT },
                onDismiss = { sheetOpen = false },
            )
        }

        trackDialog?.let { kind ->
            TrackDialog(
                kind = kind,
                player = player,
                onDismiss = { trackDialog = null },
            )
        }
    }
}

private enum class TrackKind { AUDIO, TEXT }

@OptIn(UnstableApi::class)
@Composable
private fun VideoTopBar(
    title: String,
    error: String?,
    onClose: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = androidx.compose.ui.graphics.Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = androidx.compose.ui.graphics.Color.White,
            )
            if (error != null) {
                Text(
                    text = error,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(onClick = onOpenSheet) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "设置",
                tint = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}

@Composable
private fun VideoBottomBar(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    buffering: Boolean,
    speed: Float,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = (if (dragging) dragValue else positionMs.toFloat()).coerceIn(0f, max)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Slider(
            value = shown,
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
                thumbColor = androidx.compose.ui.graphics.Color.White,
                activeTrackColor = androidx.compose.ui.graphics.Color.White,
                inactiveTrackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = MediaFormats.formatDuration(shown.toLong()),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Text(
                text = " / ${MediaFormats.formatDuration(durationMs)}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    buffering -> "缓冲中"
                    playing -> "播放中"
                    else -> "已暂停"
                },
                fontSize = 10.sp,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = "上一个",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = "下一个",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            Text(
                text = MediaFormats.speedLabel(speed),
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .clickable {
                        val index = VIDEO_SPEEDS.indexOf(speed)
                        onSpeed(VIDEO_SPEEDS[(index + 1) % VIDEO_SPEEDS.size])
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            IconButton(onClick = onSheet) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "设置",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
    }
}

private val VIDEO_SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f, 3f)

/** MT 风格设置面板：显示 / 播放 / 轨道 / 播放速度，去掉「定时关闭」。 */
@Composable
private fun PlayerSettingsSheet(
    fillScreen: Boolean,
    stretch: Boolean,
    autoRotate: Boolean,
    loopPlay: Boolean,
    shuffle: Boolean,
    autoNext: Boolean,
    speed: Float,
    audioTracks: List<String>,
    subtitleTracks: List<String>,
    onFillScreen: () -> Unit,
    onStretch: () -> Unit,
    onAutoRotate: (Boolean) -> Unit,
    onLoop: (Boolean) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onAutoNext: (Boolean) -> Unit,
    onSpeed: (Float) -> Unit,
    onPickAudio: () -> Unit,
    onPickSubtitle: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // 遮罩单独一层，点空白关闭；面板本身不参与点击，避免点面板内部时被关掉
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss),
        )
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                // 吃掉面板内的空白点击，避免穿透到遮罩把面板关掉
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "设置",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                SectionTitle("显示")
                ToggleRow("强制铺满全屏", fillScreen) { onFillScreen() }
                ToggleRow("拉伸以铺满全屏", stretch) { onStretch() }
                ToggleRow("自适应横竖屏", autoRotate, onAutoRotate)

                SectionTitle("播放")
                ToggleRow("开启循环播放", loopPlay, onLoop)
                ToggleRow("开启随机播放", shuffle, onShuffle)
                ToggleRow("自动播放下一集", autoNext, onAutoNext)

                SectionTitle("轨道")
                NavRow("音轨", if (audioTracks.isEmpty()) "无可用音轨" else "${audioTracks.size} 条", onPickAudio)
                NavRow("字幕", if (subtitleTracks.isEmpty()) "无可用字幕" else "${subtitleTracks.size} 条", onPickSubtitle)

                SectionTitle("播放速度")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    VIDEO_SPEEDS.forEach { value ->
                        val selected = kotlin.math.abs(speed - value) < 0.01f
                        Text(
                            text = MediaFormats.speedLabel(value).removeSuffix("x"),
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clickable { onSpeed(value) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(160.dp),
        )
    }
}

@Composable
private fun TrackDialog(
    kind: TrackKind,
    player: ExoPlayer,
    onDismiss: () -> Unit,
) {
    val groups = remember(kind, player) { player.trackGroups(kind) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(Modifier.heightIn(max = 420.dp)) {
                Text(
                    text = if (kind == TrackKind.AUDIO) "音轨" else "字幕",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackRow(label = if (kind == TrackKind.AUDIO) "自动" else "关闭字幕") {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(kind.trackType, true)
                            .build()
                        onDismiss()
                    }
                    groups.forEach { (index, label) ->
                        TrackRow(label = label) {
                            val override = player.trackOverride(kind, index)
                            if (override != null) {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(kind.trackType, false)
                                    .setOverrideForType(override)
                                    .build()
                            }
                            onDismiss()
                        }
                    }
                    if (groups.isEmpty()) {
                        Text(
                            text = "没有可切换的轨道",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val TrackKind.trackType: Int
    get() = if (this == TrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT

@OptIn(UnstableApi::class)
private fun ExoPlayer.trackGroups(kind: TrackKind): List<Pair<Int, String>> {
    val groups = currentTracks.groups.filter { it.type == kind.trackType }
    return groups.mapIndexed { index, group ->
        val format = group.getTrackFormat(0)
        val label = format.label
            ?: format.language?.let { "语言 $it" }
            ?: format.sampleMimeType
            ?: "轨道 ${index + 1}"
        index to label
    }
}

private fun ExoPlayer.audioTrackLabels(): List<String> = trackGroups(TrackKind.AUDIO).map { it.second }

private fun ExoPlayer.textTrackLabels(): List<String> = trackGroups(TrackKind.TEXT).map { it.second }

@OptIn(UnstableApi::class)
private fun ExoPlayer.trackOverride(kind: TrackKind, index: Int): TrackSelectionOverride? {
    val groups = currentTracks.groups.filter { it.type == kind.trackType }
    val group = groups.getOrNull(index) ?: return null
    return TrackSelectionOverride(group.mediaTrackGroup, 0)
}

private fun describePlaybackError(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或已被移动"
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取该文件的权限"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> "解码失败，格式不受支持"
    else -> "播放失败：${error.errorCodeName}"
}

private fun Int.toComposeColor(): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(this)

private fun Context.findActivity(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

