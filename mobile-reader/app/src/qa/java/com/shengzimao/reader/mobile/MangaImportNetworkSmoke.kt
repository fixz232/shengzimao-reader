package com.shengzimao.reader.mobile

import android.app.Instrumentation
import com.shengzimao.reader.mobile.data.MangaEngine
import com.shengzimao.reader.mobile.data.MangaStore
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLProtocolException
import java.net.SocketException

/** 指定 69 分享链接的真实网络检查；兼容路径先注入截图同类错误，再做真实 TLS 请求。 */
fun mangaImportNetworkSmoke(test: Instrumentation, record: (String) -> Unit) = runBlocking {
    val root = File(test.targetContext.cacheDir, "manga-import-network-${System.nanoTime()}").apply { mkdirs() }
    try {
        val address = "https://www.yckceo.com/yiciyuan/tuyuans/json/id/69.json"
        val normal = MangaEngine(MangaStore(File(root, "normal"))).importUrl(address)
        check(normal.isNotEmpty())
        record("MANGA_IMPORT_NETWORK_OK source=69 profile=system imported=${normal.size}")
        val injected = AtomicBoolean()
        val requests = AtomicInteger()
        val tls = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun secureConnectEnd(call: Call, handshake: Handshake?) {
                    if (handshake != null) tls += handshake.tlsVersion.javaName
                }
            })
            .addInterceptor { chain ->
                requests.incrementAndGet()
                if (injected.compareAndSet(false, true)) throw SSLProtocolException("Unable to parse TLS packet header")
                chain.proceed(chain.request())
            }.build()
        val store = MangaStore(File(root, "compatibility"))
        val recovered = MangaEngine(store, client).importUrl(address)
        check(recovered.size == normal.size)
        check(tls.isNotEmpty() && tls.all { it == "TLSv1.2" }) { "unexpected negotiated TLS: $tls" }
        store.saveSources(recovered)
        check(store.sources().size == recovered.size)
        record("MANGA_IMPORT_TLS_FALLBACK_OK source=69 injected=SSLProtocolException realRequests=${requests.get() - 1} imported=${recovered.size} tls=$tls")
        val resetInjected = AtomicBoolean()
        val stages = mutableListOf<String>()
        val resetClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    if (resetInjected.compareAndSet(false, true)) throw SocketException("Connection reset")
                    chain.proceed(chain.request())
                }.build()
        val resetRecovered = MangaEngine(MangaStore(File(root, "reset")), resetClient).importUrl(address) { stages += it }
        check(resetRecovered.size == normal.size && stages.any { it.contains("1/1") })
        record("MANGA_IMPORT_RESET_FALLBACK_OK source=69 injected=Connection_reset imported=${resetRecovered.size} recoveryStageShown=true")
        var fallbackNotice = ""
        val blockedEntryCalls = AtomicInteger()
        val blockedEntry = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                if (chain.request().url.host in setOf("www.yckceo.com", "yckceo.com")) {
                    blockedEntryCalls.incrementAndGet(); throw SocketException("Connection reset")
                }
                chain.proceed(chain.request())
            }.build()
        val directRecovered = MangaEngine(MangaStore(File(root, "entry-unreachable")), blockedEntry)
            .importUrl(address, onFallback = { fallbackNotice = it })
        check(directRecovered.size == normal.size && blockedEntryCalls.get() == 2 && fallbackNotice.contains("直链"))
        record("MANGA_IMPORT_ENTRY_DOWN_RECOVERY_OK source=69 entryFailsTwice=true actualPublicJsonFetched=true imported=${directRecovered.size} noticeShown=true")
        for (id in listOf(70, 71)) {
            val imported = MangaEngine(MangaStore(File(root, "source-$id"))).importUrl(address.replace("69.json", "$id.json"))
            check(imported.isNotEmpty())
            record("MANGA_IMPORT_NETWORK_OK source=$id imported=${imported.size}")
        }
    } finally {
        root.deleteRecursively()
    }
}
