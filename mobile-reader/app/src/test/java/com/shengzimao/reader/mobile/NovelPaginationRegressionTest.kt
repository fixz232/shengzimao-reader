package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NovelPaginationRegressionTest {
    private val base = "https://pagination.invalid"
    private fun post(body: String) = "$base/api," + JSONObject().put("method", "POST").put("body", body)
    private fun rule() = JSONObject().put("bookSourceName", "分页回归").put("bookSourceUrl", base)
        .put("ruleBookInfo", JSONObject().put("name", "$.name").put("tocUrl", "$.toc"))
        .put("ruleToc", JSONObject().put("chapterList", "$.items").put("chapterName", "$.title")
            .put("chapterUrl", "$.url").put("nextTocUrl", "$.next"))
        .put("ruleContent", JSONObject().put("content", "$.content").put("nextContentUrl", "$.next"))

    private fun <T> fixture(raw: JSONObject = rule(), fetch: (NovelRequest) -> JSONObject, test: (LegadoNovelEngine) -> T): T {
        val dir = Files.createTempDirectory("post-pagination-regression").toFile()
        try {
            return test(LegadoNovelEngine(NovelLegadoRules.normalize(raw), dir) { request -> NovelPage(request.url, fetch(request).toString()) })
        } finally { dir.deleteRecursively() }
    }

    @Test fun sameEndpointPostTocReadsEveryPageAndStopsRealCycle() {
        val bodies = mutableListOf<String?>()
        fixture(fetch = { request ->
            if (request.url.endsWith("/book")) JSONObject().put("name", "测试").put("toc", post("toc=1"))
            else {
                bodies += request.body
                val second = request.body == "toc=2"
                JSONObject().put("items", JSONArray().put(JSONObject().put("title", if (second) "第二章" else "第一章")
                    .put("url", "$base/chapter/${if (second) 2 else 1}")))
                    .put("next", post(if (second) "toc=1" else "toc=2"))
            }
        }) { engine ->
            assertEquals(listOf("第一章", "第二章"), engine.details("$base/book").toc.map { it.title })
            assertEquals(listOf("toc=1", "toc=2"), bodies)
        }
    }

    @Test fun sameEndpointPostContentIncludesTailButDoesNotEnterAdjacentChapter() {
        val bodies = mutableListOf<String?>()
        fixture(fetch = { request ->
            bodies += request.body
            when (request.body) {
                "chapter=1&page=1" -> JSONObject().put("content", "正文开头").put("next", post("chapter=1&page=2"))
                "chapter=1&page=2" -> JSONObject().put("content", "正文结尾").put("next", post("chapter=2&page=1"))
                else -> error("不得读取其他章节：${request.body}")
            }
        }) { engine ->
            val body = engine.chapter("$base/book", OnlineChapter("第一章", post("chapter=1&page=1"), nextUrl = post("chapter=2&page=1")))
            assertTrue(body.contains("正文开头")); assertTrue(body.contains("正文结尾"))
            assertEquals(2, bodies.size)
        }
    }

    @Test fun headerOptionsCanDistinguishPagesOnTheSameEndpoint() {
        fun page(number: Int) = "$base/api," + JSONObject().put("headers", JSONObject().put("X-Page", number.toString()))
        val requests = mutableListOf<String?>()
        fixture(fetch = { request ->
            requests += request.headers["X-Page"]
            val first = request.headers["X-Page"] == "1"
            JSONObject().put("content", if (first) "第一页" else "第二页").put("next", page(if (first) 2 else 1))
        }) { engine ->
            val text = engine.chapter("$base/book", OnlineChapter("测试", page(1)))
            assertTrue(text.contains("第一页")); assertTrue(text.contains("第二页"))
            assertEquals(listOf("1", "2"), requests)
        }
    }

    @Test fun duplicateOptionsWithDifferentJsonOrderDoNotReloadPages() {
        var requests = 0
        fixture(fetch = { request ->
            requests++
            JSONObject().put("content", "单页正文").put("next", "$base/api,{\"body\":\"page=1\",\"method\":\"POST\"}")
        }) { engine ->
            assertTrue(engine.chapter("$base/book", OnlineChapter("单页", post("page=1"))).contains("单页正文"))
            assertEquals(1, requests)
        }
    }

    @Test fun requestIdentityKeepsBodyAndCanonicalizesHeaderCaseAndOrder() {
        val first = NovelRequest.parse(post("page=1"), base, headers = linkedMapOf("X-Token" to "one", "Accept" to "text/html"))
        val same = NovelRequest.parse(post("page=1"), base, headers = linkedMapOf("accept" to "text/html", "x-token" to "one"))
        assertEquals(first.identity, same.identity)
        assertNotEquals(first.identity, first.copy(body = "page=2").identity)
        assertNotEquals(first.identity, first.copy(headers = mapOf("X-Token" to "two")).identity)
        assertEquals(first.referenceIdentity, same.referenceIdentity)
    }

    @Test fun dynamicDefaultHeadersAreNotEvaluatedForUnrequestedNavigationTargets() {
        val counts = mutableListOf<String?>()
        val raw = rule().put("header", "@js: var count=Number(java.get('headers') || 0)+1; java.put('headers', String(count)); JSON.stringify({'X-Count':String(count)})")
        fixture(raw, { request ->
            counts += request.headers["X-Count"]
            JSONObject().put("content", "正文").put("next", if (request.url.endsWith("/current")) "$base/tail" else "$base/next")
        }) { engine ->
            assertTrue(engine.chapter("$base/book", OnlineChapter("当前", "$base/current", nextUrl = "$base/next")).contains("正文"))
            assertEquals(listOf("1", "2"), counts)
        }
    }

    @Test fun rotatingDefaultAuthenticationHeaderDoesNotTurnSelfLinkIntoFiftyPages() {
        var requests = 0
        val raw = rule().put("header", "@js: var count=Number(java.get('auth') || 0)+1; java.put('auth', String(count)); JSON.stringify({'X-Auth-Nonce':String(count)})")
        fixture(raw, { request ->
            requests++
            JSONObject().put("content", "完整单页正文").put("next", request.url)
        }) { engine ->
            assertTrue(engine.chapter("$base/book", OnlineChapter("单页", "$base/current")).contains("完整单页正文"))
            assertEquals(1, requests)
        }
    }

    @Test fun adjacentChapterDoesNotExecuteAnUnusedAuthenticationScript() {
        val raw = rule().put("header", "@js: var n=Number(java.get('auth') || 0)+1; java.put('auth', String(n)); if(n>2) throw new Error('unrequested authentication'); '{}'")
        var requests = 0
        fixture(raw, { request ->
            requests++
            JSONObject().put("content", "完整正文").put("next", if (request.url.endsWith("/current")) "$base/tail" else "$base/next")
        }) { engine ->
            assertTrue(engine.chapter("$base/book", OnlineChapter("当前", "$base/current", nextUrl = "$base/next")).contains("完整正文"))
            assertEquals(2, requests)
        }
    }

    @Test fun redirectAliasesCannotAppendTheSameResponseAgain() {
        val dir = Files.createTempDirectory("pagination-redirect-loop").toFile()
        var requests = 0
        try {
            val engine = LegadoNovelEngine(NovelLegadoRules.normalize(rule()), dir) {
                requests++
                NovelPage("$base/canonical", JSONObject().put("content", "重复正文").put("next", "$base/alias").toString())
            }
            val failure = runCatching { engine.chapter("$base/book", OnlineChapter("第一章", "$base/start")) }.exceptionOrNull()
            assertNotNull("不能把重定向循环当作完整章节返回", failure)
            assertTrue(failure!!.message.orEmpty().contains("分页出现循环"))
            assertEquals(2, requests)
        } finally { dir.deleteRecursively() }
    }
}
