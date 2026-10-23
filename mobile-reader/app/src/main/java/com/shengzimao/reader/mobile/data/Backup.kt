package com.shengzimao.reader.mobile.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份：单个 `.szmbak`（zip），结构与桌面版 app/backup.cjs 一致，便于手机与电脑之间互相恢复：
 *
 *   library.json      —— 索引（books / settings / activeId）
 *   books/<id>.txt    —— 正文
 *   README.txt        —— 说明
 *
 * 在线使用声明不进备份：换设备恢复后需要重新确认。
 */
object Backup {

    const val APP = "shengzimao-reader-mobile"
    private const val MAX_ENTRY = 512L * 1024 * 1024
    private val SAFE_FILE = Regex("^[a-zA-Z0-9-]{8,64}\\.txt$")
    private val SAFE_ID = Regex("^[a-zA-Z0-9-]{8,64}$")
    private const val MAX_TEXT_ENTRY = 128L * 1024 * 1024
    private const val MAX_TOTAL_TEXT = 256L * 1024 * 1024

    data class ExportReport(val books: Int, val included: Int, val missing: Int)

    data class ImportReport(
        val imported: Int,
        val repaired: Int,
        val skipped: Int,
        val invalid: Int,
        val missingText: Int,
        val total: Int,
    )

    fun export(store: LibraryStore, library: Library, settingsJson: String?, out: java.io.OutputStream): ExportReport {
        var included = 0
        var missing = 0
        val index = JSONObject().apply {
            put("version", 1)
            put("exportedAt", java.time.Instant.now().toString())
            put("app", APP)
            put("activeId", library.activeId)
            put("books", JSONObject(LibraryJson.encode(library)).optJSONArray("books") ?: JSONArray())
            if (!settingsJson.isNullOrBlank()) put("settings", JSONObject(settingsJson))
        }
        java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
            for (book in library.books) {
                if (!store.exists(book)) {
                    missing++
                    continue
                }
                zip.putNextEntry(java.util.zip.ZipEntry("books/${book.file}"))
                store.textFile(book).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                included++
            }
            zip.putNextEntry(java.util.zip.ZipEntry("library.json"))
            zip.write(index.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("README.txt"))
            zip.write(
                ("圣子猫阅读 · 手机版书库备份\n" +
                    "包含正文、阅读进度、书签与阅读统计。\n" +
                    "空书库恢复会一并恢复阅读设置；已有书库恢复为合并，重复条目跳过，正文缺失的条目会被修复。\n").toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        return ExportReport(library.books.size, included, missing)
    }

    fun import(store: LibraryStore, library: Library, input: java.io.InputStream): ImportReport {
        val texts = HashMap<String, ByteArray>()
        var indexJson: String? = null
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            var count = 0
            var expanded = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                if (count > 50_000) throw IllegalStateException("备份条目过多。")
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    when {
                        name == "library.json" -> indexJson = readLimited(zip, 32L * 1024 * 1024).toString(Charsets.UTF_8)
                        name.startsWith("books/") && name.endsWith(".txt") -> {
                            val fileName = name.removePrefix("books/")
                            val bytes = readLimited(zip, MAX_TEXT_ENTRY)
                            expanded += bytes.size
                            if (expanded > MAX_TOTAL_TEXT) throw IllegalStateException("备份解压内容超过安全上限。")
                            if (SAFE_FILE.matches(fileName)) texts[fileName] = bytes
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val raw = indexJson ?: throw IllegalStateException("备份文件缺少 library.json，可能不是本应用的备份。")
        val payload = runCatching { JSONObject(raw) }.getOrElse { throw IllegalStateException("备份中的 library.json 已损坏。") }
        val records = payload.optJSONArray("books") ?: throw IllegalStateException("备份中的书库索引格式不正确。")
        if (records.length() > 20000) throw IllegalStateException("备份书籍数量超过上限。")

        val byId = library.books.associateBy { it.id }.toMutableMap()
        val order = library.books.map { it.id }.toMutableList()
        var imported = 0
        var repaired = 0
        var skipped = 0
        var invalid = 0
        var missingText = 0

        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i)
            val id = record?.optString("id")?.trim().orEmpty()
            val title = record?.optString("title")?.trim().orEmpty()
            if (record == null || !SAFE_ID.matches(id) || title.isEmpty()) {
                invalid++
                continue
            }
            val previous = byId[id]
            if (previous != null && store.exists(previous)) {
                skipped++
                continue
            }
            val bytes = texts[record.optString("file")]
            if (bytes == null) missingText++
            val parsed = runCatching {
                LibraryJson.decode("""{"version":1,"books":[${record}],"activeId":""}""").books.firstOrNull()
            }.getOrNull()
            if (parsed == null) {
                invalid++
                continue
            }
            val text = bytes?.toString(Charsets.UTF_8).orEmpty()
            val file = if (bytes != null) store.writeText(id, text) else ""
            val merged = parsed.copy(
                id = id,
                file = file,
                length = if (bytes != null) text.length else parsed.length,
                position = if (bytes != null) parsed.position.coerceIn(0, maxOf(0, text.length - 1)) else parsed.position,
                chapters = if (bytes != null && parsed.chapters.isEmpty()) ChapterParser.find(text) else parsed.chapters,
                digest = if (bytes != null) LibraryStore.digest(text) else parsed.digest,
                bookmarks = if (bytes != null) parsed.bookmarks.map { it.copy(position = it.position.coerceIn(0, maxOf(0, text.length - 1))) } else parsed.bookmarks,
                missing = bytes == null,
            )
            if (previous == null) order.add(id)
            byId[id] = merged
            if (previous == null) imported++ else repaired++
        }
        val mergedLibrary = library.copy(books = order.mapNotNull { byId[it] })
        store.save(mergedLibrary)
        return ImportReport(imported, repaired, skipped, invalid, missingText, records.length())
    }

    private fun readLimited(zip: java.util.zip.ZipInputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IllegalStateException("备份条目过大。")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
