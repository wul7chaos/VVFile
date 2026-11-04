package com.vvfile.app.feature.transfer
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.core.format.SizeFormatter
import com.vvfile.app.core.transfer.PcDiscovery
import com.vvfile.app.core.transfer.PcOutbox
import com.vvfile.app.core.transfer.PcTarget
import com.vvfile.app.core.transfer.PcUploader
import com.vvfile.app.ui.theme.VVDanger
import com.vvfile.app.ui.theme.VVWarn
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 单个条目的发送结果。 */
private data class SendOutcome(val name: String, val ok: Boolean, val detail: String)

/**
 * 「发送到电脑」面板：自动找电脑 → 挨个发 → 显示结果。
 *
 * 没有地址框也没有口令：打开就广播找一次（[PcDiscovery]），找到直接发；
 * 找不到给一句人话提示，并留一个「重新搜索」。文件夹原样发（[PcOutbox] 摊平成相对路径）。
 */
@Composable
fun SendToPcDialog(
    paths: List<String>,
    /** 上次连上的电脑；有就先 ping 它，不用每次重新扫描。 */
    cachedTarget: PcTarget?,
    /** 新找到/确认可用的电脑，交出去落盘，下次直接用。 */
    onRemember: (PcTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val sources = remember(paths) { paths.map { File(it) } }
    var found by remember { mutableStateOf<PcDiscovery.Found?>(null) }
    /** 局域网里发现的全部电脑；超过一台时面板上显示可选项。 */
    var devices by remember { mutableStateOf<List<PcDiscovery.Found>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var searchMessage by remember { mutableStateOf("正在找同一 WiFi 下的电脑…") }
    var sending by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var currentName by remember { mutableStateOf("") }
    var sentBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }
    var results by remember { mutableStateOf<List<SendOutcome>>(emptyList()) }
    var fileCount by remember { mutableStateOf(0) }
    var dirCount by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val folderCount = sources.count { it.isDirectory }
    val roughTotal = sources.sumOf { source ->
        if (source.isDirectory) 0L else source.length().coerceAtLeast(0)
    }
    val failed = results.count { !it.ok }

    /** [useCache]=true 时先试上次那台电脑；「重新搜索」传 false 强制重扫。 */
    suspend fun find(silent: Boolean, useCache: Boolean = true) {
        searching = true
        if (!silent) searchMessage = "正在找同一 WiFi 下的电脑…"
        found = null
        val hit = PcDiscovery.connect(if (useCache) cachedTarget else null)
        found = hit
        searching = false
        val reused = useCache && hit != null && cachedTarget != null && hit.target == cachedTarget
        searchMessage = when {
            hit == null -> "没找到电脑端。确认电脑上的 VVFile 接收器在跑，两边连同一个 WiFi（热点也行）"
            reused -> "已连上次的电脑 ${hit.target.authority}" + (hit.dir?.let { "，保存到 $it" } ?: "")
            else -> "已找到电脑 ${hit.target.authority}" + (hit.dir?.let { "，保存到 $it" } ?: "")
        }
        hit?.let { onRemember(it.target) }
        // 顺手看看同一网络里还有没有别的电脑：有就给用户一个选择
        if (hit != null) {
            val all = runCatching { PcDiscovery.discoverAll() }.getOrDefault(emptyList())
            if (all.size > 1) {
                devices = all
                searchMessage = "找到 ${all.size} 台电脑，选一台发送"
            }
        }
    }

    fun send() {
        val parsed = found?.target ?: return
        sending = true
        scanning = true
        results = emptyList()
        job = scope.launch {
            val items = PcOutbox.expandAsync(paths)
            fileCount = items.count { it is PcOutbox.Item.FileItem }
            dirCount = items.size - fileCount
            totalBytes = PcOutbox.totalBytes(items)
            scanning = false

            var index = 0
            for (item in items) {
                index += 1
                currentIndex = index
                currentName = item.relativePath
                sentBytes = 0
                when (item) {
                    is PcOutbox.Item.DirItem -> {
                        val outcome = PcUploader.mkdir(parsed, item.relativePath)
                        if (!outcome.ok) {
                            results = results + SendOutcome(
                                name = "${item.relativePath}/",
                                ok = false,
                                detail = outcome.message,
                            )
                        }
                    }
                    is PcOutbox.Item.FileItem -> {
                        val fileSize = item.file.length()
                        val outcome = PcUploader.upload(
                            target = parsed,
                            file = item.file,
                            name = item.name,
                            relativePath = item.relativePath,
                            onProgress = { sent, _ -> sentBytes = sent },
                        )
                        results = results + SendOutcome(
                            name = item.relativePath,
                            ok = outcome.ok,
                            detail = if (outcome.ok) {
                                SizeFormatter.formatCompact(fileSize)
                            } else {
                                outcome.message
                            },
                        )
                    }
                }
            }
            sending = false
        }
    }

    // 应用启动时已经找过一次：命中就立刻显示，省掉这段等待
    LaunchedEffect(paths) {
        val ready = PcDiscovery.peek()
        if (ready != null) {
            found = ready
            searching = false
            searchMessage = "已找到电脑 ${ready.target.authority}" +
                (ready.dir?.let { "，保存到 $it" } ?: "")
            onRemember(ready.target)
        } else {
            find(silent = false)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        shape = RectangleShape,
        title = {
            Column {
                Text(
                    text = "发送到电脑",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        sources.size == 1 && sources.first().isDirectory ->
                            "${sources.first().name}/（文件夹，保持原结构）"
                        sources.size == 1 -> sources.first().name
                        folderCount == 0 -> "${sources.size} 个文件 · 共 ${
                            SizeFormatter.formatCompact(roughTotal)
                        }"
                        else -> "${sources.size} 项（含 $folderCount 个文件夹）"
                    },
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .width(48.dp)
                                .height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = searchMessage,
                        fontSize = 11.sp,
                        maxLines = 2,
                        color = when {
                            searching -> MaterialTheme.colorScheme.onSurfaceVariant
                            found == null -> VVDanger
                            else -> VVWarn
                        },
                    )
                }

                // 多台电脑时列出来选：默认选中的是刚才连上的那台
                if (devices.size > 1) {
                    Spacer(Modifier.height(6.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 132.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        devices.forEach { device ->
                            val active = found?.target == device.target
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !sending) {
                                        found = device
                                        searchMessage = "已选 ${device.target.authority}" +
                                            (device.dir?.let { "，保存到 $it" } ?: "")
                                        onRemember(device.target)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = active,
                                    onClick = null,
                                    modifier = Modifier.size(22.dp),
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = MaterialTheme.colorScheme.primary,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = device.target.authority,
                                        fontSize = 12.sp,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    device.dir?.let {
                                        Text(
                                            text = "保存到 $it",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(6.dp))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (sources.size > 4) 132.dp else (sources.size * 30).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (results.isEmpty()) {
                        sources.forEach { source ->
                            ResultLine(
                                name = if (source.isDirectory) "${source.name}/" else source.name,
                                detail = if (source.isDirectory) {
                                    "文件夹"
                                } else {
                                    SizeFormatter.formatCompact(source.length())
                                },
                            )
                        }
                    } else {
                        results.takeLast(RESULT_LINES).forEach { outcome ->
                            ResultLine(
                                name = outcome.name,
                                detail = outcome.detail,
                                danger = !outcome.ok,
                            )
                        }
                    }
                }

                when {
                    sending -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (scanning) {
                                "正在整理文件夹…"
                            } else {
                                "正在发送 $currentIndex/${fileCount + dirCount} · $currentName"
                            },
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (scanning || fileCount + dirCount == 0) {
                                    0f
                                } else {
                                    currentIndex.toFloat() / (fileCount + dirCount)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$fileCount 个文件 · $dirCount 个文件夹 · 共 ${
                                SizeFormatter.formatCompact(totalBytes)
                            }",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    results.isNotEmpty() -> {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (failed == 0) {
                                "发送完成：$fileCount 个文件 · $dirCount 个文件夹"
                            } else {
                                "${results.size - failed} 个成功，$failed 个失败"
                            },
                            fontSize = 11.sp,
                            color = if (failed == 0) VVWarn else VVDanger,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sending) {
                    TextButton(
                        onClick = {
                            job?.cancel()
                            sending = false
                            scanning = false
                            searchMessage = "已取消"
                        },
                    ) {
                        Text("取消", fontSize = 13.sp)
                    }
                } else {
                    TextButton(
                        onClick = {
                            scope.launch {
                                searching = true
                                searchMessage = "正在找同一 WiFi 下的电脑…"
                                val all = PcDiscovery.connectAll(cachedTarget)
                                devices = all
                                found = all.firstOrNull()
                                searching = false
                                searchMessage = when {
                                    all.isEmpty() -> "没找到电脑端。确认电脑上的 VVFile 接收器在跑，两边连同一个 WiFi（热点也行）"
                                    all.size > 1 -> "找到 ${all.size} 台电脑，选一台发送"
                                    else -> "已找到电脑 ${all.first().target.authority}" +
                                        (all.first().dir?.let { "，保存到 $it" } ?: "")
                                }
                                all.firstOrNull()?.let { onRemember(it.target) }
                            }
                        },
                        enabled = !searching,
                    ) {
                        Text(if (searching) "搜索中…" else "重新搜索", fontSize = 13.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭", fontSize = 13.sp)
                    }
                    TextButton(onClick = { send() }, enabled = found != null) {
                        Text("发送", fontSize = 13.sp)
                    }
                }
            }
        },
    )
}

@Composable
private fun ResultLine(name: String, detail: String, danger: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = detail,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (danger) VVDanger else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RESULT_LINES = 40

