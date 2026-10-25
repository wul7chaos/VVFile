package com.vvfile.app.feature.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.ImageDecoder
import android.os.Build
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vvfile.app.core.format.SizeFormatter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片查看器：同目录图片左右滑动切换，双指缩放 / 双击放大 / 拖动平移。
 *
 * 列表与播放器共用 [MediaLibrary] 的同目录排序，所以看图顺序和文件列表一致。
 * GIF 在 Android 9+ 用系统 AnimatedImageDrawable 直接播放动画。
 */
@Composable
fun ImageViewerScreen(
    path: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivityOrNull() }
    val view = LocalView.current
    val playlist = remember(path) { MediaLibrary.playlist(path) }
    val startIndex = remember(path, playlist) { MediaFormats.indexOf(playlist, path) }
    val pagerState = rememberPagerState(initialPage = startIndex) { playlist.size }
    var zoomed by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    val currentPath = playlist.getOrElse(pagerState.currentPage) { path }

    DisposableEffect(activity, view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
        ) { page ->
            ZoomableImage(
                path = playlist.getOrElse(page) { path },
                onZoomChange = { zoomed = it },
                onTap = { barsVisible = !barsVisible },
            )
        }

        if (barsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
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
                        text = MediaFormats.nameOf(currentPath),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                    Text(
                        text = buildString {
                            append(MediaFormats.parentOf(currentPath))
                            append(" · ")
                            append(SizeFormatter.format(runCatching { File(currentPath).length() }.getOrDefault(0L)))
                        },
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                    )
                }
                if (playlist.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${playlist.size}",
                        fontSize = 12.sp,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    path: String,
    onZoomChange: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    var image by remember(path) { mutableStateOf<LoadedImage?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var container by remember(path) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) { ImageLoader.load(path) }
        image = loaded
        failed = loaded == null
    }

    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }

    fun clamp(value: Offset, currentScale: Float): Offset {
        val maxX = container.width * (currentScale - 1f) / 2f
        val maxY = container.height * (currentScale - 1f) / 2f
        return Offset(
            value.x.coerceIn(-maxX.coerceAtLeast(0f), maxX.coerceAtLeast(0f)),
            value.y.coerceIn(-maxY.coerceAtLeast(0f), maxY.coerceAtLeast(0f)),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            .pointerInput(path) {
                // 只在「双指」或「已放大」时消费手势；否则把横向滑动让给 Pager 翻页
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            scale = next
                            offset = if (next <= 1f) {
                                Offset.Zero
                            } else {
                                clamp(offset + event.calculatePan(), next)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1.01f) {
                            offset = clamp(offset + event.calculatePan(), scale)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            offset = Offset.Zero
                            scale = 1f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        when {
            loaded != null -> AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.setImageDrawable(loaded.drawable)
                    (loaded.drawable as? AnimatedImageDrawable)?.start()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            failed -> Text(
                text = "无法解码这张图片",
                fontSize = 13.sp,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
            )
            else -> Text(
                text = "加载中…",
                fontSize = 12.sp,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

private class LoadedImage(
    val drawable: Drawable,
    val width: Int,
    val height: Int,
)

/**
 * 解码到接近屏幕的尺寸即可：超大图（4000px+）按 2 的幂降采样，避免 OOM。
 */
private object ImageLoader {

    private const val MAX_SIDE = 2048

    fun load(path: String): LoadedImage? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(File(path))
            var width = 0
            var height = 0
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = sampleSize(info.size.width, info.size.height)
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }
            LoadedImage(drawable, width, height)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
            LoadedImage(
                android.graphics.drawable.BitmapDrawable(null, bitmap),
                bitmap.width,
                bitmap.height,
            )
        }
    }.getOrNull()

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_SIDE || height / sample > MAX_SIDE) sample *= 2
        return sample
    }
}

private fun Context.findActivityOrNull(): ComponentActivity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
