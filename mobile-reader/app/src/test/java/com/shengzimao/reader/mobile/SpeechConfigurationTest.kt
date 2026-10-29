package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.reader.SpeechConfiguration
import com.shengzimao.reader.mobile.reader.SpeechConfigurationTarget
import com.shengzimao.reader.mobile.reader.SpeechProfiles
import com.shengzimao.reader.mobile.reader.SpeechVoiceSelection
import com.shengzimao.reader.mobile.reader.updateSpeechConfiguration
import com.shengzimao.reader.mobile.data.ReaderSettings
import org.junit.Assert.*
import org.junit.Test

class SpeechConfigurationTest {
    private val initial = SpeechConfiguration("voice-a", 1f, 1f)

    private class Output(var published: SpeechConfiguration) : SpeechConfigurationTarget {
        val calls = mutableListOf<String>()
        var voice = published.voiceName
        var rate = published.rate
        var pitch = published.pitch
        var notice: String? = null
        var acceptedVoice = true
        var acceptedRate = true
        var acceptedPitch = true
        var replayed: SpeechConfiguration? = null
        override fun interrupt() { calls += "stop" }
        override fun selectVoice(name: String): SpeechVoiceSelection {
            calls += "voice"
            if (!acceptedVoice) return SpeechVoiceSelection(voice, "声线不可用")
            voice = name
            // Reproduce engines that discard previous tuning when a voice is loaded.
            rate = 1f; pitch = 1f
            return SpeechVoiceSelection(name)
        }
        override fun setRate(value: Float): Boolean {
            calls += "rate"
            if (acceptedRate) rate = value
            return acceptedRate
        }
        override fun setPitch(value: Float): Boolean {
            calls += "pitch"
            if (acceptedPitch) pitch = value
            return acceptedPitch
        }
        override fun publish(configuration: SpeechConfiguration, notice: String?) {
            calls += "publish"; published = configuration; this.notice = notice
        }
        override fun replay() {
            calls += "replay"; replayed = SpeechConfiguration(voice, rate, pitch)
        }
    }

    @Test fun changingAnActiveVoiceStopsOldAudioAndReplaysWithTheNewVoiceAndTuning() {
        val output = Output(initial)
        val next = SpeechConfiguration("voice-b", .85f, .76f)
        assertTrue(updateSpeechConfiguration(initial, next, true, true, output))
        assertEquals(listOf("stop", "voice", "rate", "pitch", "publish", "replay"), output.calls)
        assertEquals(next, output.replayed)
        assertEquals(next, output.published)
        assertNull(output.notice)
    }

    @Test fun changingOnlyAStyleAlsoResubmitsTheActiveUtterance() {
        val output = Output(initial)
        for (profile in SpeechProfiles.builtIn) {
            val settings = profile.apply(ReaderSettings(ttsVoice = "voice-a"))
            val next = SpeechConfiguration(settings.ttsVoice, settings.ttsRate, settings.ttsPitch)
            output.calls.clear()
            assertTrue(updateSpeechConfiguration(output.published, next, true, true, output))
            assertEquals(next, output.replayed)
            assertEquals(1, output.calls.count { it == "replay" })
        }
    }

    @Test fun pausedOrIdlePlaybackAppliesChangesWithoutStartingAudio() {
        val output = Output(initial)
        val next = SpeechConfiguration("voice-b", 1.1f, .9f)
        updateSpeechConfiguration(initial, next, true, false, output)
        assertEquals(listOf("voice", "rate", "pitch", "publish"), output.calls)
        assertEquals(next, output.published)
        assertNull(output.replayed)
    }

    @Test fun selectionDuringInitializationWaitsAndLastSelectionIsAppliedOnceReady() {
        val output = Output(initial)
        updateSpeechConfiguration(initial, SpeechConfiguration("voice-b", .9f, .8f), false, true, output)
        val latest = SpeechConfiguration("voice-c", 1.2f, 1.1f)
        updateSpeechConfiguration(output.published, latest, false, true, output)
        assertEquals(listOf("publish", "publish"), output.calls)
        assertEquals(latest, output.published)
        output.calls.clear()
        updateSpeechConfiguration(output.published, latest, true, false, output, force = true)
        assertEquals(listOf("voice", "rate", "pitch", "publish"), output.calls)
        assertEquals(latest.voiceName, output.voice)
        assertEquals(latest.rate, output.rate, 0f)
        assertEquals(latest.pitch, output.pitch, 0f)
    }

    @Test fun persistenceEchoDoesNotInterruptOrReplayTheSameSettingsAgain() {
        val output = Output(initial)
        assertFalse(updateSpeechConfiguration(initial, initial, true, true, output))
        assertTrue(output.calls.isEmpty())
    }

    @Test fun unavailableVoiceReportsActualSelectionAndStillReappliesTuning() {
        val output = Output(initial).apply { acceptedVoice = false }
        val next = SpeechConfiguration("missing", .8f, 1.2f)
        updateSpeechConfiguration(initial, next, true, true, output)
        assertEquals(next.copy(voiceName = "voice-a"), output.published)
        assertEquals(output.published, output.replayed)
        assertEquals("声线不可用", output.notice)
    }

    @Test fun rejectedTuningIsVisibleAndDoesNotPreventOtherParametersFromApplying() {
        val output = Output(initial).apply { acceptedRate = false }
        updateSpeechConfiguration(initial, SpeechConfiguration("voice-b", 2f, .8f), true, true, output)
        assertEquals(1f, output.published.rate, 0f)
        assertEquals(.8f, output.published.pitch, 0f)
        assertTrue(output.notice.orEmpty().contains("语速"))
        assertEquals(output.published, output.replayed)
    }

    @Test fun defaultVoiceAndOutOfRangeOrNonFiniteTuningAreHandledTogether() {
        val output = Output(initial)
        updateSpeechConfiguration(initial, SpeechConfiguration("", 99f, -1f), true, false, output)
        assertEquals(SpeechConfiguration("", 2.5f, .5f), output.published)
        updateSpeechConfiguration(output.published, SpeechConfiguration("", Float.NaN, Float.POSITIVE_INFINITY),
            true, false, output)
        assertEquals(SpeechConfiguration("", 1f, 1f), output.published)
    }
}
