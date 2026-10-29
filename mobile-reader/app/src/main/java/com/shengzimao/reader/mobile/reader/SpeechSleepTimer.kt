package com.shengzimao.reader.mobile.reader

/** 按实际播放时长计时；暂停停表，修改时长重新计时，停止后新会话从头计时。 */
internal class SpeechSleepTimer {
    private var minutes = 0
    private var active = false
    private var running = false
    private var lastTime = 0L
    var remainingMs = 0L
        private set

    fun configure(value: Int, now: Long) {
        val next = value.coerceIn(0, 120)
        if (minutes == next) return
        minutes = next
        remainingMs = if (active) minutes * 60_000L else 0L
        lastTime = now
    }

    fun start(now: Long) {
        active = true
        running = false
        lastTime = now
        remainingMs = minutes * 60_000L
    }

    /** 返回是否到时；running 表示从此时起是否实际播放。 */
    fun update(now: Long, playing: Boolean): Boolean {
        if (active && running && minutes > 0) {
            remainingMs = (remainingMs - (now - lastTime).coerceAtLeast(0L)).coerceAtLeast(0L)
        }
        lastTime = now
        running = active && playing
        return active && minutes > 0 && remainingMs == 0L
    }

    fun stop() { active = false; running = false; remainingMs = 0L }
}
