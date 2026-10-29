package com.shengzimao.reader.mobile.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 系统 TTS 朗读。
 *
 * 相对旧实现的几处关键改动：
 *
 * 1. **进程级单例**（[ReaderTts.get]）。旧版由阅读页 `remember` 持有，页面一销毁就
 *    `release()`，所以切后台 / 锁屏必然中断。现在引擎的生命周期跟进程走，
 *    阅读页只是「借用」，离开页面只解绑回调、不销毁引擎。
 *
 * 2. **句级播放**。交给引擎的单位从「一整段」变成「一句话」（见 [SpeechText]），
 *    暂停后续读的粒度也是一句，不再出现「暂停一下重读 3000 字」。
 *
 * 3. **音色可选**。通过 [voices] 暴露系统语音引擎的全部中文音色，
 *    可用 [selectVoice] 指定，选择会被记住。
 *
 * 4. **句级进度**。[State.sentenceIndex] 精确到「本次朗读的第几句」，
 *    配合阅读页的段落区间即可做平滑高亮。
 */
class ReaderTts private constructor(context: Context) {

    data class State(
        val ready: Boolean = false,
        val initializing: Boolean = false,
        val speaking: Boolean = false,
        val paused: Boolean = false,
        val rate: Float = 1f,
        val pitch: Float = 1f,
        val error: String? = null,
        /** 当前正在读的第几段（本次提交内的下标）。 */
        val segment: Int = -1,
        /** 本次提交的段落总数。 */
        val segmentCount: Int = 0,
        /** 当前正在读的第几句（本次提交内的下标）。 */
        val sentenceIndex: Int = -1,
        /** 本次提交的句子总数。 */
        val sentenceCount: Int = 0,
        /** 用户选中的音色名，空串表示用引擎默认。 */
        val voiceName: String = "",
        val activeSession: Boolean = false,
        val waitingForContent: Boolean = false,
        val sleepRemainingMs: Long = 0L,
        val previewing: Boolean = false,
        val voiceNotice: String? = null,
        val currentText: String = "",
        val buffering: Boolean = false,
    )

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TextToSpeech? = null
    private var systemReady = false
    private val online by lazy { OnlineSpeechPlayback(this.context) }
    private var defaultVoice: Voice? = null
    private var sequence = 0
    private var chunks: List<SpeechChunk> = emptyList()
    private var cursor = 0
    private var pendingStart = false
    private var released = false
    private var previewId: String? = null
    private var previewTimeout: Runnable? = null
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }, main).build()
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val sleepTimer = SpeechSleepTimer()
    private val timerTick = object : Runnable {
        override fun run() {
            if (released || !_state.value.activeSession) return
            if (sleepTimer.update(SystemClock.elapsedRealtime(), _state.value.speaking && !_state.value.paused && !_state.value.buffering)) {
                stop()
            } else {
                publish(_state.value)
                main.postDelayed(this, 500L)
            }
        }
    }

    fun setSleepMinutes(minutes: Int) {
        sleepTimer.configure(minutes, SystemClock.elapsedRealtime())
        publish(_state.value)
    }

    /** 句子读完时回调：参数是「本次提交内的段落下标」。 */
    var onSegmentFinished: ((Int) -> Unit)? = null
    /** 整批读完。 */
    var onQueueFinished: (() -> Unit)? = null
    /** 朗读状态变化（供前台服务更新通知）。 */
    var onStateChanged: ((State) -> Unit)? = null

    private fun publish(next: State) {
        sleepTimer.update(SystemClock.elapsedRealtime(), next.speaking && !next.paused && !next.buffering)
        val value = next.copy(sleepRemainingMs = sleepTimer.remainingMs)
        _state.value = value
        onStateChanged?.invoke(value)
    }

    @JvmOverloads
    fun ensureReady(onReady: (() -> Unit)? = null) {
        if (released) return
        if (_state.value.ready) { onReady?.invoke(); return }
        if (OnlineSpeechVoices.find(_state.value.voiceName) != null) {
            publish(_state.value.copy(ready = true, initializing = false, error = null))
            onReady?.invoke()
            if (pendingStart && !_state.value.paused) submit()
            return
        }
        if (onReady != null) readyCallbacks += onReady
        if (_state.value.initializing) return
        publish(_state.value.copy(initializing = true, error = null))
        try {
            engine = TextToSpeech(context) { status -> main.post { initialized(status) } }
        } catch (_: Exception) {
            fail("无法启动系统语音引擎，请在系统语音设置中检查。")
        }
    }

    private fun initialized(status: Int) {
        if (released) return
        // A system initialization that finished after selecting an online speaker must
        // not reset that choice, start a duplicate sentence, or report a system error.
        if (OnlineSpeechVoices.find(_state.value.voiceName) != null) {
            runCatching { engine?.shutdown() }; engine = null; systemReady = false
            publish(_state.value.copy(ready = true, initializing = false))
            readyCallbacks.clear()
            if (pendingStart && !_state.value.paused) submit()
            return
        }
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            engine?.shutdown(); engine = null
            fail("系统语音引擎不可用，请在系统语音设置中安装或选择中文语音。")
            return
        }
        val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (language < TextToSpeech.LANG_AVAILABLE && tts.setLanguage(Locale.CHINA) < TextToSpeech.LANG_AVAILABLE) {
            tts.shutdown(); engine = null
            fail("当前语音引擎缺少中文语音，请在系统语音设置中下载或更换。")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        defaultVoice = tts.voice
        systemReady = true
        // 初始化完成后再应用最后一次选择，先声线、后调音，避免声线重置调音参数。
        configureVoice(configuration(), ready = true, force = true, allowRestart = false)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                main.post {
                    if (id != null && id == previewId) return@post
                    if (!isCurrent(id)) return@post
                    val chunk = chunks.getOrNull(cursor)
                    publish(
                        _state.value.copy(
                            speaking = true,
                            buffering = false,
                            error = null,
                            segment = chunk?.segment ?: 0,
                            sentenceIndex = cursor,
                        ),
                    )
                }
            }

            override fun onDone(id: String?) {
                main.post { finished(id) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                main.post {
                    if (id != null && id == previewId) {
                        stopPreview()
                        publish(_state.value.copy(voiceNotice = "试听失败，请检查所选音色是否已安装、是否需要联网。"))
                    } else if (isCurrent(id)) fail("朗读失败，请检查当前语音引擎、中文语音及其网络设置。")
                }
            }

            override fun onError(id: String?, errorCode: Int) = onError(id)
        })
        publish(_state.value.copy(ready = true, initializing = false, error = null))
        val callbacks = readyCallbacks.toList(); readyCallbacks.clear(); callbacks.forEach { it() }
        if (pendingStart && !_state.value.paused) submit()
    }

    private fun isCurrent(id: String?): Boolean =
        !released && cursor in chunks.indices && id == "szm-$sequence-$cursor"

    private fun finished(id: String?) {
        if (id != null && id == previewId) {
            previewId = null
            previewTimeout?.let(main::removeCallbacks); previewTimeout = null
            audioManager.abandonAudioFocusRequest(focusRequest)
            publish(_state.value.copy(previewing = false, buffering = false))
            return
        }
        if (!isCurrent(id)) return
        val completed = chunks[cursor]
        val expected = sequence
        val wasLastOfSegment = chunks.getOrNull(cursor + 1)?.segment != completed.segment
        cursor++
        if (wasLastOfSegment) onSegmentFinished?.invoke(completed.segment)
        if (sequence != expected || released || _state.value.paused) return
        if (cursor < chunks.size) {
            publish(_state.value.copy(sentenceIndex = cursor, segment = chunks[cursor].segment))
            submit()
        } else {
            pendingStart = false
            publish(_state.value.copy(speaking = false, buffering = false))
            onQueueFinished?.invoke()
            if (!hasPending() && !_state.value.waitingForContent) stop()
        }
    }

    private fun interruptAudio() { engine?.stop(); online.stop() }

    private fun onlineRequest(text: String) = _state.value.let {
        OnlineSpeechRequest(it.voiceName, text, it.rate, it.pitch)
    }

    /** 空值或已移除的音色恢复初始化后的中文默认音色，避免沿用上本书。 */
    private fun applyVoice(tts: TextToSpeech, name: String): SpeechVoiceSelection {
        return try {
            val selected = tts.voices?.firstOrNull { it.name == name && it.locale.language == "zh" &&
                !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            val voice = selected ?: defaultVoice
            val result = if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            val missing = name.isNotBlank() && selected == null
            val actual = tts.voice?.name
            val rejected = result < 0 || (voice != null && actual != null && actual != voice.name)
            SpeechVoiceSelection(
                name = when {
                    rejected -> actual?.takeUnless { it == defaultVoice?.name }.orEmpty()
                    missing -> ""
                    else -> name
                }, notice = when {
                    rejected -> "系统引擎未接受所选声线，已保留实际声线，请在系统语音设置中检查。"
                    missing -> "所选声线已移除或未安装，当前使用系统中文默认声线。"
                    else -> null
                })
        } catch (_: Exception) {
            SpeechVoiceSelection(runCatching { tts.voice?.name?.takeUnless { it == defaultVoice?.name }.orEmpty() }
                .getOrDefault(""), "读取系统声线失败，请检查系统语音设置。")
        }
    }

    /** 系统引擎里所有可用的中文音色，按名字排序。 */
    fun voices(): List<Voice> = runCatching {
        engine?.voices
            ?.filter { it.locale.language == "zh" }
            ?.sortedWith(compareBy({ it.locale.country }, { it.name }))
            .orEmpty()
    }.getOrDefault(emptyList())

    fun selectVoice(name: String) {
        configureVoice(name, _state.value.rate, _state.value.pitch)
    }

    /** Apply a profile atomically; replay only the current sentence, keeping pause and queue position. */
    fun configureVoice(name: String, rate: Float, pitch: Float) {
        configureVoice(SpeechConfiguration(name, rate, pitch))
    }

    private fun configuration() = _state.value.let { SpeechConfiguration(it.voiceName, it.rate, it.pitch) }

    private fun configureVoice(requested: SpeechConfiguration, ready: Boolean = _state.value.ready,
        force: Boolean = false, allowRestart: Boolean = true) {
        if (released) return
        val onlineSelected = OnlineSpeechVoices.isOnline(requested.voiceName)
        if (onlineSelected && OnlineSpeechVoices.find(requested.voiceName) == null) {
            publish(_state.value.copy(voiceNotice = "所选在线音色不存在，请重新选择。")); return
        }
        val before = _state.value
        val available = if (onlineSelected) true else systemReady && engine != null
        val replayPreview = allowRestart && before.previewing
        val replayBook = allowRestart && before.activeSession && !before.paused && !before.waitingForContent &&
            cursor in chunks.indices && (before.speaking || pendingStart)
        val target = object : SpeechConfigurationTarget {
                override fun interrupt() {
                    if (replayPreview) stopPreview()
                    else {
                        // Invalidate onDone/onError from the old utterance before stopping it.
                        sequence++; pendingStart = true
                        interruptAudio()
                        this@ReaderTts.publish(_state.value.copy(speaking = false, buffering = false))
                    }
                }
                override fun selectVoice(name: String) = if (onlineSelected) SpeechVoiceSelection(name) else applyVoice(requireNotNull(engine), name)
                override fun setRate(value: Float) = runCatching {
                    onlineSelected || engine?.setSpeechRate(value) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
                override fun setPitch(value: Float) = runCatching {
                    onlineSelected || engine?.setPitch(value) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
                override fun publish(configuration: SpeechConfiguration, notice: String?) {
                    this@ReaderTts.publish(_state.value.copy(voiceName = configuration.voiceName,
                        rate = configuration.rate, pitch = configuration.pitch, voiceNotice = notice,
                        ready = available, initializing = if (onlineSelected) false else _state.value.initializing,
                        error = null))
                }
                override fun replay() {
                    // Keep a rejected-selection notice visible instead of audition clearing it.
                    if (replayPreview) {
                        if (_state.value.voiceNotice == null) preview()
                    } else if (replayBook) submit()
                }
            }
        val changed = force || configuration() != requested.normalized()
        if (changed && !available && (replayPreview || replayBook)) target.interrupt()
        updateSpeechConfiguration(configuration(), requested, available, replayPreview || replayBook, target, force)
        if (changed && !onlineSelected && !available) {
            if (replayPreview) ensureReady { preview() } else ensureReady()
        }
    }

    fun reconnect() {
        if (released || _state.value.activeSession || _state.value.initializing) return
        stopPreview()
        runCatching { engine?.shutdown() }; engine = null; defaultVoice = null; systemReady = false
        publish(_state.value.copy(ready = false, error = null, voiceNotice = null))
        ensureReady()
    }

    /** Preview uses a separate utterance ID and never consumes the book's queue or advances a chapter. */
    fun preview(text: String = SpeechProfiles.SAMPLE): Boolean {
        if (released || !_state.value.ready || _state.value.speaking || _state.value.waitingForContent) return false
        stopPreview()
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            publish(_state.value.copy(voiceNotice = "音频正在被其他应用占用，请稍后试听。")); return false
        }
        sequence++
        val id = "szm-preview-$sequence"
        previewId = id
        previewTimeout = Runnable {
            if (previewId == id) {
                stopPreview()
                publish(_state.value.copy(voiceNotice = "试听等待超时，请检查语音引擎或网络后重试。"))
            }
        }.also { main.postDelayed(it, 45_000) }
        publish(_state.value.copy(previewing = true, voiceNotice = null))
        if (OnlineSpeechVoices.find(_state.value.voiceName) != null) {
            publish(_state.value.copy(buffering = true))
            online.play(onlineRequest(text.take(500)), onStart = {
                if (previewId == id) publish(_state.value.copy(buffering = false))
            }, onDone = { finished(id) }, onError = { message ->
                if (previewId == id) { stopPreview(); publish(_state.value.copy(voiceNotice = message)) }
            })
            return true
        }
        if (engine?.speak(text.take(500), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            stopPreview()
            publish(_state.value.copy(voiceNotice = "系统引擎未接受试听，请检查语音设置。"))
            return false
        }
        return true
    }

    fun stopPreview() {
        if (previewId == null) return
        previewId = null
        previewTimeout?.let(main::removeCallbacks); previewTimeout = null
        interruptAudio()
        audioManager.abandonAudioFocusRequest(focusRequest)
        publish(_state.value.copy(previewing = false, buffering = false))
    }

    fun moveSentence(delta: Int): Boolean {
        if (released || !_state.value.activeSession || cursor !in chunks.indices) return false
        val target = (cursor + delta.toLong()).coerceIn(0, chunks.lastIndex.toLong()).toInt()
        if (target == cursor) return false
        stopPreview()
        val paused = _state.value.paused
        sequence++; cursor = target; pendingStart = !paused
        interruptAudio()
        publish(_state.value.copy(sentenceIndex = cursor, segment = chunks[cursor].segment,
            currentText = chunks[cursor].text, speaking = false, buffering = false))
        if (paused) return true
        if (_state.value.ready) return submit()
        ensureReady(); return true
    }

    fun setRate(rate: Float) {
        configureVoice(_state.value.voiceName, rate, _state.value.pitch)
    }

    fun setPitch(pitch: Float) {
        configureVoice(_state.value.voiceName, _state.value.rate, pitch)
    }

    /**
     * 提交一段朗读任务。
     *
     * [segments] 是页面段落文本，内部会按句切分。返回 false 表示没有可读内容。
     */
    fun speakSegments(segments: List<String>): Boolean {
        if (released) return false
        stopPreview()
        sequence++
        interruptAudio()
        chunks = SpeechText.chunks(segments)
        cursor = 0
        if (chunks.isEmpty()) { stop(); return false }
        if (!_state.value.activeSession) {
            sleepTimer.start(SystemClock.elapsedRealtime())
            main.removeCallbacks(timerTick)
            main.postDelayed(timerTick, 500L)
        }
        pendingStart = true
        publish(
            _state.value.copy(
                paused = false,
                speaking = false,
                buffering = false,
                segment = 0,
                segmentCount = segments.count { it.isNotBlank() },
                sentenceIndex = 0,
                sentenceCount = chunks.size,
                error = null,
                activeSession = true,
                waitingForContent = false,
                currentText = chunks.first().text,
            ),
        )
        if (_state.value.ready) return submit()
        ensureReady()
        return true
    }

    fun speak(text: String): Boolean = speakSegments(listOf(text))

    private fun submit(): Boolean {
        val isOnline = OnlineSpeechVoices.find(_state.value.voiceName) != null
        if (!isOnline && engine == null) return false
        val chunk = chunks.getOrNull(cursor) ?: return false
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pause()
            publish(_state.value.copy(voiceNotice = "音频正在被其他应用占用，已暂停听书。"))
            return false
        }
        pendingStart = false
        publish(
            _state.value.copy(
                speaking = true,
                buffering = isOnline,
                paused = false,
                segment = chunk.segment,
                sentenceIndex = cursor,
                currentText = chunk.text,
            ),
        )
        val id = "szm-$sequence-$cursor"
        if (isOnline) {
            online.play(onlineRequest(chunk.text), onStart = {
                if (isCurrent(id)) publish(_state.value.copy(buffering = false))
            }, onDone = { finished(id) }, onError = { message -> if (isCurrent(id)) fail(message) })
            chunks.getOrNull(cursor + 1)?.let { online.prefetch(onlineRequest(it.text)) }
            return true
        }
        val result = engine?.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            fail("语音引擎未接受朗读请求，请检查系统语音设置后重试。")
            return false
        }
        return true
    }

    fun pause() {
        stopPreview()
        if (released || chunks.isEmpty()) return
        sequence++; pendingStart = false
        publish(_state.value.copy(speaking = false, paused = true, buffering = false))
        interruptAudio()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    /**
     * 从暂停的位置继续。
     *
     * 因为分片粒度是一句话，这里只会重读「当前这一句」，
     * 不会像旧实现那样重读一整段。
     */
    fun resume(): Boolean {
        if (released || cursor !in chunks.indices) return false
        stopPreview()
        sequence++; pendingStart = true
        publish(_state.value.copy(paused = false, error = null))
        if (_state.value.ready) return submit()
        ensureReady(); return true
    }

    fun stop(resetSession: Boolean = true) {
        stopPreview()
        sequence++; pendingStart = false; chunks = emptyList(); cursor = 0
        interruptAudio()
        audioManager.abandonAudioFocusRequest(focusRequest)
        if (resetSession) { sleepTimer.stop(); main.removeCallbacks(timerTick) }
        publish(
            _state.value.copy(
                speaking = false,
                buffering = false,
                paused = false,
                segment = -1,
                segmentCount = 0,
                sentenceIndex = -1,
                sentenceCount = 0,
                currentText = "",
                activeSession = !resetSession && _state.value.activeSession,
                waitingForContent = !resetSession && _state.value.activeSession,
            ),
        )
    }

    /** 是否还有可继续的内容（前台服务据此决定是否常驻）。 */
    fun hasPending(): Boolean = cursor in chunks.indices

    private fun fail(message: String) {
        previewId = null
        previewTimeout?.let(main::removeCallbacks); previewTimeout = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        sequence++; pendingStart = false; readyCallbacks.clear()
        online.stop(); systemReady = false
        sleepTimer.stop(); main.removeCallbacks(timerTick)
        runCatching { engine?.stop() }; runCatching { engine?.shutdown() }; engine = null
        publish(
            _state.value.copy(
                ready = false, initializing = false, speaking = false, paused = false, error = message,
                activeSession = false, waitingForContent = false,
                previewing = false,
                buffering = false,
            ),
        )
    }

    /** 只解绑回调，不销毁引擎：阅读页离开后仍可继续朗读。 */
    fun detach() {
        onSegmentFinished = null
        onQueueFinished = null
    }

    /** 真正释放引擎。仅当确定不再需要朗读时调用（例如服务停止）。 */
    fun release() {
        if (released) return
        released = true; sequence++; pendingStart = false; chunks = emptyList(); readyCallbacks.clear()
        online.release(); systemReady = false
        previewId = null; audioManager.abandonAudioFocusRequest(focusRequest)
        previewTimeout?.let(main::removeCallbacks); previewTimeout = null
        sleepTimer.stop(); main.removeCallbacks(timerTick)
        runCatching { engine?.stop() }; runCatching { engine?.shutdown() }; engine = null
        _state.value = State()
        instance = null
    }

    companion object {
        @Volatile private var instance: ReaderTts? = null

        /** 进程级单例：所有页面共享同一个引擎，切页/切后台不会中断朗读。 */
        fun get(context: Context): ReaderTts =
            instance ?: synchronized(this) {
                instance ?: ReaderTts(context).also { instance = it }
            }

        /** 当前是否正在朗读（供外部快速判断，不触发初始化）。 */
        fun peek(context: Context): ReaderTts.State? = instance?.state?.value
    }
}
