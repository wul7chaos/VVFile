package com.vvfile.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
fun FileRow(
    entry: FileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
