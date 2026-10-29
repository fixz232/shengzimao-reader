package com.shengzimao.reader.mobile.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Shares only identical voice/text/tuning audio; never queues an entire chapter in memory. */
internal class OnlineSpeechPlayback(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(context.applicationContext.cacheDir, "online-speech-v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OnlineSpeechClient()
    private val pending = linkedMapOf<String, Deferred<File>>()
    private var player: MediaPlayer? = null
    private var playing: Job? = null
    private var generation = 0

    private fun fetch(request: OnlineSpeechRequest): Deferred<File> {
        val key = cacheKey(request)
        pending.remove(key)?.takeUnless { it.isCancelled }?.let { pending[key] = it }
        return pending.getOrPut(key) {
            scope.async(Dispatchers.IO) {
                directory.mkdirs()
                val file = File(directory, "$key.mp3")
                if (file.isFile && file.length() in 128..4L * 1024 * 1024 &&
                    file.inputStream().use { input -> val head = ByteArray(128)
                        input.read(head) == head.size && OnlineSpeechProtocol.isMp3(head) }) {
                    file.setLastModified(System.currentTimeMillis()); return@async file
                }
                val data = runInterruptible { client.synthesize(request) }
                ensureActive()
                val temporary = File(directory, "$key-${UUID.randomUUID()}.tmp")
                try {
                    temporary.writeBytes(data); ensureActive()
                    check(temporary.renameTo(file)) { "语音缓存保存失败" }
                } finally { temporary.delete() }
                val cached = directory.listFiles()?.filter { it.extension == "mp3" }?.sortedByDescending { it.lastModified() }.orEmpty()
                var bytes = 0L
                cached.forEachIndexed { i, old -> bytes += old.length(); if (i >= 64 || bytes > 24L * 1024 * 1024) old.delete() }
                file
            }
        }.also {
            // Current plus a single look-ahead; evict failed or stale in-flight requests.
            while (pending.size > 3) pending.remove(pending.keys.first())?.cancel()
            if (it.isCancelled) pending.remove(key)
        }
    }

    fun play(request: OnlineSpeechRequest, onStart: () -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        stopPlayback()
        val expected = generation
        playing = scope.launch {
            try {
                val file = fetch(request).await()
                ensureActive()
                if (expected != generation) return@launch
                val media = MediaPlayer()
                player = media
                media.setWakeMode(appContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                media.setDataSource(file.absolutePath)
                media.setOnPreparedListener {
                    if (expected == generation) {
                        try { it.start(); onStart() }
                        catch (_: Exception) { stopPlayback(); onError("语音播放器启动失败，请重试") }
                    }
                }
                media.setOnCompletionListener {
                    if (expected == generation) { stopPlayback(); onDone() }
                }
                media.setOnErrorListener { _, _, _ ->
                    if (expected == generation) { file.delete(); stopPlayback(); onError("语音音频播放失败，请重试") }
                    true
                }
                media.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                pending.remove(cacheKey(request))
                if (expected == generation) { stopPlayback(); onError("在线音色加载失败，请检查网络后重试，或切换系统声音") }
            }
        }
    }

    fun prefetch(request: OnlineSpeechRequest) { fetch(request) }

    private fun stopPlayback() {
        generation++; playing?.cancel(); playing = null
        runCatching { player?.release() }; player = null
    }

    fun stop() { stopPlayback(); pending.values.forEach { it.cancel() }; pending.clear() }
    fun release() { stop(); scope.cancel() }

    companion object {
        internal fun cacheKey(request: OnlineSpeechRequest): String {
            val input = "v1\u0000${request.voice}\u0000${request.rate}\u0000${request.pitch}\u0000${request.text}"
            return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
