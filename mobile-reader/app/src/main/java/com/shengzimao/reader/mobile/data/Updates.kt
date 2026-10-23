package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 追更与下载入库的元数据处理，对应桌面版 app/updates.cjs。
 *
 * 关键约束（与桌面版一致）：
 * - 只在旧目录**可核对**时追加：源站目录不能变短、旧章节标题顺序不能变；
 * - 有 onlineChapters 清单时还要求旧章节 URL 完全一致；
 * - 正文按“旧文 + 分隔 + 新增”拼接，替换文件前校验摘要，失败回滚。
 */
object Updates {

    fun titleKey(value: String?): String =
        (value ?: "")
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFKC) }
            .trim()
            .replace(Regex("^(\\d+)\\s*\\.\\s*(.+)$")) { match -> "第${match.groupValues[1]}章${match.groupValues[2]}" }
            .replace(Regex("\\s+"), "")
            .lowercase()

    /** 把下载正文按在线目录映射成本地章节锚点。 */
    fun mapOnlineChapters(text: String, toc: List<OnlineChapter>): List<Chapter> {
        val lines = ArrayList<Pair<String, Int>>()
        var position = 0
        for (line in text.split('\n')) {
            if (line.isNotBlank()) lines.add(titleKey(line) to position)
            position += line.length + 1
        }
        var cursor = 0
        return toc.map { chapter ->
            while (cursor < lines.size && lines[cursor].first != titleKey(chapter.title)) cursor++
            if (cursor == lines.size) throw IllegalStateException("下载正文缺少章节「${chapter.title}」，已停止入库。")
            Chapter(chapter.title, lines[cursor].second).also { cursor++ }
        }
    }

    /** 校验引擎返回的详情；章节标题/URL 必须合法且不重复。 */
    fun validateDetails(json: JSONObject): Details {
        val book = json.optJSONObject("book") ?: throw IllegalStateException("源站未返回书籍信息。")
        val array = json.optJSONArray("toc") ?: throw IllegalStateException("源站未返回目录。")
        if (array.length() == 0 || array.length() > 30000) throw IllegalStateException("源站目录为空或超过 30000 章上限。")
        val seen = HashSet<String>()
        val toc = ArrayList<OnlineChapter>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: throw IllegalStateException("源站目录格式错误。")
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            if (title.isEmpty() || !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url) || !seen.add(url)) {
                throw IllegalStateException("源站目录存在空标题、重复章节或非法链接，已停止更新。")
            }
            toc.add(OnlineChapter(title.take(250), url.take(2000)))
        }
        return Details(
            book = OnlineResult(
                bookName = book.optString("bookName"),
                author = book.optString("author"),
                url = book.optString("url"),
                sourceName = book.optString("sourceName"),
                category = book.optString("category"),
                intro = book.optString("intro"),
                latestChapter = book.optString("latestChapter"),
                lastUpdateTime = book.optString("lastUpdateTime"),
                status = book.optString("status"),
            ),
            toc = toc,
        )
    }

    data class Details(val book: OnlineResult, val toc: List<OnlineChapter>) {
        val chapterCount: Int get() = toc.size
    }

    data class Plan(
        val id: String,
        val file: String,
        val digest: String,
        val details: Details,
        val start: Int,
        val chapters: List<OnlineChapter>,
    )

    /** 决定本次要追加哪些章节；任何不一致都抛错并保持正文不动。 */
    fun planUpdate(book: Book, local: List<Chapter>, details: Details): Plan {
        if (book.file.isEmpty()) throw IllegalStateException("正文缺失，请先重新关联或恢复备份。")
        val manifest = book.onlineChapters.ifEmpty { null }
        // 清单记录的是「上次入库时源站目录」，本地章节是「正文实际解析结果」，
        // 两者数量不一致说明正文与清单已经对不上，此时按任一方的长度追加都会错位。
        if (manifest != null && local.isNotEmpty() && local.size != manifest.size) {
            throw IllegalStateException("本地章节数与在线目录清单不一致，无法安全追加；现有正文未改动。")
        }
        val titles: List<String> = manifest?.map { it.title } ?: local.map { it.title }
        if (titles.isEmpty()) throw IllegalStateException("旧书未记录可核对目录，请先保留备份并重新从原站入库。")
        if (titles.size > details.toc.size) throw IllegalStateException("源站目录变短，已保留现有正文并停止追加。")
        for (i in titles.indices) {
            if (titleKey(titles[i]) != titleKey(details.toc[i].title)) {
                throw IllegalStateException("源站目录顺序或旧章节已变化，无法安全追加；现有正文未改动。")
            }
            if (manifest != null && manifest[i].url != details.toc[i].url) {
                throw IllegalStateException("源站旧章节链接已变化，无法安全追加；现有正文未改动。")
            }
        }
        val start = titles.size
        return Plan(
            id = book.id,
            file = book.file,
            digest = book.digest,
            details = details,
            start = start,
            chapters = details.toc.drop(start),
        )
    }

    /** 结果：合并后的正文与新章节锚点。写入磁盘由调用方完成。 */
    data class MergeResult(val text: String, val chapters: List<Chapter>, val added: Int)

    fun merge(original: String, addition: String, toc: List<OnlineChapter>): MergeResult {
        if (addition.isBlank()) throw IllegalStateException("未取得完整新增正文，现有正文未改动。")
        val separator = if (original.endsWith("\n\n")) "" else "\n\n"
        val text = original + separator + addition
        // UTF-8 下每个 UTF-16 码元最多 3 字节：先用上界判，超限再精确计算。
        if (text.length * 3L > 128L * 1024 * 1024 && text.toByteArray(Charsets.UTF_8).size > 128 * 1024 * 1024) {
            throw IllegalStateException("更新后正文超过 128 MB 上限。")
        }
        return MergeResult(text, mapOnlineChapters(text, toc), toc.size)
    }
}

/** 扩展字段：在线目录清单。放在单独文件避免 Book 数据类膨胀。 */
val Book.onlineChapters: List<OnlineChapter>
    get() = onlineManifest
        .let { raw -> runCatching { OnlineChapterCodec.decode(raw) }.getOrDefault(emptyList()) }

object OnlineChapterCodec {
    fun encode(chapters: List<OnlineChapter>): String {
        val array = JSONArray()
        chapters.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        return array.toString()
    }

    fun decode(raw: String): List<OnlineChapter> {
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title")
            val url = item.optString("url")
            if (title.isEmpty() || url.isEmpty()) null else OnlineChapter(title, url)
        }
    }
}
