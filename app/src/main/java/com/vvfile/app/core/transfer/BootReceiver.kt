package com.vvfile.app.core.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 升级后把「电脑 → 手机」的接收服务拉起来。
 *
 * 没有它，服务只在用户打开一次 App 之后有效，重启手机就收不到东西了——
 * 而「锁屏也能收」的前提是服务一直在。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PcReceiveService.start(context)
        }
    }
}
