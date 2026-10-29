package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

class MangaImportRoutesTest {
    @get:Rule val folder = TemporaryFolder()
    private val address = "https://www.yckceo.com/yiciyuan/tuyuans/json/id/69.json"
    private fun sample() = javaClass.classLoader!!.getResourceAsStream("legado-sample.json")!!.bufferedReader().use { it.readText() }

    @Test fun knownShareSurvivesEntryResetAndKeepsAll97SourcesWithNotice(): Unit = runBlocking {
        val visited = mutableListOf<String>(); var notice = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            visited += chain.request().url.toString()
            if (chain.request().url.host == "www.yckceo.com") throw SocketException("Connection reset")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(sample().toResponseBody()).build()
        }.build()
        val sources = MangaEngine(MangaStore(folder.newFolder()), client).importUrl(address, onFallback = { notice = it })
        assertEquals(97, sources.size)
        assertEquals(listOf(address, address) + MangaImportRoutes.candidates(address), visited)
        assertTrue(notice.contains("2026-10-27") && notice.contains("直链"))
    }

    @Test fun knownShareDnsFailureCanUsePublicRouteWithoutRetryingBadDns(): Unit = runBlocking {
        val visited = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            visited += chain.request().url.host
            if (chain.request().url.host == "www.yckceo.com") throw UnknownHostException("entry DNS")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(MangaSource.template.toResponseBody()).build()
        }.build()
        assertTrue(MangaEngine(MangaStore(folder.newFolder()), client).importUrl(address).isNotEmpty())
        assertEquals(listOf("www.yckceo.com", "gcore.jsdelivr.net"), visited)
    }

    @Test fun successfulRedirectIsRetriedAtItsDestinationInsteadOfEntry(): Unit = runBlocking {
        val visited = mutableListOf<String>(); var failed = false
        val entry = "https://source.example/share.json"; val target = "https://cdn.example/source.json"
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val url = chain.request().url.toString(); visited += url
            when {
                url == entry -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(302).message("Found").header("Location", target).body("".toResponseBody()).build()
                !failed -> { failed = true; throw SocketException("Connection reset") }
                else -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(MangaSource.template.toResponseBody()).build()
            }
        }.build()
        assertTrue(MangaEngine(MangaStore(folder.newFolder()), client).importUrl(entry).isNotEmpty())
        assertEquals(listOf(entry, target, target), visited)
    }

    @Test fun certificateFailureNeverFallsBackEvenForKnownShare(): Unit = runBlocking {
        var calls = 0; var notice = ""
        val client = OkHttpClient.Builder().addInterceptor { calls++; throw SSLPeerUnverifiedException("hostname mismatch") }.build()
        val failure = runCatching { MangaEngine(MangaStore(folder.newFolder()), client).importUrl(address, onFallback = { notice = it }) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("证书校验失败"))
        assertEquals(1, calls); assertEquals("", notice)
    }

    @Test fun routesOnlyMatchExactPublicSharesAndNeverForwardCredentialsOrQueries() {
        listOf(address.replace("www.yckceo.com", "www.yckceo.com.evil.example"), address + "?token=private",
            address.replace("https:", "http:"), address.replace("69.json", "934.json"),
            address.replace("www.yckceo.com", "user:pass@www.yckceo.com"), address.replace(".com/", ".com:8443/")
        ).forEach { assertTrue(MangaImportRoutes.candidates(it).isEmpty()) }
        for (id in listOf(69, 70, 71)) assertTrue(MangaImportRoutes.candidates(address.replace("69.json", "$id.json")).all {
            it.startsWith("https://") && it.contains("/gh/mumuceo/file01/")
        })
    }
}
