package com.shengzimao.reader.mobile.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

/** Boodo 的公开懒加载阅读页：按页面槽位调用同站只读取图接口，不执行网页脚本。 */
internal object BoodoReader {
    data class Chapter(val id: String, val aid: String, val count: Int)

    fun chapter(root: Element): Chapter? {
        val slots = root.select("#imgsec .reader-pic-slot[data-chapter-id][data-aid][data-pic-index]")
        if (slots.isEmpty()) return null
        require(slots.size <= 5000) { "单章图片超过 5000 张上限" }
        val first = slots.first()!!
        val id = first.attr("data-chapter-id"); val aid = first.attr("data-aid")
        require(id.matches(Regex("[0-9]{1,20}")) && aid.matches(Regex("[0-9]{1,20}"))) { "漫画页面缺少有效的章节标识" }
        require(slots.all { it.attr("data-chapter-id") == id && it.attr("data-aid") == aid } &&
            slots.map { it.attr("data-pic-index").toIntOrNull() }.sortedBy { it ?: -1 } == (0 until slots.size).toList()) {
            "漫画页面图片槽位不完整，请刷新章节"
        }
        return Chapter(id, aid, slots.size)
    }

    suspend fun pages(chapter: Chapter, url: String, fetch: suspend (String, String) -> String): List<String> = coroutineScope {
        val endpoint = URI(url).resolve("/api/comic/read/pics").toString()
        val permits = Semaphore(3)
        (0 until chapter.count step 5).map { offset -> async {
            permits.withPermit {
                val response = JSONObject(fetch(endpoint, "id=${chapter.id}&aid=${chapter.aid}&offset=$offset&limit=5"))
                require(response.optInt("code") == 1) { "站点暂未返回本章图片，请重试；需要登录或付费的章节请在原站阅读" }
                val data = response.getJSONObject("data")
                val pictures = data.getJSONArray("pic")
                require(data.optInt("total", chapter.count) == chapter.count && data.optInt("offset", offset) == offset &&
                    pictures.length() == minOf(5, chapter.count - offset)) { "站点返回的本章图片不完整，请重试" }
                (0 until pictures.length()).map { index ->
                    val image = mangaHttpUrl(URI(url).resolve(pictures.getJSONObject(index).getString("pic")).toString())
                    require(!mangaPlaceholder(image)) { "站点返回了加载占位图，请刷新章节" }
                    image
                }
            }
        } }.awaitAll().flatten()
    }
}

internal fun mangaPlaceholder(reference: String): Boolean = runCatching {
    GmhReader.placeholderImage(reference) || BaoziReader.placeholderImage(reference) ||
        URI(reference).path.orEmpty().substringAfterLast('/').lowercase() in setOf("load.gif", "loading.gif", "loading.svg", "loading.png")
}.getOrDefault(false)
