package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class MangaNetworkCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()
    private val source = MangaSource.parse(JSONObject("""{"id":"transport-test","name":"原创连接测试","baseUrl":"https://source.example",
        "search":{"url":"/search?q={query}","items":".book","title":"a","link":"a@href"},
        "detail":{"chapters":".chapter"},"reader":{"pages":"img@src"}}"""))

    @Test fun sourceGetRecoversFromResetAndKeepsTheWorkingProfile() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            if (calls == 1) throw SocketException("Connection reset")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<div class='book'><a href='/book'>原创漫画</a></div>".toResponseBody()).build()
        }.build()
        val engine = MangaEngine(MangaStore(folder.newFolder()), client)
        assertEquals(1, engine.search(source, "原创", 1).size)
        assertEquals(2, calls)
        assertEquals(1, engine.search(source, "原创", 2).size)
        assertEquals(3, calls)
    }

    @Test fun exhaustedProfileAndCertificateErrorsAreNotRepeatedByTheOuterRetryLoop() = runBlocking {
        for (failure in listOf(SocketException("Connection reset"), SSLHandshakeException("certificate").apply { initCause(CertificateException("expired")) })) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { calls++; throw failure }.build()
            assertTrue(runCatching { MangaEngine(MangaStore(folder.newFolder()), client).search(source, "原创", 1) }.isFailure)
            assertEquals(if (failure is SocketException) 2 else 1, calls)
        }
    }

    @Test fun emptyUpstreamResponseIsNotPresentedAsASuccessfulSearchWithNoMatches() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }.build()
        val failure = runCatching { MangaEngine(MangaStore(folder.newFolder()), client).search(source,"原创",1) }.exceptionOrNull()!!
        assertTrue(failure.message.orEmpty().contains("空响应"))
    }
}
