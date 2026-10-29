package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 与旧小说备份共用 ZIP 容器。临时图片不导出，主动下载和本地图片可选。 */
object MangaBackup {
    private val safeAsset = Regex("comics/assets/[a-f0-9]{64}")
    private val secret = Regex("cookie|authorization|password|passwd|secret|token|credential|api.?key", RegexOption.IGNORE_CASE)
    internal fun redact(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { key -> if (!secret.containsMatchIn(key)) out.put(key, redact(value.get(key))) } }
        is JSONArray -> JSONArray().also { out -> (0 until value.length()).forEach { out.put(redact(value.get(it))) } }
        is String -> if (Regex("(?i)(cookie|authorization|password|token|secret|api.?key)\\s*[:=]").containsMatchIn(value)) "" else value
        else -> value
    }
    private fun assetKey(source: String, ref: String, context: MangaImageContext? = null): String =
        if (context == null) "$source|$ref" else "v2:" + mangaHash("$source|${context.key()}")

    fun write(zip: ZipOutputStream, store: MangaStore, settings: MangaSettings, includeImages: Boolean) {
        store.flushProgress(); store.migrateDownloads()
        val books = store.books()
        val files = linkedMapOf<String, File>()
        val mapping = JSONObject()
        books.forEach { book ->
            fun collect(ref: String, context: MangaImageContext? = null) {
                if (!includeImages || ref.isBlank()) return
                val file = runCatching { if (ref.startsWith("file:")) store.localImage(ref)
                    else store.cachedImage(book.sourceId, ref, context = context).takeIf { it.parentFile == store.downloadDir } }.getOrNull()
                if (file?.isFile == true) {
                    val key = assetKey(book.sourceId, ref, context)
                    val path = "comics/assets/" + mangaHash(key)
                    files[path] = file; mapping.put(key, path)
                }
            }
            book.chapters.forEach { chapter -> chapter.pages.forEachIndexed { page, ref -> collect(ref, MangaImageContext.of(book, chapter, page)) } }
            collect(book.cover)
        }
        require(files.values.sumOf { it.length() } <= 700L * 1024 * 1024) { "漫画图片超过 700 MB，请取消包含图片后导出，或分批保留原始漫画文件" }
        val metadata = JSONObject().put("version", 1).put("imageDecoderVersion", 1).put("imagesIncluded", includeImages)
            .put("cacheSchema", 2).put("books", JSONArray().also { a -> books.forEach { a.put(it.json().put("ruleState", "").also { j ->
                j.getJSONArray("chapters").let { c -> (0 until c.length()).forEach { c.getJSONObject(it).put("ruleState", "") } }
            }) } })
            .put("sources", JSONArray().also { a -> store.sources().forEach { a.put(redact(it.json())) } })
            .put("settings", JSONObject(ReadingPreferences.encode(settings))).put("assets", mapping)
        zip.putNextEntry(ZipEntry("comics/metadata.json")); zip.write(metadata.toString().toByteArray()); zip.closeEntry()
        files.forEach { (path, file) -> zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
    }

    class Restore(private val store: MangaStore, tempParent: File) : java.io.Closeable {
        private val staging = File(tempParent, "restore-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        private var metadata: JSONObject? = null
        fun accept(name: String, bytes: ByteArray) {
            if (name == "comics/metadata.json") metadata = JSONObject(bytes.toString(Charsets.UTF_8))
            else if (safeAsset.matches(name)) {
                val target = File(staging, name.substringAfterLast('/')); target.writeBytes(bytes)
                MangaStore.checkImage(target)
            }
        }
        fun finish(): Pair<Int, String?> {
            val j = metadata ?: return 0 to null
            require(j.optInt("version") == 1) { "不支持的漫库备份版本" }
            val sources = j.optJSONArray("sources") ?: JSONArray()
            require(sources.length() <= 2000) { "源数量超过上限" }
            val parsed = (0 until sources.length()).map { MangaSource.parse(sources.getJSONObject(it)) }
            val effectiveSources = (parsed + store.sources()).associateBy { it.id }
            fun cacheName(sourceId: String, ref: String, original: Boolean = false, context: MangaImageContext? = null): String {
                val spec = MangaDecoder.identify(MangaDecoder.rule(effectiveSources[sourceId]?.legacy))
                return MangaDecoder.cacheName(sourceId, ref, if (original && j.optInt("imageDecoderVersion") < 1) null else spec, context = context)
            }
            val assets = j.optJSONObject("assets") ?: JSONObject()
            assets.keys().forEach { key ->
                val path = assets.getString(key)
                require(safeAsset.matches(path) && File(staging, path.substringAfterLast('/')).isFile) { "漫画备份图片缺失或路径无效" }
            }
            val books = j.optJSONArray("books") ?: JSONArray(); require(books.length() <= 20000)
            val parsedBooks = (0 until books.length()).map { MangaBook.parse(books.getJSONObject(it)).also { book ->
                require(Regex("[A-Za-z0-9-]{8,80}").matches(book.id)) { "漫库 id 无效" }
            } }
            require(parsedBooks.map { it.id }.distinct().size == parsedBooks.size) { "漫库备份包含重复作品" }
            val existing = store.books().map { it.id }.toSet()
            val restored = mutableListOf<MangaBook>()
            val createdFiles = mutableListOf<File>()
            try {
            for (raw in parsedBooks) {
                if (raw.id in existing) continue
                fun restoreRef(ref: String, context: MangaImageContext? = null): String {
                    val scoped = if (j.optInt("cacheSchema") >= 2 && context != null) assets.optString(assetKey(raw.sourceId, ref, context)) else ""
                    val path = scoped.ifBlank { assets.optString(assetKey(raw.sourceId, ref)) }
                    if (!safeAsset.matches(path)) return if (ref.startsWith("file:")) "" else ref
                    val file = File(staging, path.substringAfterLast('/')); if (!file.isFile) return if (ref.startsWith("file:")) "" else ref
                    val target = if (raw.local) File(store.localDir, "${raw.id}/${file.name}") else File(store.downloadDir, cacheName(raw.sourceId, ref, original = true, context = context.takeIf { scoped.isNotBlank() }))
                    target.parentFile?.mkdirs(); if (!target.exists()) { createdFiles += target; file.copyTo(target) }
                    return if (raw.local) target.toURI().toString() else ref
                }
                val chapters = raw.chapters.map { chapter -> chapter.copy(pages = chapter.pages.mapIndexed { page, ref -> restoreRef(ref, MangaImageContext.of(raw, chapter, page)) }.filter { it.isNotBlank() }) }
                val complete = chapters.filter { c -> c.pages.isNotEmpty() && c.pages.withIndex().all { (page, ref) -> if (ref.startsWith("file:")) true else
                    File(store.downloadDir, cacheName(raw.sourceId, ref, context = MangaImageContext.of(raw, c, page))).isFile || File(store.downloadDir, cacheName(raw.sourceId, ref)).isFile } }.map { it.url }.toSet()
                restored += raw.copy(chapters = chapters, cover = restoreRef(raw.cover), pageReference = raw.chapters.getOrNull(raw.chapterIndex)?.let { restoreRef(raw.pageReference, MangaImageContext.of(raw, it, raw.pageIndex)) } ?: restoreRef(raw.pageReference),
                    bookmarks = raw.bookmarks.map { mark -> mark.copy(reference = raw.chapters.firstOrNull { it.url == mark.chapterUrl }?.let { restoreRef(mark.reference, MangaImageContext.of(raw, it, mark.page)) } ?: restoreRef(mark.reference)) }, cachedChapters = complete,
                    onlineOnly = !raw.local && (raw.onlineOnly || complete.size != chapters.size))
            }
            return store.mergeRestored(parsed, restored) to j.optJSONObject("settings")?.toString()
            } catch (failure: Exception) {
                createdFiles.forEach { it.delete() }
                throw failure
            }
        }
        override fun close() { staging.deleteRecursively() }
    }
}
