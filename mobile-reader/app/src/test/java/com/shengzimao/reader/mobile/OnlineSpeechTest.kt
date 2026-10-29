package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.reader.*
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReadingPreferences
import org.junit.Assert.*
import org.junit.Test

class OnlineSpeechTest {
    private val request = OnlineSpeechRequest("edge:zh-CN-XiaoxiaoNeural", "这是中文试听。", 1f, 1f)

    @Test fun sixRealSpeakersHaveStableUniqueIds() {
        assertEquals(6, OnlineSpeechVoices.all.size)
        assertEquals(6, OnlineSpeechVoices.all.map { it.id }.distinct().size)
        OnlineSpeechVoices.all.forEach { assertEquals(it, OnlineSpeechVoices.find(it.id)) }
        assertNull(OnlineSpeechVoices.find("edge:unknown"))
    }

    @Test fun selectedOnlineVoiceSurvivesBookPreferences() {
        val saved = ReaderSettings(ttsVoice = request.voice, ttsRate = 1.15f, ttsPitch = .9f)
        val restored = ReadingPreferences.novel(ReadingPreferences.encode(saved))
        assertEquals(saved.ttsVoice, restored.ttsVoice)
        assertEquals(saved.ttsRate, restored.ttsRate)
    }

    @Test fun ssmlEscapesTextAndUsesActualSpeakerAndTuning() {
        val xml = OnlineSpeechProtocol.ssml(request.copy(text = "甲<&>乙\u0001", rate = 1.5f, pitch = 1.2f))
        assertTrue(xml.contains("name='zh-CN-XiaoxiaoNeural'"))
        assertTrue(xml.contains("甲&lt;&amp;&gt;乙"))
        assertFalse(xml.contains('\u0001'))
        assertTrue(xml.contains("rate='+50%'"))
        assertTrue(xml.contains("pitch='+10Hz'"))
    }

    private fun frame(header: String, body: ByteArray): ByteArray {
        val h = header.toByteArray()
        return byteArrayOf((h.size shr 8).toByte(), h.size.toByte()) + h + body
    }

    @Test fun websocketAudioStripsOnlyTheDeclaredHeader() {
        val audio = byteArrayOf(-1, -5, 1, 2, 3)
        assertArrayEquals(audio, OnlineSpeechProtocol.audioChunk(frame("Path:audio\r\nContent-Type:audio/mpeg\r\n", audio)))
    }

    @Test fun metadataAndEmptyAudioFramesAreNotPlayed() {
        assertNull(OnlineSpeechProtocol.audioChunk(frame("Path:audio.metadata\r\n", "{}".toByteArray())))
        assertNull(OnlineSpeechProtocol.audioChunk(frame("Path:audio\r\n", byteArrayOf())))
    }

    @Test fun truncatedAndNonAudioResponsesAreRejected() {
        listOf(byteArrayOf(0), byteArrayOf(0, 30, 1), frame("Path:audio\r\nContent-Type:text/html\r\n", "error".toByteArray()))
            .forEach { bytes -> assertTrue(runCatching { OnlineSpeechProtocol.audioChunk(bytes) }.isFailure) }
        assertFalse(OnlineSpeechProtocol.isMp3("<html>not audio</html>".toByteArray()))
    }

    @Test fun audioCacheSeparatesTextSpeakersAndTuning() {
        val requests = listOf(request, request.copy(text = "下一句话。"), request.copy(voice = "edge:zh-CN-YunxiNeural"),
            request.copy(rate = 1.2f), request.copy(pitch = .9f))
        assertEquals(requests.size, requests.map { OnlineSpeechPlayback.cacheKey(it) }.distinct().size)
        assertEquals(OnlineSpeechPlayback.cacheKey(request), OnlineSpeechPlayback.cacheKey(request.copy()))
    }
}
