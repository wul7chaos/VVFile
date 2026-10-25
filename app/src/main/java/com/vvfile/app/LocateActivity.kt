package com.vvfile.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.vvfile.app.core.share.SharedPathResolver

/**
 * 「定位所在目录」入口：在别的应用里点文件 → 打开方式 → 选它，跳进 VVFile 并定位到该文件。
 *
 * 故意做成独立 Activity：选择器里显示的是**这一个 Activity 的 label**，
 * 所以清单里它的名字就叫「定位所在目录」，用户在系统弹窗里一眼能看懂会发生什么。
 * 它自己不留界面，解析出路径就转给 [MainActivity] 并立刻结束。
 */
class LocateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = SharedPathResolver.resolveIntent(this, intent)
        if (path == null) {
            Toast.makeText(this, "无法定位该文件（可能不在共享存储里）", Toast.LENGTH_LONG).show()
            finish()
            return
        }
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
