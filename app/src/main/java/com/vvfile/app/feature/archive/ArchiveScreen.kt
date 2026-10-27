package com.vvfile.app.feature.archive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.vvfile.app.core.archive.ArchivePasswordRequiredException
import com.vvfile.app.core.archive.ArchivePasswordWrongException
import com.vvfile.app.data.model.FileEntry
import com.vvfile.app.ui.components.FileRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 压缩包浏览：点 zip / apk / tar / tar.gz / 7z 直接进来，像文件夹一样逐层看，
 * 点文件就地解到缓存再打开。
 *
 * 带密码的包在这里闭环：打开要密码（7z 加密文件头）、解某个条目要密码
 * （ZIP 加密条目 / 7z 加密数据）都弹同一个输入框，密码输对了本次运行内记住，
 * 后续条目不再追问。取消输入则关掉压缩包，不留一个空壳界面。
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
    var password by remember(archivePath) { mutableStateOf(ArchivePasswords.get(archivePath)) }
    var prompt by remember(archivePath) { mutableStateOf<ArchivePrompt?>(null) }

    // 列表读取：需要密码时弹框（7z 加密文件头），密码错则留在框上重试
    val load: (String?) -> Unit = { candidate ->
        scope.launch {
            error = null
            entries = null
            try {
                entries = withContext(Dispatchers.IO) { ArchiveIndex.read(archivePath, candidate) }
                if (candidate != null) ArchivePasswords.remember(archivePath, candidate)
                password = candidate
            } catch (needed: ArchivePasswordRequiredException) {
                prompt = ArchivePrompt.Open(error = null)
            } catch (wrong: ArchivePasswordWrongException) {
                ArchivePasswords.forget(archivePath)
                password = null
                prompt = ArchivePrompt.Open(error = wrong.message ?: "密码不正确")
            } catch (failure: Exception) {
                error = failure.message ?: "无法读取压缩包"
            }
        }
    }

    // 解单个条目：加密条目按缓存里的密码先试，不行就问
    val extract: (ArchiveEntryInfo, String?) -> Unit = { entry, candidate ->
        extracting = entry.name
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    ArchiveIndex.extractTo(
                        path = archivePath,
                        entryName = entry.name,
                        targetDir = archiveCacheDir(context),
                        password = candidate,
                    )
                }
                extracting = null
                if (target != null) {
                    // 解开一条就等于验过密码：记下来，同一包后续条目不再追问
                    if (candidate != null) {
                        ArchivePasswords.remember(archivePath, candidate)
                        password = candidate
                    }
                    onOpenEntry(target.absolutePath)
                } else {
                    Toast.makeText(context, "解出失败：条目可能已损坏", Toast.LENGTH_SHORT).show()
                }
            } catch (needed: ArchivePasswordRequiredException) {
                extracting = null
                prompt = ArchivePrompt.Entry(entry, error = null)
            } catch (wrong: ArchivePasswordWrongException) {
                extracting = null
                ArchivePasswords.forget(archivePath)
                password = null
                prompt = ArchivePrompt.Entry(entry, error = wrong.message ?: "密码不正确")
            } catch (failure: Exception) {
                extracting = null
                Toast.makeText(context, failure.message ?: "解出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(archivePath) { load(password) }

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
                        if (password != null) {
                            append(" · 已解锁")
                        } else if (entries?.any { it.encrypted } == true) {
                            append(" · 含加密条目")
                        }
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
                            // 加密条目在名字前面挂把锁：一眼看出哪些要密码
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
                            when {
                                entry.isDirectory -> currentDir = entry.name.trimEnd('/')
                                // 已解锁就带着密码直接解，省一次点击
                                password != null -> extract(entry, password)
                                entry.encrypted -> prompt = ArchivePrompt.Entry(entry, error = null)
                                else -> extract(entry, null)
                            }
                        },
                    )
                }
            }
        }
    }

    val current = prompt
    if (current != null) {
        ArchivePasswordDialog(
            fileName = fileName,
            error = current.error,
            hint = when (current) {
                is ArchivePrompt.Open -> "这个压缩包需要密码才能打开"
                is ArchivePrompt.Entry -> "「${current.entry.displayName}」已加密"
            },
            onDismiss = {
                prompt = null
                // 连列表都没打开时，取消就退出去，别停在一个死界面上
                if (entries == null && current is ArchivePrompt.Open) onClose()
            },
            onConfirm = { input ->
                prompt = null
                when (current) {
                    is ArchivePrompt.Open -> load(input)
                    is ArchivePrompt.Entry -> extract(current.entry, input)
                }
            },
        )
    }
}

/** 密码弹窗的两种来由：打开整个包，或解某一个加密条目。 */
private sealed interface ArchivePrompt {
    val error: String?

    data class Open(override val error: String?) : ArchivePrompt

    data class Entry(val entry: ArchiveEntryInfo, override val error: String?) : ArchivePrompt
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
