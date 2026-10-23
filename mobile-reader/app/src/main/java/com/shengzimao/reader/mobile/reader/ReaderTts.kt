package com.shengzimao.reader.mobile.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 朗读（TTS）：从当前页首开始读，读到页尾自动翻页续读。
 * 与桌面版一致：只朗读“正在显示的这一页”，不朗读被菜单遮挡的内容。
 */
class ReaderTts(private val context: Context) {

    data class State(
        val ready: Boolean = false,
        val speaking: Boolean = false,
        val rate: Float = 1f,
        val pitch: Float = 1f,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TextToSpeech? = null
    private var pendingStart = false
    private var utteranceId = 0

    /** 每次朗读一句时回调，用于“读完了，翻页继续”。 */
    var onPageFinished: (() -> Unit)? = null

    @Volatile
    private var released = false

    fun ensureReady() {
        if (engine != null || released) return
        // 用局部变量接住实例：若 release() 在回调到达前执行，这里要能把它关掉。
        val created = TextToSpeech(context) { status ->
            val releasedNow = released
            val tts = engine
            if (status == TextToSpeech.SUCCESS && tts != null) {
                val chinese = Locale.SIMPLIFIED_CHINESE
                val result = tts.setLanguage(chinese)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.CHINA)
                }
                tts.setSpeechRate(_state.value.rate)
                tts.setPitch(_state.value.pitch)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id?.startsWith(UTTERANCE_PREFIX) == true) onPageFinished?.invoke()
                    }

                    override fun onError(id: String?) {
                        _state.value = _state.value.copy(speaking = false, error = "朗读失败，请检查系统语音引擎。")
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?, errorCode: Int) = onError(id)
                })
                _state.value = _state.value.copy(ready = true, error = null)
                if (pendingStart) {
                    pendingStart = false
                    speakBlocking()
                }
            } else {
                _state.value = _state.value.copy(ready = false, speaking = false, error = "系统未安装中文语音引擎。")
            }
        }
        engine = created
        if (released) {
            runCatching { created.shutdown() }
            engine = null
        }
    }

    fun setRate(rate: Float) {
        val value = rate.coerceIn(0.5f, 2.5f)
        _state.value = _state.value.copy(rate = value)
        engine?.setSpeechRate(value)
    }

    fun setPitch(pitch: Float) {
        val value = pitch.coerceIn(0.5f, 2f)
        _state.value = _state.value.copy(pitch = value)
        engine?.setPitch(value)
    }

    /** 说一段文字；同时只保留最新一句，避免排队越积越长。 */
    fun speak(text: String): Boolean {
        val engine = engine
        if (engine == null || !_state.value.ready) {
            pendingStart = true
            lastText = text
            ensureReady()
            return false
        }
        return speakBlocking(text)
    }

    private var lastText = ""

    private fun speakBlocking(): Boolean = speakBlocking(lastText)

    private fun speakBlocking(text: String): Boolean {
        val engine = engine ?: return false
        lastText = text
        val clean = text.trim()
        if (clean.isEmpty()) {
            _state.value = _state.value.copy(speaking = false)
            return false
        }
        engine.stop()
        utteranceId++
        val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "$UTTERANCE_PREFIX$utteranceId")
        _state.value = _state.value.copy(speaking = result == TextToSpeech.SUCCESS, error = null)
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        pendingStart = false
        engine?.stop()
        _state.value = _state.value.copy(speaking = false)
    }

    fun release() {
        released = true
        pendingStart = false
        engine?.stop()
        engine?.shutdown()
        engine = null
        _state.value = State()
    }

    private companion object {
        const val UTTERANCE_PREFIX = "szm-page-"
    }
}
