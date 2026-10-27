package com.vvfile.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.widget.Toast
import kotlinx.coroutines.delay

/**
 * 「跳转」面板：点面包屑打开，直接改路径。
 *
 * 输入时按「所在目录 + 名字前缀」实时列候选（[suggest] 由调用方给，通常是 FileRepository），
 * 点候选就补全成它的路径；目录候选带尾斜杠，方便继续往里打。
 */
@Composable
fun PathInputDialog(
    currentPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    suggest: suspend (String) -> List<String>,
) {
    var value by remember {
        mutableStateOf(
            TextFieldValue(
                text = currentPath,
                selection = TextRange(currentPath.length),
            ),
        )
    }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 输入停顿一下再查目录，避免每敲一个字符都去 list 一次
    LaunchedEffect(value.text, currentPath) {
        val query = value.text
        // 还没改过（等于当前目录）就不提示，否则一打开就弹一条等于自己的候选
        if (query.isBlank() || query.trimEnd('/') == currentPath.trimEnd('/')) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SUGGEST_DEBOUNCE_MS)
        suggestions = runCatching { suggest(query) }.getOrDefault(emptyList())
            .filterNot { it.trimEnd('/') == query.trimEnd('/') }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column {
                Text(
                    text = "跳转",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        label = { Text("路径") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))

                    if (suggestions.isEmpty()) {
                        Text(
                            text = "输入路径可跳转；末尾带 / 会列出该目录内容",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 176.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .verticalScroll(rememberScrollState()),
                        ) {
                            suggestions.forEach { suggestion ->
                                SuggestionRow(
                                    path = suggestion,
                                    onClick = {
                                        // 补全后把光标放末尾，方便继续往里打
                                        value = TextFieldValue(
                                            text = suggestion,
                                            selection = TextRange(suggestion.length),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                val text = clipboard.getText()?.text?.trim().orEmpty()
                                if (text.isNotEmpty()) {
                                    value = TextFieldValue(text, selection = TextRange(text.length))
                                }
                            },
                        ) { Text("粘贴") }
                        TextButton(
                            onClick = {
                                val text = value.text.trim()
                                if (text.isNotEmpty()) {
                                    clipboard.setText(AnnotatedString(text))
                                    Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) { Text("复制路径") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(
                            enabled = value.text.isNotBlank(),
                            onClick = { onConfirm(value.text.trim()) },
                        ) { Text("确定") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(path: String, onClick: () -> Unit) {
    val isDirectory = path.endsWith("/")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = if (isDirectory) Icons.Outlined.FolderOpen else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = path,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SUGGEST_DEBOUNCE_MS = 160L
