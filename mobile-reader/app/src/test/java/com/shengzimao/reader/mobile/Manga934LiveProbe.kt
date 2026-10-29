package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaEngine
import com.shengzimao.reader.mobile.data.MangaStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/** 手动真实网络探针；不属于常规单元测试，也不进入正式安装包。 */
object Manga934LiveProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Files.createTempDirectory("manga934-qa-").toFile()
        try {
            val engine = MangaEngine(MangaStore(root))
            val source = engine.importUrl("https://www.yckceo.com/yiciyuan/tuyuan/json/id/934.json").single()
            check(source.enabled && source.legacy != null)
            val books = engine.search(source, "魔咒", 1)
            check(books.isNotEmpty()) { "站点未返回可解析的搜索结果" }
            check(books.any { it.title.contains("魔咒") }) { "未命中指定关键词" }
            println("MANGA_934_LIVE_SEARCH_OK imported=1 results=${books.size} matchedKeyword=true legacyPost=true")
        } finally {
            root.deleteRecursively()
        }
    }
}
