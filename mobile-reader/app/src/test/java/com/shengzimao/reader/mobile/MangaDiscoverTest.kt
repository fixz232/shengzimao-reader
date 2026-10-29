package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaDiscover
import com.shengzimao.reader.mobile.data.MangaSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫源 discover（排行榜/分类）规则的解析。
 *
 * 关键约束：discover 是**可选增强**。配置写坏、缺字段、类型不对，都必须返回 null
 * 而不是抛异常——否则一个源的错误配置会让整个源无法使用。
 */
class MangaDiscoverTest {

    private fun source(definition: String) = MangaSource(
        id = "test",
        name = "测试源",
        baseUrl = "https://example.invalid",
        definition = JSONObject(definition).apply {
            put("search", JSONObject().apply {
                put("url", "/s?q={query}")
                put("items", ".item")
                put("title", "a@title")
                put("link", "a@href")
            })
            put("detail", JSONObject().apply { put("chapters", ".ch") })
            put("reader", JSONObject().apply { put("pages", ".img") })
        }.toString(),
    )

    @Test
    fun `discovers groups correctly`() {
        val s = source(
            """
            {
              "id": "test", "name": "测试源", "baseUrl": "https://example.invalid",
              "discover": {
                "groups": [
                  { "name": "排行榜", "entries": [
                      { "name": "人气榜", "url": "/rank/hot", "page": "?page={page}" },
                      { "name": "新作榜", "url": "/rank/new" }
                  ]},
                  { "name": "分类", "entries": [ { "name": "热血", "url": "/tag/action" } ] }
                ],
                "items": ".comic-list li",
                "title": "a@title",
                "link": "a@href",
                "cover": "img@src"
              }
            }
            """.trimIndent(),
        )
        val discover = MangaDiscover.from(s)
        assertNotNull(discover)
        assertEquals(2, discover!!.groups.size)
        assertEquals("排行榜", discover.groups[0].name)
        assertEquals(2, discover.groups[0].entries.size)
        assertEquals("?page={page}", discover.groups[0].entries[0].page)
        assertEquals("img@src", discover.cover)
    }

    @Test
    fun `missing discover returns null instead of throwing`() {
        val s = source("""{"id":"test","name":"测试源","baseUrl":"https://example.invalid"}""")
        assertNull(MangaDiscover.from(s))
    }

    @Test
    fun `malformed discover returns null`() {
        // 缺 items / title / link 这三项必需规则。
        val s = source(
            """
            {
              "id": "test", "name": "测试源", "baseUrl": "https://example.invalid",
              "discover": { "groups": [ { "name": "排行榜", "entries": [ { "name": "人气", "url": "/x" } ] } ] }
            }
            """.trimIndent(),
        )
        assertNull(MangaDiscover.from(s))
    }

    @Test
    fun `entries with blank name or url are dropped`() {
        val s = source(
            """
            {
              "id": "test", "name": "测试源", "baseUrl": "https://example.invalid",
              "discover": {
                "groups": [ { "name": "排行榜", "entries": [
                    { "name": "", "url": "/a" },
                    { "name": "有效", "url": "/b" },
                    { "name": "缺链接", "url": "" }
                ]}],
                "items": ".x", "title": "a", "link": "b"
              }
            }
            """.trimIndent(),
        )
        val discover = MangaDiscover.from(s)
        assertNotNull(discover)
        assertEquals(1, discover!!.groups[0].entries.size)
        assertEquals("有效", discover.groups[0].entries[0].name)
    }

    @Test
    fun `fallback keywords are non empty and distinct`() {
        val labels = MangaDiscover.FALLBACK_KEYWORDS.map { it.second }
        assertTrue(labels.isNotEmpty())
        assertEquals(labels.size, labels.distinct().size)
        // 降级方案的入口名不能空，否则界面上会出现空胶囊。
        assertTrue(MangaDiscover.FALLBACK_KEYWORDS.all { it.first.isNotBlank() && it.second.isNotBlank() })
    }
}
