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
import androidx.compose.material.icons.outlined.Link
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.R
import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.core.format.TimeFormatter
import com.vvfile.app.core.thumb.Thumbnails
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.data.model.FileKind
import com.vvfile.app.data.model.IMAGE_EXTENSIONS
import com.vvfile.app.data.model.VIDEO_EXTENSIONS
import com.vvfile.app.data.model.kindOf
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
                painter = painterResource(iconFor(entry)),
                contentDescription = if (entry.isDirectory) "文件夹" else "文件",
                // 图标自带配色，不能被主题色染色
                tint = Color.Unspecified,
                modifier = Modifier.size(28.dp),
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

// 能出缩略图的类型：svg 是矢量图、ico/tiff 是少见格式，BitmapFactory 解不干净，老实显示图标
private val thumbImageExt = IMAGE_EXTENSIONS - setOf("svg", "ico", "tif", "tiff")
private val thumbVideoExt = VIDEO_EXTENSIONS

private fun iconFor(entry: FileEntry): Int = when (kindOf(entry)) {
    FileKind.Folder -> R.drawable.vvfile_ic_folder
    FileKind.Image -> R.drawable.vvfile_ic_image
    FileKind.Video -> R.drawable.vvfile_ic_video
    FileKind.Audio -> R.drawable.vvfile_ic_audio
    FileKind.Apk -> R.drawable.vvfile_ic_apk
    FileKind.Archive -> R.drawable.vvfile_ic_archive
    FileKind.Text -> R.drawable.vvfile_ic_text
    FileKind.Pdf -> R.drawable.vvfile_ic_pdf
    FileKind.Doc -> R.drawable.vvfile_ic_doc
    FileKind.Sheet -> R.drawable.vvfile_ic_sheet
    FileKind.Slides -> R.drawable.vvfile_ic_slides
    FileKind.Code -> R.drawable.vvfile_ic_code
    FileKind.Config -> R.drawable.vvfile_ic_config
    FileKind.Script -> R.drawable.vvfile_ic_script
    FileKind.Db -> R.drawable.vvfile_ic_db
    FileKind.Font -> R.drawable.vvfile_ic_font
    FileKind.Unknown -> R.drawable.vvfile_ic_unknown
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


