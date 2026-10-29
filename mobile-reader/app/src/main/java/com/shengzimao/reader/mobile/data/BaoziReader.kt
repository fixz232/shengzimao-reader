package com.shengzimao.reader.mobile.data

import org.jsoup.Jsoup
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 旧式包子源指向已失效的 App CDN；从该源的公开网页读取当前图片地址。 */
internal object BaoziReader {
    private val publicHosts = setOf("cn.bzmanga.com", "cn.baozimh.com", "www.baozimh.com", "cn.baozimhcn.com")
    private val imageHost = Regex("s[0-9]+(?:-[a-z0-9-]+)?\\.(?:baozicdn\\.com|bzcdn\\.net|baozimh\\.com)")
    private const val APP_NOTICE_PATH = "/scomic/meishaonianbangjiashijian-aojianyixi2/0/2-df6a/1.jpg"

    fun placeholderImage(reference: String): Boolean = runCatching {
        val uri = URI(reference)
        imageHost.matches(uri.host.orEmpty()) && uri.path == APP_NOTICE_PATH
    }.getOrDefault(false)

    /** This exact public response was visually confirmed as an app-download QR notice. */
    fun checkImageNotice(file: java.io.File) {
        if (file.length() != 87309L) return
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(digest != "360f8c8ba4fc98202c2878ecb20983593bddb2c0ab147e39593ac0e1c40184b1") {
            "原站返回了下载 APP 的提示图，当前章节没有可读正文，请刷新章节或选择其他源"
        }
    }

    fun emptyDirectoryMessage(html: String, url: String): String? {
        if (URI(url).host !in publicHosts) return null
        val notice = Jsoup.parse(html).selectFirst(".empty_chapters_tips .tips-box-right")?.text()?.trim().orEmpty()
        return notice.takeIf { it.isNotBlank() }?.let { "原站未提供可读章节：${it.take(300)} 请更换作品或漫源。" }
    }

    /** The old App endpoint omits the public site's explanation when a work has no chapters. */
    fun publicDetail(sourceBase: String, detail: String): String? {
        val base = URI(sourceBase)
        if (base.host !in publicHosts) return null
        val uri = URI(detail)
        if (!Regex("appgb[0-9]+(?:-[a-z0-9-]+)?\\.baozimh\\.com").matches(uri.host.orEmpty())) return null
        val match = Regex("^/baozimhapp/comic/([A-Za-z0-9_-]+)$").matchEntire(uri.path) ?: return null
        return URI("https", null, base.host, -1, "/comic/${match.groupValues[1]}", null, null).toString()
    }

    /** 来自站点公开 page_runtime_v5.js 的 REQ_DOMAINS（2026-10-26 核验），不猜测 CDN/IP。 */
    fun imageCandidates(reference: String): List<String> {
        val uri = URI(reference)
        val host = uri.host ?: return listOf(reference)
        val shard = Regex("^(s[0-9]+)(?:-[a-z0-9-]+)?\\.(?:baozicdn\\.com|bzcdn\\.net|baozimh\\.com)$").matchEntire(host)?.groupValues?.get(1)
            ?: return listOf(reference)
        if (!uri.path.startsWith("/scomic/") && !uri.path.startsWith("/w640/scomic/")) return listOf(reference)
        return (listOf("$shard.baozimh.com", "$shard-dpa1-cnhk.baozicdn.com",
            "$shard-fdca1-jptky.baozicdn.com", "$shard-smb1-usla.baozicdn.com",
            "$shard-a3-ussv.baozicdn.com", "$shard-a1-usla.baozicdn.com").map {
            "https://$it${uri.rawPath}" + uri.rawQuery?.let { query -> "?$query" }.orEmpty()
        } + reference).distinct()
    }

    /** Follow only continuation pages of this chapter, never the next chapter or an external link. */
    fun nextPage(html: String, url: String): String? {
        val current = URI(url)
        if (current.host !in publicHosts) return null
        val match = Regex("^(/comic/chapter/[A-Za-z0-9_-]+/\\d+_\\d+)(?:_(\\d+))?\\.html$")
        val start = match.matchEntire(current.path) ?: return null
        val link = Jsoup.parse(html, url).selectFirst("a#next-chapter[href]")?.attr("href").orEmpty()
        if (link.isBlank()) return null
        val next = current.resolve(link)
        val end = match.matchEntire(next.path) ?: return null
        if (next.scheme != current.scheme || next.rawAuthority != current.rawAuthority ||
            start.groupValues[1] != end.groupValues[1]) return null
        val page = start.groupValues[2].toIntOrNull() ?: 1
        if (end.groupValues[2].toIntOrNull() != page + 1) return null
        return mangaHttpUrl(next.toString())
    }

    fun publicChapter(sourceBase: String, chapter: String): String? {
        val base = URI(sourceBase)
        if (base.host !in publicHosts) return null
        val uri = URI(chapter)
        if (uri.host in publicHosts) {
            if (Regex("^/comic/chapter/[A-Za-z0-9_-]+/\\d+_\\d+\\.html$").matches(uri.path)) return chapter
            if (uri.path == "/user/page_direct") {
                val target = chapter.toHttpUrl()
                val comic = target.queryParameter("comic_id").orEmpty()
                val section = target.queryParameter("section_slot").orEmpty()
                val slot = target.queryParameter("chapter_slot").orEmpty()
                if (Regex("[A-Za-z0-9_-]+").matches(comic) && Regex("\\d{1,7}").matches(section) && Regex("\\d{1,7}").matches(slot))
                    return URI("https", null, uri.host, -1, "/comic/chapter/$comic/${section}_$slot.html", null, null).toString()
            }
        }
        if (uri.host != "baozimh.com" && uri.host?.endsWith(".baozimh.com") != true) return null
        val match = Regex("^/baozimhapp/comic/chapter/([A-Za-z0-9_-]+)/(\\d+_\\d+)\\.html$").matchEntire(uri.path) ?: return null
        return URI("https", null, base.host, -1, "/comic/chapter/${match.groupValues[1]}/${match.groupValues[2]}.html", null, null).toString()
    }

    fun pages(html: String, url: String): List<String> {
        val images = Jsoup.parse(html, url).select("amp-img.comic-contain__item[data-src], img.comic-contain__item[data-src]")
        require(images.size in 1..5000) { "包子漫画公开阅读页未返回有效图片，请刷新章节或更换书源" }
        val chapter = Regex("^/comic/chapter/([A-Za-z0-9_-]+)/").find(URI(url).path)?.groupValues?.get(1)
        return images.map {
            val image = URI(url).resolve(it.attr("data-src"))
            if (chapter != null && URI(url).host in publicHosts && imageHost.matches(image.host.orEmpty())) {
                val work = Regex("^/(?:w640/)?scomic/([^/]+)/").find(image.path)?.groupValues?.get(1)
                require(work == null || work == chapter) { "原站返回了其他作品或 APP 引导图，当前章节暂无可读正文" }
            }
            mangaHttpUrl(image.toString())
        }
            .filterNot(::mangaPlaceholder).distinct().also { require(it.isNotEmpty()) { "公开阅读页只返回了占位图" } }
    }
}
