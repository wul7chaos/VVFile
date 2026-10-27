package com.vvfile.app.feature.archive

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.ui.components.FileRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 压缩包浏览：点 zip / apk / tar / tar.gz 直接进来，像文件夹一样逐层看，
 * 点文件就地解到缓存再打开。
 *
 * 以前点压缩包会弹「选择打开方式」，让系统应用接管；文件管理器里点压缩包，
 * 第一反应应该是「进去看看里面有什么」。
 */
@Composable
fun ArchiveScreen(
    archivePath: String,
    onClose: () -> Unit,
    onOpenEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(archivePath) { mutableStateOf<List<ArchiveEntryInfo>?>(null) }
    var error by remember(archivePath) { mutableStateOf<String?>(null) }
    var currentDir by remember(archivePath) { mutableStateOf("") }
    var extracting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(archivePath) {
        error = null
        entries = null
        runCatching { withContext(Dispatchers.IO) { ArchiveIndex.read(archivePath) } }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "无法读取压缩包" }
    }

    val visible = remember(entries, currentDir) {
        entries?.let { ArchiveIndex.children(it, currentDir) }.orEmpty()
    }
    val fileName = remember(archivePath) { File(archivePath).name }

    BackHandler {
        if (currentDir.isNotEmpty()) {
            currentDir = ArchiveIndex.parentOf(currentDir)
        } else {
            onClose()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (currentDir.isNotEmpty()) {
                    currentDir = ArchiveIndex.parentOf(currentDir)
                } else {
                    onClose()
                }
            }) {
                Icon(
                    imageVector = if (currentDir.isEmpty()) {
                        Icons.Outlined.Close
                    } else {
                        Icons.AutoMirrored.Outlined.ArrowBack
                    },
                    contentDescription = if (currentDir.isEmpty()) "关闭" else "上一级",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Icon(
                imageVector = Icons.Outlined.FolderZip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (currentDir.isEmpty()) "/" else "/$currentDir")
                        entries?.let { append(" · ${it.size} 项") }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (extracting != null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        when {
            error != null -> ArchiveState(error!!)
            entries == null -> ArchiveState("正在读取压缩包…")
            visible.isEmpty() -> ArchiveState("这个压缩包里是空的")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.name }) { entry ->
                    FileRow(
                        entry = FileEntry(
                            // 加密条目在名字前面挂把锁：列表阶段看不出来，只能点开报错，
                            // 那是最糟的体验
                            name = if (entry.encrypted) "🔒 ${entry.displayName}" else entry.displayName,
                            path = entry.name,
                            isDirectory = entry.isDirectory,
                            size = entry.size,
                            lastModified = entry.modifiedAt,
                        ),
                        isSelected = false,
                        // 压缩包内条目没有权限/所有者，详情行显示大小更有用
                        showDetails = false,
                        // 包内条目还没落盘，没有可解码的源文件，别去试缩略图
                        showThumbnail = false,
                        onClick = {
                            if (entry.encrypted) {
                                Toast.makeText(
                                    context,
                                    "此条目已加密，暂不支持输入密码",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else if (entry.isDirectory) {
                                currentDir = entry.name.trimEnd('/')
                            } else {
                                // 解到缓存再交给统一的打开逻辑（文本/图片/视频都走同一条路）
                                extracting = entry.name
                                scope.launch {
                                    val target = withContext(Dispatchers.IO) {
                                        ArchiveIndex.extractTo(
                                            path = archivePath,
                                            entryName = entry.name,
                                            targetDir = archiveCacheDir(context),
                                        )
                                    }
                                    extracting = null
                                    if (target != null) {
                                        onOpenEntry(target.absolutePath)
                                    } else {
                                        // 加密、CRC 损坏、软链接都走这里：说一句人话，
                                        // 不要点了没反应
                                        Toast.makeText(
                                            context,
                                            "解出失败：条目可能已加密或已损坏",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderZip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 压缩包内文件的临时落地目录；每次进入复用，避免缓存无限增长。 */
private fun archiveCacheDir(context: android.content.Context): File =
    File(context.cacheDir, "archive")
