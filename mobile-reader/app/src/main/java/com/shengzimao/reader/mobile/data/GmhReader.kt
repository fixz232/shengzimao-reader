package com.shengzimao.reader.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Public reader protocol verified on 2026-10-27; the retired API returns HTTP 410. */
internal object GmhReader {
    private val sourceHosts = setOf("m.g-mh.org", "baozimh.org", "godamh.com")
    private val oldApiHosts = setOf("api-get.mgsearcher.com", "api-get-v2.mgsearcher.com", "api-get-v3.mgsearcher.com")

    fun retiredImage(reference: String): Boolean {
        val host = runCatching { URI(reference).host }.getOrNull().orEmpty()
        return host == "g-mh.online" || host.endsWith(".g-mh.online")
    }

    fun currentChapterUrl(sourceBase: String, chapterUrl: String): String? {
        if (URI(sourceBase).host !in sourceHosts) return null
        val old = chapterUrl.toHttpUrl()
        if (!(old.host in oldApiHosts && old.encodedPath == "/api/chapter/getinfo") &&
            !(old.host == "v2.apikk.top" && old.encodedPath == "/api/v2/chapter/getinfo")) return null
        val manga = old.queryParameter("m") ?: return null
        val chapter = old.queryParameter("c") ?: return null
        if (!manga.matches(Regex("\\d{1,12}")) || !chapter.matches(Regex("\\d{1,12}"))) return null
        return "https://v2.apikk.top/api/v2/chapter/getinfo".toHttpUrl().newBuilder()
            .addQueryParameter("m", manga).addQueryParameter("c", chapter).build().toString()
    }

    private fun imageHost(host: String?) = host.orEmpty().matches(Regex("[ct]-nd[23]-1\\.6wm\\.top"))

    /** The CDN redirects unavailable image addresses to this shared notice, not chapter content. */
    fun placeholderImage(reference: String): Boolean = runCatching {
        val uri = URI(reference)
        imageHost(uri.host) && uri.path == "/link/link.webp"
    }.getOrDefault(false)

    /** Old rules hardcoded line 3 and omitted chapter context. Refresh once when uncached. */
    fun requiresPageRefresh(sourceBase: String, chapter: MangaChapter, cached: (String) -> Boolean): Boolean {
        if (currentChapterUrl(sourceBase, chapter.url) == null) return false
        return chapter.pages.any { image ->
            val missingContext = imageHost(runCatching { URI(image).host }.getOrNull()) &&
                chapter.imageHeaders[image].orEmpty().none { (key, value) ->
                    key.equals("Referer", true) && value == sourceBase
                }
            (retiredImage(image) || missingContext) && !cached(image)
        }
    }

    fun pages(text: String, chapterUrl: String): List<String> {
        val response = JSONObject(text)
        require(response.optBoolean("status") && response.optInt("code") == 200) { "当前漫画接口没有返回可读章节" }
        val info = response.getJSONObject("data").getJSONObject("info")
        require(info.optString("mid") == chapterUrl.toHttpUrl().queryParameter("m")) { "章节接口返回了其他漫画的数据" }
        val images = info.getJSONObject("images")
        val payload = images.get("images")
        val items = if (payload is JSONArray) payload else decode(payload as? String ?: error("章节图片数据格式不正确"))
        require(items.length() in 1..5000) { "章节没有可读图片或超过图片上限" }
        val route = if (images.optInt("line", 1) == 2) "https://c-nd2-1.6wm.top" else "https://c-nd3-1.6wm.top"
        return (0 until items.length()).map { index ->
            val path = items.getJSONObject(index).getString("url")
            require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\')) { "章节返回了无效图片路径" }
            mangaHttpUrl(route + path)
        }.distinct()
    }

    /** Inverse of the source's bounded URL-list encoding, not an executable remote script. */
    internal fun decode(encoded: String): JSONArray {
        require(encoded.length in 10..4 * 1024 * 1024 && encoded.startsWith("J7r") && encoded.endsWith("nQ")) {
            "当前章节图片编码已变化，请更新漫画源"
        }
        val body = encoded.substring(3, encoded.length - 2)
        val size = body.length - 5
        val firstSize = size / 3
        val secondSize = (size - firstSize) / 2
        val thirdSize = size - firstSize - secondSize
        require(body.substring(secondSize, secondSize + 2) == "kD" &&
            body.substring(secondSize + 2 + thirdSize, secondSize + 5 + thirdSize) == "W4s") { "章节图片编码校验失败" }
        val reordered = body.substring(secondSize + thirdSize + 5) + body.substring(0, secondSize) +
            body.substring(secondSize + 2, secondSize + thirdSize + 2)
        val custom = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val base64 = buildString(reordered.length) {
            var offset = 0
            while (offset < reordered.length) {
                val end = minOf(offset + 7, reordered.length)
                for (index in offset until end) {
                    val charIndex = if (offset / 7 % 2 == 1) end - 1 - (index - offset) else index
                    val position = custom.indexOf(reordered[charIndex])
                    require(position >= 0) { "章节图片编码含无效字符" }
                    append(standard[position])
                }
                offset = end
            }
        }
        return JSONArray(Base64.getUrlDecoder().decode(base64).toString(Charsets.UTF_8))
    }

    /** Both image routes are declared by the site's current public getChapter script. */
    fun imageCandidates(reference: String): List<String> {
        val uri = URI(reference)
        if (!imageHost(uri.host) ||
            (!uri.path.startsWith("/hp/") && !uri.path.startsWith("/manga/"))) return listOf(reference)
        val other = (if (uri.host.startsWith("c-")) "t" else "c") + uri.host.substring(1)
        return listOf(reference, "https://$other${uri.rawPath}" + uri.rawQuery?.let { "?$it" }.orEmpty())
    }
}
