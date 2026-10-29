package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaEngine
import com.shengzimao.reader.mobile.data.MangaImportTls
import com.shengzimao.reader.mobile.data.MangaSource
import com.shengzimao.reader.mobile.data.MangaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.io.EOFException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

class MangaImportTlsTest {
    @get:Rule val directory = TemporaryFolder()
    private val address = "https://source.example/sources.json"

    @Test fun tlsPacketFailureUsesOneSecureCompatibilityRetry(): Unit = runBlocking {
        val calls = AtomicInteger()
        lateinit var primary: OkHttpClient
        primary = OkHttpClient.Builder().addInterceptor { chain ->
            if (calls.incrementAndGet() == 1) throw SSLProtocolException("Unable to parse TLS packet header")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(MangaSource.template.toResponseBody()).build()
        }.build()
        val engine = MangaEngine(MangaStore(directory.newFolder()), primary)
        assertEquals(listOf(Protocol.HTTP_1_1), engine.importCompatibleClient.protocols)
        assertEquals(listOf(TlsVersion.TLS_1_2), engine.importCompatibleClient.connectionSpecs.first().tlsVersions)
        assertSame(primary.hostnameVerifier, engine.importCompatibleClient.hostnameVerifier)
        assertSame(primary.sslSocketFactory, engine.importCompatibleClient.sslSocketFactory)
        assertSame(primary.proxySelector, engine.importCompatibleClient.proxySelector)
        val imported = engine.importUrl(address)
        assertTrue(imported.isNotEmpty())
        assertEquals(2, calls.get())
    }

    @Test fun successDoesNotChangeProtocolOrRetry(): Unit = runBlocking {
        val calls = AtomicInteger()
        val primary = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(MangaSource.template.toResponseBody()).build()
        }.build()
        assertTrue(Protocol.HTTP_2 in primary.protocols)
        assertTrue(MangaEngine(MangaStore(directory.newFolder()), primary).importUrl(address).isNotEmpty())
        assertEquals(1, calls.get())
    }

    @Test fun connectionResetAndTruncatedResponseRecoverWithOneFreshSecureConnection(): Unit = runBlocking {
        for (original in listOf(SocketException("Connection reset"), EOFException("closed before body"), IOException("unexpected end of stream"), SocketTimeoutException("handshake timed out"))) {
            val calls = AtomicInteger()
            val primary = OkHttpClient.Builder().addInterceptor { chain ->
                if (calls.incrementAndGet() == 1) throw original
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(MangaSource.template.toResponseBody()).build()
            }.build()
            val engine = MangaEngine(MangaStore(directory.newFolder()), primary)
            assertNotSame(primary.connectionPool, engine.importCompatibleClient.connectionPool)
            assertTrue(engine.importUrl(address).isNotEmpty())
            assertEquals(2, calls.get())
        }
    }

    @Test fun repeatedConnectionResetReportsRecoveryActionsInsteadOfRawSocketError(): Unit = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw SocketException("Connection reset") }.build()
        val failure = runCatching { MangaEngine(MangaStore(directory.newFolder()), client).importUrl(address) }.exceptionOrNull()!!
        assertEquals(2, calls.get())
        assertTrue(failure.message.orEmpty().contains("连接被中断"))
        assertTrue(failure.message.orEmpty().contains("文件 / 粘贴"))
        assertFalse(MangaImportTls.canRetry(SocketException("Socket closed")))
    }

    @Test fun repeatedTlsFailureIsBoundedAndHasActionableMessage(): Unit = runBlocking {
        val calls = AtomicInteger()
        val primary = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw SSLProtocolException("Unable to parse TLS packet header")
        }.build()
        val failure = runCatching { MangaEngine(MangaStore(directory.newFolder()), primary).importUrl(address) }.exceptionOrNull()!!
        assertEquals(2, calls.get())
        assertTrue(failure.message.orEmpty().contains("TLS 1.2 / HTTP/1.1"))
        assertTrue(failure.message.orEmpty().contains("文件 / 粘贴"))
        assertTrue(generateSequence(failure) { it.cause }.any { cause ->
            cause.suppressed.any { it is SSLProtocolException }
        })
    }

    @Test fun invalidCertificateAndWrongHostnameAreNotRetried(): Unit = runBlocking {
        val failures = listOf(SSLHandshakeException("certificate").apply { initCause(CertificateException("expired")) },
            SSLPeerUnverifiedException("hostname mismatch"))
        for (original in failures) {
            val calls = AtomicInteger()
            val primary = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw original }.build()
            val failure = runCatching { MangaEngine(MangaStore(directory.newFolder()), primary).importUrl(address) }.exceptionOrNull()!!
            assertEquals(1, calls.get())
            assertTrue(failure.message.orEmpty().contains("证书校验失败"))
        }
    }

    @Test fun dnsAndCancellationDoNotTriggerCompatibilityRetry(): Unit = runBlocking {
        for (original in listOf(UnknownHostException("DNS"), CancellationException("cancelled"))) {
            val calls = AtomicInteger()
            val primary = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw original }.build()
            val failure = runCatching { MangaEngine(MangaStore(directory.newFolder()), primary).importUrl(address) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(1, calls.get())
        }
    }

    @Test fun wrappedProtocolErrorIsRecognizedButOrdinaryJsonErrorIsNot() {
        assertTrue(MangaImportTls.canRetry(IOException("connection", SSLProtocolException("TLS packet"))))
        assertTrue(MangaImportTls.canRetry(IOException("Unable to parse TLS packet header")))
        assertFalse(MangaImportTls.canRetry(IllegalArgumentException("invalid JSON")))
        val failure = IOException("network")
        assertSame(failure, MangaImportTls.diagnose(address, failure, false))
    }
}
