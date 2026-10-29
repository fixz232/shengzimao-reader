package com.shengzimao.reader.mobile.data

import org.json.JSONObject

/**
 * 漫画源的「发现」能力：排行榜 / 分类。
 *
 * 现状约束：漫画源规则原本只有 search / detail / reader 三段，没有「无关键词浏览」的能力。
 * 这里新增一个**完全可选**的 `discover` 段：
 *
 * ```json
 * "discover": {
 *   "groups": [
 *     { "name": "排行榜", "entries": [
 *         { "name": "人气榜", "url": "/rank/hot", "page": "?page={page}" },
 *         { "name": "新作榜", "url": "/rank/new" }
 *     ]},
 *     { "name": "分类", "entries": [
 *         { "name": "热血", "url": "/tag/action" }
 *     ]}
 *   ],
 *   "items": ".comic-list li",
 *   "title": "a@title",
 *   "link": "a@href",
 *   "cover": "img@src",
 *   "author": ".author"
 * }
 * ```
 *
 * 关键是 [items] / [title] / [link] 复用 search 段的选择器语法与解析器，
 * 因此不需要改动引擎的取图逻辑。源没有声明 discover 时，界面会退回
 * 「按分类关键词搜索」的降级方案，并明确标注哪些是真实榜单、哪些是搜索。
 */
data class MangaDiscoverEntry(
    val name: String,
    val url: String,
    /** 翻页后缀，支持 {page}；为空表示不支持翻页。 */
    val page: String = "",
)

data class MangaDiscoverGroup(
    val name: String,
    val entries: List<MangaDiscoverEntry>,
)

data class MangaDiscover(
    val groups: List<MangaDiscoverGroup>,
    val items: String,
    val title: String,
    val link: String,
    val cover: String = "",
    val author: String = "",
) {
    val isEmpty: Boolean get() = groups.isEmpty()

    companion object {
        /** 单个源最多保留的条目数，防止异常配置拖慢界面。 */
        private const val MAX_GROUPS = 12
        private const val MAX_ENTRIES = 40

        /**
         * 从源规则里解析 discover 段。没有该段或格式不合法时返回 null（不抛错）：
         * discover 是增强能力，配置写坏不应该让整个源不可用。
         */
        fun from(source: MangaSource): MangaDiscover? = runCatching {
            val rule = source.rules.optJSONObject("discover") ?: return null
            val groupsJson = rule.optJSONArray("groups") ?: return null
            val groups = ArrayList<MangaDiscoverGroup>()
            for (i in 0 until minOf(groupsJson.length(), MAX_GROUPS)) {
                val group = groupsJson.optJSONObject(i) ?: continue
                val name = group.optString("name").trim().ifBlank { "未命名" }
                val entriesJson = group.optJSONArray("entries") ?: continue
                val entries = ArrayList<MangaDiscoverEntry>()
                for (e in 0 until minOf(entriesJson.length(), MAX_ENTRIES)) {
                    val entry = entriesJson.optJSONObject(e) ?: continue
                    val entryName = entry.optString("name").trim()
                    val url = entry.optString("url").trim()
                    if (entryName.isBlank() || url.isBlank()) continue
                    entries += MangaDiscoverEntry(
                        name = entryName.take(60),
                        url = url.take(2000),
                        page = entry.optString("page").trim().take(200),
                    )
                }
                if (entries.isNotEmpty()) groups += MangaDiscoverGroup(name.take(40), entries)
            }
            val items = rule.optString("items").trim()
            val title = rule.optString("title").trim()
            val link = rule.optString("link").trim()
            if (groups.isEmpty() || items.isBlank() || title.isBlank() || link.isBlank()) return null
            MangaDiscover(
                groups = groups,
                items = items,
                title = title,
                link = link,
                cover = rule.optString("cover").trim(),
                author = rule.optString("author").trim(),
            )
        }.getOrNull()

        /**
         * 降级方案：源没有 discover 规则时，用常用分类关键词代替。
         *
         * 这些不是真正的「榜单」——它们是搜索关键词，结果取决于源站如何索引。
         * 界面必须明确标注这一点，不能让用户以为看到了官方排行榜。
         */
        val FALLBACK_KEYWORDS: List<Pair<String, String>> = listOf(
            "排行" to "排行榜",
            "热门" to "热门",
            "完结" to "已完结",
            "连载" to "连载中",
            "热血" to "热血",
            "恋爱" to "恋爱",
            "搞笑" to "搞笑",
            "奇幻" to "奇幻",
            "悬疑" to "悬疑",
            "校园" to "校园",
        )
    }
}
