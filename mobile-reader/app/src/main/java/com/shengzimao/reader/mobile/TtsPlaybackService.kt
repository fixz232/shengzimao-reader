package com.shengzimao.reader.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shengzimao.reader.mobile.reader.ReaderTts

/**
 * 听书前台服务。
 *
 * 为什么必须有它：
 * 旧实现里 TTS 引擎挂在阅读页的 Composable 上，一旦离开页面就 `release()`，
 * 于是「锁屏继续听」「切到别的 App 继续听」这两个最基本的听书场景都无法成立，
 * 系统在后台也随时可能把进程回收掉。
 *
 * 现在：
 * - 服务在前台运行，持有进程级 [ReaderTts] 单例，锁屏/切后台继续朗读；
 * - 通知栏提供「暂停 / 继续」「停止」，不用回到 App；
 * - 朗读结束（[ReaderTts.hasPending] 为假且不在播放）时自动 `stopSelf()`，不常驻。
 *
 * 使用 mediaPlayback 类型，按实际播放会话保活，结束后停止服务。
 */
class TtsPlaybackService : Service() {

    private val tts by lazy { ReaderTts.get(this) }
    private var receiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) tts.pause()
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(state: ReaderTts.State): Notification {
        val playing = state.speaking && !state.paused
        val title = if (state.error != null) "听书已停止" else "圣子猫阅读 · 听书"
        val text = when {
            state.error != null -> state.error
            state.paused -> "已暂停"
            state.waitingForContent -> "正在加载下一段内容…"
            state.buffering -> "正在加载在线语音…"
            playing -> "正在朗读 · 第 ${state.sentenceIndex + 1}/${state.sentenceCount} 句"
            else -> "准备朗读…"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (state.error == null) {
            if (playing) {
                builder.addAction(
                    android.R.drawable.ic_media_pause, "暂停",
                    actionIntent(ACTION_PAUSE, 1),
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_play, "继续",
                    actionIntent(ACTION_RESUME, 2),
                )
            }
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "停止",
                actionIntent(ACTION_STOP, 3),
            )
        }
        return builder.build()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "听书播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "朗读小说时的播放控制"
                setShowBadge(false)
            },
        )
        startForeground(ID, buildNotification(tts.state.value))
        ContextCompat.registerReceiver(this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        tts.onStateChanged = { state ->
            if (state.error != null || !state.activeSession) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                getSystemService(NotificationManager::class.java).notify(ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> tts.pause()
            ACTION_RESUME -> tts.resume()
            ACTION_STOP -> {
                tts.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        if (!tts.state.value.activeSession) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        tts.pause()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) { unregisterReceiver(noisyReceiver); receiverRegistered = false }
        tts.onStateChanged = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 1801
        private const val CHANNEL = "reader-tts"
        const val ACTION_PAUSE = "com.shengzimao.reader.mobile.tts.PAUSE"
        const val ACTION_RESUME = "com.shengzimao.reader.mobile.tts.RESUME"
        const val ACTION_STOP = "com.shengzimao.reader.mobile.tts.STOP"

        /** 开始朗读时拉起前台服务，保证锁屏后不被回收。 */
        fun start(context: Context): Boolean =
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TtsPlaybackService::class.java))
            }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TtsPlaybackService::class.java)) }
        }
    }
}
