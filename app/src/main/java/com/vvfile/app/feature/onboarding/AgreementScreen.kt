package com.vvfile.app.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vvfile.app.BuildConfig
import com.vvfile.app.R

/**
 * 首次启动的使用协议页。
 *
 * 只有点「同意并继续」才会写入 [com.vvfile.app.data.prefs.Settings.agreementAccepted]，
 * 之后启动直接进主界面；卸载重装会重新出现。
 */
@Composable
fun AgreementScreen(
    onAccept: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.vvfile_app_icon),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "VVFile",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · 首次启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "使用协议",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Section(
                "本地运行",
                "浏览、复制、移动、重命名、压缩、文本编辑都在本机完成。" +
                    "应用不联网、不上传文件、不收集使用数据，也没有广告和统计 SDK。",
            )
            Section(
                "申请的权限",
                "「所有文件访问权限」用于读写共享存储，这是文件管理器的基本能力；" +
                    "「通知权限」（Android 13 及以上）只用于在播放音频时显示控制条。" +
                    "设置与缩略图缓存保存在应用自己的目录内。",
            )
            Section(
                "数据安全",
                "删除、覆盖、移动不可撤销，重要数据请先备份。" +
                    "使用 root 权限操作的是系统分区，风险由使用者自行承担。",
            )
            Section(
                "许可与来源",
                "本软件以非商业开源方式发布，界面灵感来源于 MT 文件管理器，" +
                    "不含其任何代码、图标或素材；图标由本地 Qwen-Image 模型生成。",
            )
            Section(
                "免责声明",
                "软件按「现状」提供，不对数据丢失、设备损坏或其它后果承担责任。" +
                    "继续使用即表示你已阅读并同意上述条款。",
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "开发者：大肥鱼（DeepSeek-v4.1-flash-1017）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onExit, modifier = Modifier.height(46.dp)) {
                Text("退出", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RectangleShape,
            ) {
                Text("同意并继续", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
