package com.vvfile.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vvfile.app.core.share.SharedPathResolver
import com.vvfile.app.core.shell.RootManager
import com.vvfile.app.core.shell.RootState
import kotlinx.coroutines.launch

/**
 * 「定位所在目录」入口：在别的应用里点文件 → 打开方式 → 选它，跳进 VVFile 并定位到该文件。
 *
 * 故意做成独立 Activity：选择器里显示的是**这一个 Activity 的 label**，
 * 所以清单里它的名字就叫「定位所在目录」，用户在系统弹窗里一眼能看懂会发生什么。
 * 它自己不留界面，解析出路径就转给 [MainActivity] 并立刻结束。
 *
 * QQ / Telegram / 微信这类应用用的是 FileProvider，给不出 `_data` 列，
 * 所以快路径解析不出来时不能直接报错：先反查 fd 的真实路径，再拿 root 兜底搜文件名。
 */
class LocateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = SharedPathResolver.uriOf(intent)
        if (uri == null) {
            Toast.makeText(this, "没拿到要定位的文件", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 快路径：系统文件选择器/相册/下载这类能直接拿到路径的，几十毫秒就完事
        val quick = SharedPathResolver.resolve(this, uri)
        if (quick != null) {
            open(quick)
            return
        }

        Toast.makeText(this, "正在定位…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = SharedPathResolver.deepResolve(this@LocateActivity, uri)
            val path = result.path
            if (path == null) {
                Toast.makeText(
                    this@LocateActivity,
                    "无法定位该文件：" + (result.reason ?: "路径解析失败"),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }
            if (result.needsRoot) {
                // 落在对方应用私有目录里：只有 root 浏览器进得去，先说清楚
                val rootState = RootManager.detect()
                val hint = if (rootState is RootState.Available) {
                    "该文件在其他应用的私有目录，正在用 root 打开"
                } else {
                    "该文件在其他应用的私有目录，需要 root 才能浏览"
                }
                Toast.makeText(this@LocateActivity, hint, Toast.LENGTH_SHORT).show()
            }
            open(path)
        }
    }

    private fun open(path: String) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_LOCATE_PATH, path)
            },
        )
        finish()
    }
}


