package com.shengzimao.reader.mobile.data

import okhttp3.HttpUrl

/** Shared import/execution policy; source rules cannot override connection framing or Host. */
internal object MangaRequestHeaders {
    private val allowed = setOf("user-agent", "referer", "accept", "accept-language", "content-type",
        "platform", "region", "version", "webp", "x-requested-with", "origin", "cookie", "authorization")

    fun validate(name: String, value: String) {
        require(name.lowercase() in allowed) { "源请求头 $name 暂不支持" }
        require(value.length <= 2048 && value.all { it == '\t' || it.code in 32..126 }) { "源请求头无效：$name" }
    }

    fun sameOrigin(from: HttpUrl, to: HttpUrl) = from.scheme == to.scheme && from.host == to.host && from.port == to.port

    fun scoped(headers: Map<String, String>, allowCredentials: Boolean): Map<String, String> {
        headers.forEach { (name, value) -> validate(name, value) }
        return headers.filterKeys { allowCredentials || (!it.equals("Cookie", true) && !it.equals("Authorization", true)) }
    }
}
