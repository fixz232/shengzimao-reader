package com.shengzimao.reader.mobile.reader

internal data class SpeechConfiguration(val voiceName: String, val rate: Float, val pitch: Float) {
    fun normalized() = copy(
        rate = if (rate.isFinite()) rate.coerceIn(.5f, 2.5f) else 1f,
        pitch = if (pitch.isFinite()) pitch.coerceIn(.5f, 2f) else 1f,
    )
}

internal data class SpeechVoiceSelection(val name: String, val notice: String? = null)

/** A voice change can reset tuning and only affects subsequently submitted utterances. */
internal interface SpeechConfigurationTarget {
    fun interrupt()
    fun selectVoice(name: String): SpeechVoiceSelection
    fun setRate(value: Float): Boolean
    fun setPitch(value: Float): Boolean
    fun publish(configuration: SpeechConfiguration, notice: String?)
    fun replay()
}

internal fun updateSpeechConfiguration(
    current: SpeechConfiguration,
    requested: SpeechConfiguration,
    ready: Boolean,
    restart: Boolean,
    target: SpeechConfigurationTarget,
    force: Boolean = false,
): Boolean {
    val next = requested.normalized()
    if (!force && next == current) return false
    // During asynchronous initialization, retain the request without querying an unready engine.
    if (!ready) {
        target.publish(next, null)
        return true
    }
    if (restart) target.interrupt()
    val voice = target.selectVoice(next.voiceName)
    // Always set these AFTER selecting the voice, including when restoring a saved profile.
    val rateAccepted = target.setRate(next.rate)
    val pitchAccepted = target.setPitch(next.pitch)
    val notice = listOfNotNull(voice.notice,
        if (rateAccepted) null else "系统引擎未接受语速设置，请更换语音引擎后重试。",
        if (pitchAccepted) null else "系统引擎未接受音高设置，请更换语音引擎后重试。",
    ).joinToString("\n").ifBlank { null }
    target.publish(next.copy(voiceName = voice.name,
        rate = if (rateAccepted) next.rate else current.rate,
        pitch = if (pitchAccepted) next.pitch else current.pitch), notice)
    if (restart) target.replay()
    return true
}
