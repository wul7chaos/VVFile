package com.vvfile.app.feature.browser

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vvfile.app.data.file.AggregateProperties

/**
 * 多选属性面板（对齐 MT）：名称 / 目录 / 大小 / 文件数 / 文件夹数，
 * 「更多」再展开修改时间、权限、所有者。
 *
 * 单条属性仍走 [PropertiesDialog]：那里显示的类型/可读可写只对单条有意义，
 * 多选关心的是合计和一致性，混在一张表里反而更乱。
 */
@Composable
fun MultiPropertiesDialog(
    properties: AggregateProperties,
    onDismiss: () -> Unit,
    onChangePermissions: () -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column {
                Text(
                    text = "属性",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    InfoRow("名称", properties.nameText)
                    InfoRow("目录", properties.directory)
                    InfoRow("大小", properties.sizeText)
                    InfoRow("文件数", properties.fileCount.toString())
                    InfoRow("文件夹数", properties.dirCount.toString())
                    if (properties.partial) {
                        InfoRow(
                            "说明",
                            "部分目录无权限或数量过多，统计值可能偏小",
                            valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = if (detailsOpen) "收起" else "更多",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { detailsOpen = !detailsOpen }
                            .padding(vertical = 10.dp),
                    )

                    if (detailsOpen) {
                        InfoRow("修改时间", properties.modifiedText)
                        InfoRow("文件权限", properties.commonPermissions ?: "各不相同")
                        InfoRow("所有者与用户组", properties.commonOwner ?: "各不相同")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onChangePermissions) { Text("修改权限") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}
