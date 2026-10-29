package com.shengzimao.reader.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 2026-10-27 从分享入口的 HTTPS 302 Location 实测核验；只用于入口网络故障。 */
internal object MangaImportRoutes {
    const val NOTICE = "原分享入口暂不可达，已使用 2026-10-27 核验的公开 JSON 直链。请核对源列表后导入；原站入口是否恢复仍取决于网络。"
    private val paths = mapOf(
        "69" to "202603/12870_dc8508fc28121c857aef81b9361eabe7.json",
        "70" to "202603/12870_fd882bbf3b7c8c2c9648da9d9ea84702.json",
        "71" to "202605/13518_4d301baa5a35b308154bcfa3e81d3abb.json",
    )

    fun candidates(address: String): List<String> {
        val url = address.toHttpUrlOrNull() ?: return emptyList()
        if (url.scheme != "https" || url.port != 443 || url.host !in setOf("www.yckceo.com", "yckceo.com") ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedQuery != null) return emptyList()
        val id = Regex("^/yiciyuan/tuyuans/json/id/(69|70|71)\\.json$").matchEntire(url.encodedPath)?.groupValues?.get(1)
            ?: return emptyList()
        val path = paths.getValue(id)
        // 70/71 在两个公开 CDN 上的内容 SHA-256 一致；69 的 cdn 线路实测不可用，不加入。
        return listOf("https://gcore.jsdelivr.net/gh/mumuceo/file01/$path") +
            if (id == "69") emptyList() else listOf("https://cdn.jsdelivr.net/gh/mumuceo/file01/$path")
    }
}
