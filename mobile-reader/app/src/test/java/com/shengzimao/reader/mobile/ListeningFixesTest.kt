package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReadingPreferences
import com.shengzimao.reader.mobile.reader.FontProvider
import com.shengzimao.reader.mobile.reader.SpeechSleepTimer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ListeningFixesTest {
    @Test fun voiceRoundTripsAndMissingFieldInheritsDefault() {
        val settings = ReaderSettings(ttsVoice = "zh-CN-selected")
        assertEquals(settings.ttsVoice, ReadingPreferences.novel(ReadingPreferences.encode(settings)).ttsVoice)
        assertEquals(settings.ttsVoice, ReadingPreferences.novel("{}", settings).ttsVoice)
        assertEquals("", ReadingPreferences.novel("""{"ttsVoice":""}""", settings).ttsVoice)
    }

    @Test fun pauseDoesNotUseUpSleepTimerAndResumeRetainsRemainder() {
        val timer = SpeechSleepTimer()
        timer.configure(1, 0); timer.start(0)
        assertFalse(timer.update(0, true))
        assertFalse(timer.update(10_000, false)); assertEquals(50_000L, timer.remainingMs)
        assertFalse(timer.update(100_000, true)); assertEquals(50_000L, timer.remainingMs)
        assertFalse(timer.update(149_999, true)); assertEquals(1L, timer.remainingMs)
        assertTrue(timer.update(150_000, true))
    }

    @Test fun changingDurationResetsAnActiveTimerAndNewSessionStartsFresh() {
        val timer = SpeechSleepTimer()
        timer.configure(15, 0); timer.start(0); timer.update(0, true); timer.update(60_000, true)
        assertEquals(840_000L, timer.remainingMs)
        timer.configure(30, 60_000); assertEquals(1_800_000L, timer.remainingMs)
        timer.update(120_000, false); assertEquals(1_740_000L, timer.remainingMs)
        timer.stop(); assertEquals(0L, timer.remainingMs)
        timer.start(500_000); assertEquals(1_800_000L, timer.remainingMs)
        timer.configure(0, 500_000); assertFalse(timer.update(9_000_000, true)); assertEquals(0L, timer.remainingMs)
    }

    @Test fun waitingForChapterDoesNotConsumePlaybackTime() {
        val timer = SpeechSleepTimer()
        timer.configure(1, 0); timer.start(0); timer.update(0, true)
        timer.update(1000, false); timer.update(30_000, true)
        assertEquals(59_000L, timer.remainingMs)
        assertTrue(timer.update(89_000, true))
    }

    @Test fun fontReadStopsAtTheSizeBoundaryWithoutTruncatingValidFiles() {
        val data = ByteArray(33) { it.toByte() }
        assertArrayEquals(data.take(32).toByteArray(), FontProvider.readFontBytes(ByteArrayInputStream(data, 0, 32), 32))
        assertTrue(runCatching { FontProvider.readFontBytes(ByteArrayInputStream(data), 32) }.isFailure)
        assertArrayEquals(byteArrayOf(), FontProvider.readFontBytes(ByteArrayInputStream(byteArrayOf()), 32))
    }
}
