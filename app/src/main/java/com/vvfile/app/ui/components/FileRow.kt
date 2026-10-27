package com.vvfile.app.ui.components
import androidx.compose.ui.graphics.RectangleShape

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.core.format.TimeFormatter
import com.vvfile.app.core.thumb.Thumbnails
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.ui.theme.FileTintApk
import com.vvfile.app.ui.theme.FileTintArchive
import com.vvfile.app.ui.theme.FileTintAudio
import com.vvfile.app.ui.theme.FileTintDefault
import com.vvfile.app.ui.theme.FileTintImage
import com.vvfile.app.ui.theme.FileTintText
import com.vvfile.app.ui.theme.FileTintVideo
import com.vvfile.app.ui.theme.VVFileTheme
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    onSwipeSelect: () -> Unit = {},
    showDetails: Boolean = false,
    showThumbnail: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    var dragTotal by remember(entry.path) { mutableFloatStateOf(0f) }
    var dragOffset by remember(entry.path) { mutableFloatStateOf(0f) }
    var dragging by remember(entry.path) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        // 拖动时跟手；松手后回弹到原位
        animationSpec = if (dragging) {
            snap()
        } else {
            spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "rowSwipeOffset",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .offset { IntOffset(animatedOffset.roundToInt(), 0) }
            .clip(RectangleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .pointerInput(entry.path) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragTotal = 0f
                        dragOffset = 0f
                        dragging = true
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragTotal += amount
                        val maxOffset = 28.dp.toPx()
                        // 0.55 的阻尼，让行跟随手指轻移，而不是整行滑走
                        dragOffset = (dragOffset + amount * 0.55f).coerceIn(-maxOffset, maxOffset)
                    },
                    onDragEnd = {
                        dragging = false
                        if (abs(dragTotal) >= 48.dp.toPx()) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSwipeSelect()
                        }
                        dragOffset = 0f
                        dragTotal = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragOffset = 0f
                        dragTotal = 0f
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { this.selected = isSelected }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileLeading(entry = entry, showThumbnail = showThumbnail)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = metaFor(entry, showDetails),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.isSymlink) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = "符号链接",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * 行首的图标位：图片/视频在缩略图准备好之后换成预览图，
 * 其余类型、以及没加载出来（格式不支持/没有读权限）的一律显示原来的分类图标。
 *
 * 格子固定 32dp、图标居中，缩略图换上来时文件名不会左右抖一下。
 */
@Composable
private fun FileLeading(entry: FileEntry, showThumbnail: Boolean) {
    val isImage = entry.extension in thumbImageExt
    val isVideo = entry.extension in thumbVideoExt
    val thumb = if (showThumbnail && !entry.isDirectory && (isImage || isVideo)) {
        val bitmap by produceState(
            initialValue = Thumbnails.peek(entry.path, entry.size, entry.lastModified),
            entry.path,
            entry.size,
            entry.lastModified,
            isVideo,
        ) {
            // 命中内存缓存就不用等
            if (value != null) return@produceState
            // 快速滑动时行一闪而过，先等一拍再解码，别把整段列表都排进解码队列
            delay(if (isVideo) VIDEO_SETTLE_MS else IMAGE_SETTLE_MS)
            value = Thumbnails.load(entry.path, entry.size, entry.lastModified, isVideo)
        }
        bitmap
    } else {
        null
    }

    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RectangleShape),
            )
        } else {
            Icon(
                imageVector = iconFor(entry),
                contentDescription = if (entry.isDirectory) "文件夹" else "文件",
                tint = tintFor(entry),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 图片解码便宜，稍等一下即可；视频取帧要开解码器，多等一会儿更划算。 */
private const val IMAGE_SETTLE_MS = 45L
private const val VIDEO_SETTLE_MS = 90L

private fun metaFor(entry: FileEntry, showDetails: Boolean): String {
    val time = TimeFormatter.formatShort(entry.lastModified)
    if (showDetails) {
        val permissions = entry.permissions ?: "---------"
        val owner = entry.owner ?: "-"
        return "$permissions $owner · $time"
    }
    return if (entry.isDirectory) "文件夹 · $time" else "${SizeFormatter.format(entry.size)} · $time"
}

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts")
// 能出缩略图的类型：svg 是矢量图，BitmapFactory 解不了，老老实实显示图标
private val thumbImageExt = imageExt - "svg"
private val thumbVideoExt = videoExt
private val audioExt = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "amr")
private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst", "apk", "jar")
private val textExt = setOf(
    "txt", "md", "json", "xml", "yaml", "yml", "log", "ini", "conf", "properties",
    "html", "htm", "css", "js", "ts", "kt", "java", "py", "sh", "c", "h", "cpp",
)

private fun iconFor(entry: FileEntry): ImageVector = when {
    entry.isDirectory -> Icons.Outlined.Folder
    entry.extension in imageExt -> Icons.Outlined.Image
    entry.extension in videoExt -> Icons.Outlined.Movie
    entry.extension in audioExt -> Icons.Outlined.MusicNote
    entry.extension == "apk" -> Icons.Outlined.Android
    entry.extension in archiveExt -> Icons.Outlined.FolderZip
    entry.extension in textExt -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable
private fun tintFor(entry: FileEntry): Color = when {
    entry.isDirectory -> MaterialTheme.colorScheme.primary
    entry.extension in imageExt -> FileTintImage
    entry.extension in videoExt -> FileTintVideo
    entry.extension in audioExt -> FileTintAudio
    entry.extension == "apk" -> FileTintApk
    entry.extension in archiveExt -> FileTintArchive
    entry.extension in textExt -> FileTintText
    else -> FileTintDefault
}

@Preview(showBackground = true, backgroundColor = 0xFF171C24)
@Composable
private fun FileRowPreview() {
    VVFileTheme {
        Column {
            FileRow(
                entry = FileEntry(
                    name = "Download",
                    path = "/sdcard/Download",
                    isDirectory = true,
                    size = 0L,
                    lastModified = 1_700_000_000_000L,
                ),
                onClick = {},
            )
            FileRow(
                entry = FileEntry(
                    name = "note.txt",
                    path = "/sdcard/note.txt",
                    isDirectory = false,
                    size = 1_204L,
                    lastModified = 1_700_000_000_000L,
                ),
                isSelected = true,
                onClick = {},
                onLongClick = {},
            )
        }
    }
}


