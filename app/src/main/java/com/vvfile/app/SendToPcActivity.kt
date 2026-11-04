package com.vvfile.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vvfile.app.core.transfer.PcInbox
import java.io.File
import kotlinx.coroutines.launch

/**
 * 「发送到电脑」入口：在别的应用里点文件 → 打开方式 → 选它，直接把文件发到电脑。
 *
 * 和 [LocateActivity] 同一个套路：独立 Activity + 自己的 label，
 * 系统选择器里显示的就是「发送到电脑」，用户一眼知道会发生什么。
 * 真正发送界面在 [MainActivity]（要显示地址、进度、结果），这里只负责把文件解析出来。
 */
class SendToPcActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val files = runCatching {
                PcInbox.resolve(this@SendToPcActivity, intent, cacheDir)
            }.getOrDefault(emptyList())
            if (files.isEmpty()) {
                Toast.makeText(
                    this@SendToPcActivity,
                    "没拿到要发送的文件",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }
            startActivity(
                Intent(this@SendToPcActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_SEND_TO_PC_PATHS, joined(files))
                },
            )
            finish()
        }
    }

    private fun joined(files: List<File>): String = files.joinToString("\n") { it.absolutePath }
}
