package com.vvfile.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Check
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.core.format.TimeFormatter
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.ui.theme.FileTintApk
import com.vvfile.app.ui.theme.FileTintArchive
import com.vvfile.app.ui.theme.FileTintAudio
import com.vvfile.app.ui.theme.FileTintDefault
import com.vvfile.app.ui.theme.FileTintImage
import com.vvfile.app.ui.theme.FileTintText
import com.vvfile.app.ui.theme.FileTintVideo
import com.vvfile.app.ui.theme.VVFileTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
    ) {
        if (isSelected) {
            // 规范：选中行左侧 3dp 强调色条
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { this.selected = isSelected }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                SelectionIndicator(checked = isSelected)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = iconFor(entry),
                contentDescription = if (entry.isDirectory) "文件夹" else "文件",
                tint = tintFor(entry),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = metaFor(entry),
                    style = MaterialTheme.typography.bodySmall,
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

/** 选择态行首的圆形勾选（未选中为描边空心）。 */
@Composable
private fun SelectionIndicator(checked: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) accent else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (checked) accent else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun metaFor(entry: FileEntry): String {
    val time = TimeFormatter.formatShort(entry.lastModified)
    return if (entry.isDirectory) "文件夹 · $time" else "${SizeFormatter.format(entry.size)} · $time"
}

private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts")
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

private fun tintFor(entry: FileEntry): Color = when {
    entry.isDirectory -> Color(0xFF8B7DFF)
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
                selectionMode = true,
                onClick = {},
                onLongClick = {},
            )
        }
    }
}
