package com.vvfile.app.core.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.vvfile.app.MainActivity
import com.vvfile.app.R
import java.io.File

/**
 * 常驻的接收服务：电脑端「发送到手机」把文件推到 `Download/VVFile`。
 *
 * 为什么要前台服务：接收要在应用切到后台后仍然有效（电脑发送时手机往往在桌上放着），
 * 而后台常驻 socket 在 Android 8+ 只能靠前台服务。通知里带一个「停止接收」，
 * 不想收时一键关掉，端口随之释放。
 */
class PcReceiveService : Service() {

    private var server: PcReceiveServer? = null
    private var received = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在接收电脑发来的文件", "保存到 ${root().absolutePath}"), type())
        running = true
        holdLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (server?.isRunning != true) {
            val server = PcReceiveServer(
                root = root(),
                deviceName = deviceName(),
                onReceived = { file -> onFileReceived(file) },
                onLog = { message -> android.util.Log.i(TAG, message) },
            )
            if (server.start()) {
                this.server = server
            } else {
                notify("接收服务没起来", "端口被占用或没有存储权限")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        releaseLocks()
        server = null
        running = false
        super.onDestroy()
    }

    private fun onFileReceived(file: File) {
        received += 1
        val relative = PcServerPaths.relative(root(), file)
        notify("已收到 $relative", "共 $received 个文件 · ${root().absolutePath}")
    }

    /**
     * 锁屏也要能收：屏幕一关，系统会让 CPU 进休眠、Wi-Fi 进省电（真机上实测过：
     * 关屏 15 秒后电脑就连不上 7718 了，但进程和服务都还在）。
     * 刷一个 PARTIAL_WAKE_LOCK 保住 CPU，再刷 WIFI_MODE_FULL_HIGH_PERF 让 Wi-Fi 不做省电休眠。
     */
    @Suppress("DEPRECATION")
    private fun holdLocks() {
        runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$LOCK_TAG:wifi")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { android.util.Log.w(TAG, "拿 Wi-Fi 锁失败：${it.message}") }

        runCatching {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:cpu")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.onFailure { android.util.Log.w(TAG, "拿 CPU 锁失败：${it.message}") }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun root(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val public = File(downloads, "VVFile")
        val ok = runCatching { public.mkdirs(); public.isDirectory && public.canWrite() }.getOrDefault(false)
        // 没有「所有文件访问权限」时退回应用专属目录，至少别把文件吞掉
        val fallback = File(getExternalFilesDir(null) ?: filesDir, "VVFile").apply { mkdirs() }
        return if (ok) public else fallback
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifEmpty { "Android" }
    }

    private fun type(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PcReceiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止接收", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "电脑发来文件", NotificationManager.IMPORTANCE_LOW).apply {
            description = "电脑端把文件发到这台手机时使用的常驻通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "VVFileReceive"
        private const val LOCK_TAG = "vvfile-receive"
        private const val CHANNEL_ID = "vvfile_receive"
        private const val NOTIFICATION_ID = 7718
        const val ACTION_STOP = "com.vvfile.app.action.STOP_RECEIVE"

        /** 服务是否在跑；界面用来判断要不要提示「去开启」。 */
        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PcReceiveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PcReceiveService::class.java))
        }
    }
}
