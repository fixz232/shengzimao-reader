package com.shengzimao.reader.mobile.reader

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class OnlineSpeechRequest(val voice: String, val text: String, val rate: Float, val pitch: Float)

/** Only the selected sentence and one look-ahead sentence are submitted to Read Aloud. */
internal class OnlineSpeechClient {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    @Volatile private var clockOffsetSeconds = 0L

    fun synthesize(request: OnlineSpeechRequest): ByteArray {
        require(OnlineSpeechVoices.find(request.voice) != null) { "未知在线声线" }
        require(request.text.isNotBlank() && request.text.length <= 500) { "朗读文本长度无效" }
        try { return exchange(request) }
        catch (failure: SpeechHttpException) {
            if (failure.status != 403 || failure.serverDate == null) throw failure
            val server = runCatching { ZonedDateTime.parse(failure.serverDate, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
                ?: throw failure
            clockOffsetSeconds = server - System.currentTimeMillis() / 1000
            return exchange(request)
        }
    }

    private fun exchange(request: OnlineSpeechRequest): ByteArray {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val output = ByteArrayOutputStream()
        var complete = false
        val id = UUID.randomUUID().toString().replace("-", "")
        val token = EdgeSpeechToken.generate(System.currentTimeMillis() / 1000 + clockOffsetSeconds)
        val address = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${EdgeSpeechToken.CLIENT_TOKEN}&Sec-MS-GEC=$token" +
            "&Sec-MS-GEC-Version=${EdgeSpeechToken.VERSION}&ConnectionId=$id"
        val handshake = Request.Builder().url(address)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)};")
            .build()
        val socket = client.newWebSocket(handshake, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val stamp = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC))
                webSocket.send("X-Timestamp:$stamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                    """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":false},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""")
                webSocket.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:$stamp\r\nPath:ssml\r\n\r\n" + OnlineSpeechProtocol.ssml(request))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.substringBefore("\r\n\r\n").lineSequence().any { it.trim().equals("Path:turn.end", true) }) {
                    complete = true; done.countDown()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val audio = OnlineSpeechProtocol.audioChunk(bytes.toByteArray()) ?: return
                    require(output.size() + audio.size <= 4 * 1024 * 1024) { "语音响应过大" }
                    output.write(audio)
                } catch (error: Exception) { failure.compareAndSet(null, error); done.countDown() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.compareAndSet(null, if (response != null) SpeechHttpException(response.code, response.header("Date")) else t)
                response?.close(); done.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
        })
        try {
            if (!done.await(35, TimeUnit.SECONDS)) throw IOException("在线语音响应超时")
            failure.get()?.let { throw it }
            require(complete) { "在线语音连接提前中断" }
            return output.toByteArray().also { require(OnlineSpeechProtocol.isMp3(it)) { "在线服务未返回可播放语音" } }
        } finally { socket.cancel() }
    }

    private class SpeechHttpException(val status: Int, val serverDate: String?) : IOException("在线语音服务 HTTP $status")
}

internal object OnlineSpeechProtocol {
    fun ssml(request: OnlineSpeechRequest): String {
        val voice = requireNotNull(OnlineSpeechVoices.find(request.voice))
        val text = buildString {
            request.text.forEach { c -> when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '\r' || c == '\n' || c == '\t' || c.code >= 32 -> append(c)
            } }
        }
        val rate = ((request.rate.takeIf { it.isFinite() } ?: 1f).coerceIn(.5f, 2.5f) * 100 - 100).toInt()
        val pitch = (((request.pitch.takeIf { it.isFinite() } ?: 1f).coerceIn(.5f, 2f) - 1) * 50).toInt()
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${voice.locale}'>" +
            "<voice name='${voice.shortName}'><prosody rate='${signed(rate)}%' pitch='${signed(pitch)}Hz' volume='+0%'>" +
            "$text</prosody></voice></speak>"
    }
    private fun signed(value: Int) = if (value >= 0) "+$value" else value.toString()

    fun audioChunk(frame: ByteArray): ByteArray? {
        require(frame.size >= 2) { "语音数据帧不完整" }
        val length = ((frame[0].toInt() and 255) shl 8) or (frame[1].toInt() and 255)
        require(length <= frame.size - 2) { "语音数据头不完整" }
        val headers = frame.copyOfRange(2, 2 + length).toString(Charsets.UTF_8).lineSequence()
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase(Locale.ROOT) to line.substring(it + 1).trim() } }.toMap()
        if (!headers["path"].equals("audio", true) || length + 2 == frame.size) return null
        require(headers["content-type"].equals("audio/mpeg", true)) { "语音服务返回了非音频内容" }
        return frame.copyOfRange(2 + length, frame.size)
    }

    fun isMp3(bytes: ByteArray): Boolean = bytes.size >= 128 &&
        (bytes.take(3) == listOf(73.toByte(), 68.toByte(), 51.toByte()) ||
            ((bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224))
}
