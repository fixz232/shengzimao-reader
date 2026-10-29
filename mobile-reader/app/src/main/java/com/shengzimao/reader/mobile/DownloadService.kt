package com.shengzimao.reader.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shengzimao.reader.mobile.data.DownloadStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample

/** 用户主动发起的下载以前台通知保持运行；系统终止后保留断点，回到队列可继续。 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue get() = (application as ReaderApp).readerViewModel.downloads
    private fun notification(text: String, done: Int = 0, total: Int = 0): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("圣子猫阅读 · 下载管理").setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(total, done, total == 0)
            .addAction(android.R.drawable.ic_media_pause, "暂停下载", pause).build()
    }
    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "主动下载", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, notification("正在准备下载…"))
        scope.launch {
            queue.tasks.sample(400).collect { tasks ->
                val active = tasks.firstOrNull { it.status == DownloadStatus.RUNNING }
                if (active == null && tasks.none { it.status == DownloadStatus.QUEUED }) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } else getSystemService(NotificationManager::class.java).notify(ID, if (active == null) notification("队列等待中 · 仅 Wi-Fi 任务会等待合适网络")
                    else notification("${active.title} · ${active.message}", active.done, active.total))
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") queue.tasks.value.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }.forEach { queue.pause(it.id) }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.tasks.value.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING) }.forEach { queue.pause(it.id) }
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val ID = 1800
        private const val CHANNEL = "reader-downloads"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
    }
}
