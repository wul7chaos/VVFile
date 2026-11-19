package com.vvfile.app.feature.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 解压落点：当前目录 / 自动建同名文件夹 / 手打路径。 */
enum class UnzipTargetMode(val label: String) {
    HERE("解压到当前目录"),
    SUBFOLDER("解压到同名文件夹"),
    CUSTOM("解压到指定目录"),
}

/** 解压范围：整包 / 只解包里正在看的这一层 / 只解勾选的条目。 */
enum class UnzipRangeMode(val label: String) {
    ALL("整个压缩包"),
    INNER("仅当前目录"),
    MARKED("仅选中条目"),
}

/**
 * 解压选项（对齐 MT 的解压对话框）。
 *
 * 三件事：**解到哪**、**解哪些**、**落到什么路径**。
 * 「同名文件夹」默认选中：几百个文件直接糊在当前目录是最常见的事故。
 */
@Composable
fun UnzipOptionsDialog(
    archiveName: String,
    currentDir: String,
    /** 包内正在浏览的目录；空串表示在包根，此时不提供「仅当前目录」。 */
    innerDir: String,
    /** 已勾选的条目（包内相对路径）；空表示没有可选的「仅选中条目」。 */
    marked: List<String> = emptyList(),
    onConfirm: (targetDir: String, entries: List<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    val base = archiveName.substringBeforeLast('.').ifBlank { archiveName }
    var targetMode by remember { mutableStateOf(UnzipTargetMode.SUBFOLDER) }
    var custom by remember { mutableStateOf(currentDir) }
    var range by remember {
        mutableStateOf(if (marked.isNotEmpty()) UnzipRangeMode.MARKED else UnzipRangeMode.ALL)
    }

    val resolved = when (targetMode) {
        UnzipTargetMode.HERE -> currentDir
        UnzipTargetMode.SUBFOLDER -> joinPath(currentDir, base)
        UnzipTargetMode.CUSTOM -> custom.trim()
    }
    val entries = when (range) {
        UnzipRangeMode.ALL -> null
        UnzipRangeMode.INNER -> innerDir.takeIf { it.isNotBlank() }?.let { listOf(it) }
        UnzipRangeMode.MARKED -> marked.takeIf { it.isNotEmpty() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解压", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = archiveName,
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("解压到", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                UnzipTargetMode.entries.forEach { option ->
                    ChoiceRow(
                        label = option.label,
                        selected = targetMode == option,
                        onSelect = { targetMode = option },
                    )
                }
                if (targetMode == UnzipTargetMode.CUSTOM) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        singleLine = true,
                        label = { Text("目标目录", fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "将解压到：${resolved.ifBlank { "（未填写）" }}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (innerDir.isNotBlank() || marked.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("解压范围", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChoiceRow(
                        label = UnzipRangeMode.ALL.label,
                        selected = range == UnzipRangeMode.ALL,
                        onSelect = { range = UnzipRangeMode.ALL },
                    )
                    if (innerDir.isNotBlank()) {
                        ChoiceRow(
                            label = "${UnzipRangeMode.INNER.label}（$innerDir）",
                            selected = range == UnzipRangeMode.INNER,
                            onSelect = { range = UnzipRangeMode.INNER },
                        )
                    }
                    if (marked.isNotEmpty()) {
                        ChoiceRow(
                            label = "${UnzipRangeMode.MARKED.label}（${marked.size} 条）",
                            selected = range == UnzipRangeMode.MARKED,
                            onSelect = { range = UnzipRangeMode.MARKED },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (resolved.isNotBlank()) onConfirm(resolved, entries) },
                enabled = resolved.isNotBlank(),
            ) { Text("开始解压") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = label, fontSize = 12.sp)
    }
}

/** 拼目录，处理 `//` 与末尾 `/`。 */
internal fun joinPath(parent: String, name: String): String =
    if (parent.isEmpty() || parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
