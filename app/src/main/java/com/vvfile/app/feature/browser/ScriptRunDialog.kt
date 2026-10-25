package com.vvfile.app.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.core.script.ScriptEnv
import com.vvfile.app.core.script.ScriptRunResult
import com.vvfile.app.core.script.ScriptRunner

/**
 * `.sh` 文件的处理菜单（对齐 MT：点击脚本先弹菜单，而不是直接进编辑器）。
 *
 * 只提供系统环境 / ROOT 两种执行环境（不做扩展包环境），选中 root 时显示红字警告。
 */
@Composable
fun ScriptRunDialog(
    fileName: String,
    env: ScriptEnv,
    permission: String,
    currentMode: PermissionMode?,
    onEnvChange: (ScriptEnv) -> Unit,
    onPermissionChange: (String) -> Unit,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Column {
                Text(
                    text = fileName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (currentMode == null) {
                        "请选择要执行的操作"
                    } else {
                        "当前权限 ${currentMode.symbolic()}（${currentMode.octal()}）"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (showSettings) {
                    ScriptSettingsPanel(
                        env = env,
                        permission = permission,
                        currentMode = currentMode,
                        onEnvChange = onEnvChange,
                        onPermissionChange = onPermissionChange,
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "收起设置" else "设置", fontSize = 13.sp)
                    }
                    TextButton(onClick = onEdit) {
                        Text("编辑", fontSize = 13.sp)
                    }
                    TextButton(onClick = onRun) {
                        Text("执行", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** 执行环境 + 执行权限；root 时给出红字风险提示。 */
@Composable
private fun ScriptSettingsPanel(
    env: ScriptEnv,
    permission: String,
    currentMode: PermissionMode?,
    onEnvChange: (ScriptEnv) -> Unit,
    onPermissionChange: (String) -> Unit,
) {
    Column {
        Text(
            text = "执行环境",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ScriptEnv.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEnvChange(option) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = env == option,
                    onClick = { onEnvChange(option) },
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    text = option.label,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "执行权限",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 文件真实权限不在常用预设里时补一个格子，并放在最前面：
            // 放末尾会被横向滚动挤出屏幕，等于看不到「当前是什么」
            val modes = remember(currentMode) {
                val current = currentMode?.octal()?.takeLast(3)
                if (current != null && current !in ScriptRunner.PERMISSIONS) {
                    listOf(current) + ScriptRunner.PERMISSIONS
                } else {
                    ScriptRunner.PERMISSIONS
                }
            }
            modes.forEach { mode ->
                val selected = mode == permission
                Text(
                    text = mode,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            RectangleShape,
                        )
                        .clickable { onPermissionChange(mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        if (env == ScriptEnv.ROOT) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "您已勾选使用 ROOT 权限执行，请确保脚本中不含有危险代码！",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 执行结果：命令、退出码、输出（可滚动）。 */
@Composable
fun ScriptResultDialog(
    fileName: String,
    result: ScriptRunResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Column {
                Text(text = fileName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (result.success) "执行完成" else "执行失败（exit ${result.exitCode}）",
                    fontSize = 11.sp,
                    color = if (result.success) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = "\$ ${result.command}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = result.output.ifBlank { "（无输出）" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
