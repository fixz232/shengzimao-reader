package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReadingPreferences
import com.shengzimao.reader.mobile.reader.SpeechProfile
import com.shengzimao.reader.mobile.reader.SpeechProfiles
import org.junit.Assert.*
import org.junit.Test

class SpeechProfilesTest {
    @Test fun tenDistinctStylesHaveValidTuningAndDoNotPretendToProvideSpeakers() {
        val profiles = SpeechProfiles.builtIn
        assertEquals(10, profiles.size)
        assertEquals(10, profiles.map { it.id }.toSet().size)
        assertEquals(10, profiles.map { it.rate to it.pitch }.toSet().size)
        assertEquals(5, profiles.count { it.group == "女声风格" })
        assertEquals(5, profiles.count { it.group == "男声风格" })
        profiles.forEach {
            assertTrue(it.rate in .5f..2.5f); assertTrue(it.pitch in .5f..2f)
            assertNull(it.voiceName)
        }
    }

    @Test fun applyingAStyleKeepsTheUsersRealBaseVoiceAndOtherReadingPreferences() {
        val original = ReaderSettings(ttsVoice = "real-installed-voice", ttsSleepMinutes = 30,
            autoTtsTurnPage = false, ttsHighlight = false, fontSize = 25)
        for (profile in SpeechProfiles.builtIn) {
            val applied = profile.apply(original)
            assertTrue(profile.matches(applied))
            assertEquals(original.ttsVoice, applied.ttsVoice)
            assertEquals(original, applied.copy(ttsProfile = original.ttsProfile, ttsRate = original.ttsRate, ttsPitch = original.ttsPitch))
        }
    }

    private fun custom(voice: String = "selected-real-voice") = SpeechProfile("custom-fixture", "我的睡前声",
        "已保存的基础声线与调音", "我的风格", .85f, .95f, voice)

    @Test fun customProfileRestoresItsBaseVoiceAndTuningAfterSerialization() {
        val expected = custom()
        val restored = SpeechProfiles.decode(SpeechProfiles.encode(listOf(expected))).single()
        assertEquals(expected, restored)
        val applied = restored.apply(ReaderSettings(ttsVoice = "another-engine-voice"))
        assertEquals(expected.voiceName, applied.ttsVoice)
        assertTrue(restored.matches(applied))
        assertFalse(restored.matches(applied.copy(ttsVoice = "different")))
        assertFalse(restored.matches(applied.copy(ttsRate = 1.5f)))
    }

    @Test fun explicitSystemDefaultVoiceIsPreservedForCustomProfiles() {
        val restored = SpeechProfiles.decode(SpeechProfiles.encode(listOf(custom("")))).single()
        assertEquals("", restored.apply(ReaderSettings(ttsVoice = "previous")).ttsVoice)
    }

    @Test fun perBookSettingsRoundTripAndOldPreferencesKeepTheirDefaults() {
        val expected = SpeechProfiles.builtIn.first().apply(ReaderSettings(ttsVoice = "real-installed-voice"))
        assertEquals(expected, ReadingPreferences.novel(ReadingPreferences.encode(expected)))
        assertEquals("", ReadingPreferences.novel("{}").ttsProfile)
        assertEquals(expected.ttsProfile, ReadingPreferences.novel("{}", expected).ttsProfile)
    }

    @Test fun malformedOrOversizedCustomProfilesAreRejectedInsteadOfPartiallyLoaded() {
        assertThrows(Exception::class.java) { SpeechProfiles.decode("broken") }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.decode(" ".repeat(33 * 1024)) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom(), custom())) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom().copy(name = " "))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom().copy(name = "字".repeat(31)))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom().copy(rate = Float.NaN))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom().copy(pitch = .1f))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom().copy(id = "gentle"))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(listOf(custom().copy(voiceName = null))) }
    }

    @Test fun customCollectionLimitIsEnforcedAndAllTwentyCanBeReloaded() {
        val profiles = (1..SpeechProfiles.MAX_CUSTOM).map { custom().copy(id = "custom-$it", name = "声音 $it") }
        assertEquals(profiles, SpeechProfiles.decode(SpeechProfiles.encode(profiles)))
        assertThrows(IllegalArgumentException::class.java) { SpeechProfiles.encode(profiles + custom()) }
    }
}
