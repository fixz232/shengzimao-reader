package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit live diagnostic, excluded from the regular unit suite and all APKs. */
object BaoziDirectoryLiveProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Files.createTempDirectory("baozi-directory-probe").toFile()
        try {
            val sources = MangaSource.importText(javaClass.getResource("/legado-sample.json")!!.readText())
                .filter { it.name.endsWith("包子漫画web") || it.name.endsWith("包子漫画") }
            check(sources.size == 2)
            val evidence = File("reports/manga-empty-directory-2026-10-26").apply { mkdirs() }
            var responseIndex = 0
            val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                .addInterceptor { chain -> chain.proceed(chain.request()).also { response ->
                    if (chain.request().url.encodedPath.startsWith("/comic/")) {
                        val body = response.peekBody(1024 * 1024).string()
                        val name = "engine-response-${++responseIndex}.html"
                        File(evidence, name).writeText(body)
                        println("BAOZI_HTTP status=${response.code} url=${chain.request().url} file=$name title=${org.jsoup.Jsoup.parse(body).title()}")
                    }
                } }.build()
            val engine = MangaEngine(MangaStore(root), client)
            for (source in sources) {
                val hits = engine.search(source, "一人之下", 1)
                val blocked = hits.first { it.url.contains("michengzi") }
                val failure = runCatching { engine.details(source, blocked) }.exceptionOrNull()
                println("BAOZI_NOTICE source=${source.name} url=${blocked.url} message=${failure?.message}")
                check(failure?.message?.contains("屏蔽处理") == true) { "expected upstream unavailable notice: ${failure?.message}" }
                println("BAOZI_BLOCKED_ENTRY_CONFIRMED source=${source.name} author=${blocked.author} url=${blocked.url}")
                val candidate = hits.first { it.url.endsWith("/yirenzhixia-dongmantang") }
                val detail = engine.details(source, candidate)
                check(detail.chapters.size > 100)
                val pages = engine.pages(source, detail.chapters.first())
                check(pages.isNotEmpty() && pages.none(::mangaPlaceholder))
                println("BAOZI_OTHER_EDITION_DIRECTORY_OK source=${source.name} title=${candidate.title} author=${candidate.author} chapters=${detail.chapters.size} firstChapterPages=${pages.size} url=${candidate.url}")
            }
        } finally { root.deleteRecursively() }
    }
}
