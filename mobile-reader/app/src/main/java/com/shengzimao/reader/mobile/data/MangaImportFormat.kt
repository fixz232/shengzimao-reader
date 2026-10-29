package com.shengzimao.reader.mobile.data

import org.json.JSONArray

/** Identify incompatible plugin protocols before treating them as malformed native rules. */
internal object MangaImportFormat {
    fun textError(text: String): String? = when {
        text.startsWith("PK\u0003\u0004") -> "这是 APK / ZIP 二进制文件，不是漫画源 JSON。Mihon 扩展需要对应的 Android 插件运行时，本版本不能直接安装该扩展。"
        !text.startsWith("{") && !text.startsWith("[") && Regex("\\bextends\\s+ComicSource\\b").containsMatchIn(text) ->
            "这是 Venera 的 JavaScript 漫画源，需要其 ComicSource 插件 API；不能当作异次元 JSON 导入。本版本请使用异次元或本应用格式的源。"
        else -> null
    }

    fun jsonError(items: JSONArray): String? {
        val objects = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
        if (objects.any { it.has("pkg") && it.has("apk") }) return "这是 Mihon / Tachiyomi 扩展仓库索引，里面是 APK 插件信息，不是可执行的异次元规则。本版本尚未接入其扩展运行时，请使用兼容的漫画源 JSON。"
        if (objects.any { it.has("key") && it.has("fileName") && it.optString("fileName").endsWith(".js", true) }) return "这是 Venera 的源仓库索引，实际规则位于 JavaScript 脚本中。本版本尚未接入其插件运行时，请使用异次元或本应用格式的源；不能只转换名称和地址。"
        if (objects.any { it.optJSONObject("meta")?.has("signingKey") == true }) return "这是 Mihon 扩展仓库元信息，不是漫画源规则。需要配套的扩展仓库及插件运行时，本版本不能直接导入。"
        return null
    }
}
