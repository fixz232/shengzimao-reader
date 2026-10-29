package com.shengzimao.reader.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Site-specific public endpoints live here; generic JSON/CSS/legacy rules stay in MangaEngine. */
internal interface MangaSiteAdapter {
    val id: String
    fun accepts(source: MangaSource, chapter: MangaChapter): Boolean
    suspend fun pages(source: MangaSource, chapter: MangaChapter,
        fetch: suspend (String, Map<String, String>) -> String,
        rememberHeaders: (String, Map<String, String>) -> Unit): List<String>
    fun imageCandidates(url: String): List<String> = listOf(url)
}

internal object MangaSiteAdapters {
    val all: List<MangaSiteAdapter> = listOf(GmhSiteAdapter, BaoziSiteAdapter)
    fun find(source: MangaSource, chapter: MangaChapter) = all.firstOrNull { it.accepts(source, chapter) }
    fun imageCandidates(url: String): List<String> = all.asSequence().map { it.imageCandidates(url) }.firstOrNull { it.size > 1 } ?: listOf(url)
}

internal object GmhSiteAdapter : MangaSiteAdapter {
    override val id = "gmh-public-v2"
    override fun accepts(source: MangaSource, chapter: MangaChapter) = GmhReader.currentChapterUrl(source.baseUrl, chapter.url) != null
    override suspend fun pages(source: MangaSource, chapter: MangaChapter, fetch: suspend (String, Map<String, String>) -> String,
        rememberHeaders: (String, Map<String, String>) -> Unit): List<String> {
        val current = checkNotNull(GmhReader.currentChapterUrl(source.baseUrl, chapter.url))
        val headers = MangaRequestHeaders.scoped(chapter.requestHeaders, MangaRequestHeaders.sameOrigin(chapter.url.toHttpUrl(), current.toHttpUrl()))
        return GmhReader.pages(fetch(current, headers), current).also { images -> images.forEach { rememberHeaders(it, mapOf("Referer" to source.baseUrl)) } }
    }
    override fun imageCandidates(url: String) = GmhReader.imageCandidates(url)
}

internal object BaoziSiteAdapter : MangaSiteAdapter {
    override val id = "baozi-public-v2"
    override fun accepts(source: MangaSource, chapter: MangaChapter) = BaoziReader.publicChapter(source.baseUrl, chapter.url) != null
    override suspend fun pages(source: MangaSource, chapter: MangaChapter, fetch: suspend (String, Map<String, String>) -> String,
        rememberHeaders: (String, Map<String, String>) -> Unit): List<String> {
        var page = checkNotNull(BaoziReader.publicChapter(source.baseUrl, chapter.url))
        val images = linkedSetOf<String>()
        val visited = hashSetOf<String>()
        repeat(50) {
            require(visited.add(page)) { "包子章节分页出现循环，未保存不完整章节" }
            val html = fetch(page, emptyMap())
            BaoziReader.pages(html, page).forEach { image -> images += image; rememberHeaders(image, mapOf("Referer" to page)) }
            require(images.size <= 5000) { "单章图片超过 5000 张上限" }
            page = BaoziReader.nextPage(html, page) ?: return images.toList()
        }
        error("包子章节分页超过上限，未保存不完整章节")
    }
    override fun imageCandidates(url: String) = BaoziReader.imageCandidates(url)
}

enum class MangaReadability(val label: String) {
    UNTESTED("未检测"), SEARCHABLE("可搜索"), DIRECTORY("目录可读"), READABLE("图片可读"),
    PARTIAL("部分图片可读"), VERIFICATION("需要登录或验证"), UNSUPPORTED("规则暂不支持"),
    UNREACHABLE("站点连接失败"), FAILED("读取失败")
}
