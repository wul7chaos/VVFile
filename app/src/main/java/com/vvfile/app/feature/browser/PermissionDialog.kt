package com.vvfile.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 改权限面板（对齐 MT）：读/写/执行 × 所有者/用户组/其它 九个方格，
 * 加特殊权限三个方格，底部实时显示 `rw-rw----（660）`。
 *
 * 原来只有一个「权限（如 755）」输入框，用户得自己心算八进制，
 * 而且没法设 setuid/sticky。
 */
@Composable
fun PermissionDialog(
    summary: String,
    initialMode: PermissionMode,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var helpOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "权限",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    HelpBadge(onClick = { helpOpen = true })
                }

                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(WHO_WIDTH))
                        PermBit.entries.forEach { bit ->
                            Text(
                                text = bit.label,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    PermWho.entries.forEach { who ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = who.label,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(WHO_WIDTH),
                            )
                            PermBit.entries.forEach { bit ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    val checked = mode.enabled(who, bit)
                                    PermCheck(checked) { mode = mode.with(who, bit, !checked) }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "--- 特殊权限 ---",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        SpecialBit.entries.forEach { bit ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val checked = mode.specialEnabled(bit)
                                PermCheck(checked) { mode = mode.withSpecial(bit, !checked) }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = bit.label,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "${mode.symbolic()}（${mode.octal()}）",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(mode.octal()) }) { Text("确定") }
                }
            }
        }
    }
    if (helpOpen) {
        PermissionHelpDialog(onDismiss = { helpOpen = false })
    }
}

/** 右上角的 `?`：方框样式，和勾选方格统一。 */
@Composable
private fun HelpBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 权限说明：每个勾选项是什么意思、常见场景怎么配。
 *
 * 权限面板只有九个方格，不解释就只能靠背 chmod 口诀，所以给一个常驻的 `?`。
 */
@Composable
fun PermissionHelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column {
                Text(
                    text = "权限说明",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    HelpSection(
                        title = "读 / 写 / 执行",
                        lines = listOf(
                            "读：能查看内容。文件是能打开阅读，文件夹是能列出里面有什么。",
                            "写：能修改内容。文件是能改、能清空，文件夹是能在里面新建、删除、改名。",
                            "执行：文件是能当程序运行；文件夹是能进去（少了它就打不开这个目录）。",
                        ),
                    )
                    HelpSection(
                        title = "所有者 / 用户组 / 其它",
                        lines = listOf(
                            "所有者：文件的主人，一般是最先创建它的账号或应用。",
                            "用户组：和主人同组的一批账号；Android 上每个应用各占一个组。",
                            "其它：除上面两类之外的所有人。",
                            "判断是逐级的：先看是不是所有者，不是再看是不是同组，都不是才按「其它」。",
                        ),
                    )
                    HelpSection(
                        title = "特殊权限",
                        lines = listOf(
                            "设置UID：运行时以文件所有者的身份执行，而不是以运行它的人的身份，" +
                                "系统的 su 就靠它。风险最高，普通文件别开。",
                            "设置GID：以文件所属组的身份执行；对文件夹则是「新建的东西继承这个组」，" +
                                "多人共享目录常开。",
                            "粘滞：只有文件主人能删除或改名自己的文件，典型是 /tmp（1777）。",
                            "开了特殊位但没勾执行时，预览串显示大写 S / T，表示已设但不生效。",
                        ),
                    )
                    HelpSection(
                        title = "常用配置",
                        lines = listOf(
                            "644：普通文件（文档、图片、代码）。",
                            "755：可执行脚本、目录（目录必须带执行位才能进去）。",
                            "600：只有自己能读写，配置文件和密钥用这个。",
                            "660：同组可读写，协作目录里的文件常用。",
                            "777：谁都能改，除了临时调试，尽量别用。",
                        ),
                    )
                    HelpSection(
                        title = "注意",
                        lines = listOf(
                            "Android 的 /sdcard 是 FUSE 挂载，chmod 会被忽略（返回成功但权限不变）。" +
                                "要真正生效，得在内置存储以外的分区，或用 root 环境改。",
                        ),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("知道了") }
                }
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, lines: List<String>) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
    lines.forEach { line ->
        Text(
            text = "· $line",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/** 勾选方格：MT 用的是方框，这里也画方的（Material 的 Checkbox 是圆角）。 */
@Composable
private fun PermCheck(checked: Boolean, onToggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (checked) accent else MaterialTheme.colorScheme.surface, RectangleShape)
            .border(1.dp, if (checked) accent else outline, RectangleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 「所有者」这类标签列宽，和表头的空白对齐。 */
private val WHO_WIDTH = 84.dp
