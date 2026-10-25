package com.vvfile.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 批量重命名弹窗：两个模式 + 实时预览。
 *
 * 刻意比 MT 简单：MT 那套 `{P}{S}{T}{E}{N}{zN}` 模板 + 正则开关 + 查找替换 + EXIF 时间，
 * 八行说明没人看。这里只保留「序号」和「查找替换」，序号用一个 `#` 表达位置，
 * 留空就是「原名 + 序号」——覆盖九成场景，而且每一行改动都能在预览里看到。
 */
@Composable
fun BatchRenameDialog(
    targets: List<RenameTarget>,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<RenamePreview>) -> Unit,
) {
    var mode by remember { mutableStateOf(RenameMode.SEQUENCE) }
    var template by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }

    val rule: RenameRule = when (mode) {
        RenameMode.SEQUENCE -> RenameRule.Sequence(template)
        RenameMode.REPLACE -> RenameRule.Replace(find, replaceWith)
    }
    val previews = remember(targets, rule, existingNames) {
        BatchRename.preview(targets, rule, existingNames)
    }
    val blocking = previews.any { it.issue.blocksApply() }
    val changed = previews.count { it.issue == null }
    val applicable = changed > 0 && !blocking

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column {
                Text(
                    text = "批量重命名 · ${targets.size} 项",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeChip("序号", mode == RenameMode.SEQUENCE) { mode = RenameMode.SEQUENCE }
                        Spacer(Modifier.width(8.dp))
                        ModeChip("查找替换", mode == RenameMode.REPLACE) { mode = RenameMode.REPLACE }
                    }
                    Spacer(Modifier.height(10.dp))

                    when (mode) {
                        RenameMode.SEQUENCE -> {
                            OutlinedTextField(
                                value = template,
                                onValueChange = { template = it },
                                singleLine = true,
                                label = { Text("新名称（可留空）") },
                                placeholder = { Text("留空 = 原名 + 序号") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "序号用 # 表示：IMG_# → IMG_01；## 表示补零两位；" +
                                    "留空则在原名后面直接接序号。扩展名保持不变。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        RenameMode.REPLACE -> {
                            OutlinedTextField(
                                value = find,
                                onValueChange = { find = it },
                                singleLine = true,
                                label = { Text("查找内容") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = replaceWith,
                                onValueChange = { replaceWith = it },
                                singleLine = true,
                                label = { Text("替换为（留空表示删除）") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "只替换主名，扩展名不动；区分大小写，命中全部替换。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "预览",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 76.dp, max = 148.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        previewRows(previews).forEach { item ->
                            PreviewLine(item)
                        }
                    }

                    if (blocking) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = previews.firstOrNull { it.issue.blocksApply() }
                                ?.issue
                                ?.let { issueLabel(it) }
                                .orEmpty(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "共 ${previews.size} 项，将改动 $changed 项",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(
                            enabled = applicable,
                            onClick = { onConfirm(previews.filter { it.issue == null }) },
                        ) { Text("应用") }
                    }
                }
            }
        }
    }
}

/** 条目多时只展示前 3 条和最后 1 条：首行看格式，末行看补零位数够不够。 */
private fun previewRows(items: List<RenamePreview>): List<RenamePreview> =
    if (items.size <= 4) items else items.take(3) + items.takeLast(1)

@Composable
private fun PreviewLine(item: RenamePreview) {
    val issue = item.issue
    val color = when {
        issue.blocksApply() -> MaterialTheme.colorScheme.error
        issue == RenameIssue.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.original,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = " → ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = item.result,
            fontSize = 11.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val label = issue?.let { issueLabel(it) }
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(text = label, fontSize = 10.sp, color = color, maxLines = 1)
        }
    }
}

private fun issueLabel(issue: RenameIssue): String = when (issue) {
    RenameIssue.EMPTY -> "名称为空"
    RenameIssue.INVALID -> "含非法字符或过长"
    RenameIssue.DUPLICATE -> "与批内条目重名"
    RenameIssue.EXISTS -> "目录内已有同名"
    RenameIssue.UNCHANGED -> "未改动"
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private enum class RenameMode { SEQUENCE, REPLACE }
