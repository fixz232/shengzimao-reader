package com.shengzimao.reader.mobile.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

class MangaStore(val root: File) {
    val adapters = MangaAdapterRegistry(File(root, "adapter-packs.json"))
    val imageDir = File(root, "images").apply { mkdirs() }
    val downloadDir = File(root, "downloads").apply { mkdirs() }
    val localDir = File(root, "local").apply { mkdirs() }
    private val libraryFile = File(root, "library.json")
    private val sourcesFile = File(root, "sources.json")
    private var decoderSpecs: Map<String, MangaDecoder.Spec>? = null
    private var decoderAdapterRevision = -1L
    private var configuredSourceIds = emptySet<String>()
    private var imageOwnersCache: Map<Pair<String, String>, List<MangaImageContext>>? = null

    @Synchronized private fun imageOwners(source: String, url: String): List<MangaImageContext> {
        val cache = imageOwnersCache ?: buildMap<Pair<String, String>, MutableList<MangaImageContext>> {
            books().filterNot { it.local }.forEach { book -> book.chapters.forEach { chapter -> chapter.pages.forEachIndexed { page, ref ->
                if (ref.startsWith("http")) getOrPut(book.sourceId to ref) { arrayListOf() }.add(MangaImageContext.of(book, chapter, page))
            } } }
        }.also { imageOwnersCache = it }
        return cache[source to url].orEmpty()
    }

    /** 图片最后访问时间，用于 LRU 淘汰。内存表 + 定期落盘，避免每次读图都写磁盘。 */
    private val imageAccess = ConcurrentHashMap<String, Long>()

    @Synchronized fun books(): List<MangaBook> = read(libraryFile).let { a -> (0 until a.length()).mapNotNull { runCatching { MangaBook.parse(a.getJSONObject(it)) }.getOrNull() } }
    @Synchronized fun sources(): List<MangaSource> = read(sourcesFile).let { a -> (0 until a.length()).mapNotNull { runCatching { MangaSource.parse(a.getJSONObject(it)) }.getOrNull() } }
    @Synchronized fun saveSources(sources: List<MangaSource>) {
        sources.forEach(adapters::effective)
        atomicJson(sourcesFile, JSONArray().also { a -> sources.forEach { a.put(it.json()) } }.toString())
        decoderSpecs = null
    }
    @Synchronized fun mutateSources(transform: (List<MangaSource>) -> List<MangaSource>) = saveSources(transform(sources()))
    @Synchronized fun upsert(book: MangaBook) {
        val previous = books()
        val old = previous.firstOrNull { it.id == book.id }
        if (old == null || old.chapters != book.chapters || old.url != book.url || old.sourceId != book.sourceId) imageOwnersCache = null
        val next = if (previous.any { it.id == book.id }) previous.map { if (it.id == book.id) book else it } else listOf(book) + previous
        atomicJson(libraryFile, JSONArray().also { a -> next.forEach { a.put(it.json()) } }.toString())
    }
    @Synchronized fun patch(id: String, transform: (MangaBook) -> MangaBook): MangaBook? {
        val book = books().firstOrNull { it.id == id } ?: return null
        return transform(book).also { upsert(it) }
    }

    /**
     * 只更新阅读进度，不重写整库。
     *
     * 之前 savePosition → patch → upsert 每次都把整个 library.json 重新序列化；
     * 书多时条漫连续滑动会明显卡顿。这里内存中改一本书的进度，
     * 并让调用方合并写入（见 [flushProgress]）。
     */
    private val pendingProgress = ConcurrentHashMap<String, Progress>()

    private data class Progress(val chapter: Int, val page: Int, val at: Long, val fraction: Float, val reference: String)

    @Synchronized
    fun stagePosition(id: String, chapter: Int, page: Int, fraction: Float = 0f, reference: String = "") {
        pendingProgress[id] = Progress(chapter, page.coerceAtLeast(0), System.currentTimeMillis(), fraction.coerceIn(0f, 1f), reference)
    }

    /** 把暂存的进度合并写回索引；只有一次全量序列化。 */
    @Synchronized
    fun flushProgress() {
        if (pendingProgress.isEmpty()) return
        val updates = HashMap(pendingProgress)
        val all = books()
        val next = all.map { book ->
            val change = updates[book.id] ?: return@map book
            if (change.chapter in book.chapters.indices) book.copy(chapterIndex = change.chapter, pageIndex = change.page,
                pageFraction = change.fraction, pageReference = change.reference, lastReadAt = change.at,
                newChapters = if (book.unreadChapterUrls.isEmpty()) minOf(book.newChapters, (book.chapters.size - change.chapter - 1).coerceAtLeast(0))
                    else (book.unreadChapterUrls - book.chapters[change.chapter].url).size,
                unreadChapterUrls = book.unreadChapterUrls - book.chapters[change.chapter].url)
            else book
        }
        atomicJson(libraryFile, JSONArray().also { a -> next.forEach { a.put(it.json()) } }.toString())
        pendingProgress.keys.removeAll(updates.keys)
    }

    @Synchronized fun remove(id: String) {
        val all = books()
        val target = all.firstOrNull { it.id == id } ?: return
        if (!target.local) deleteDownloads(id)
        atomicJson(libraryFile, JSONArray().also { a -> all.filterNot { it.id == id }.forEach { a.put(it.json()) } }.toString())
        pendingProgress.remove(id)
        imageOwnersCache = null
        if (target.local && Regex("[A-Za-z0-9-]{8,80}").matches(id)) File(localDir, id).deleteRecursively()
    }

    /** 恢复前已验证所有作品和图片；两个索引写入任一失败时恢复原索引。 */
    @Synchronized fun mergeRestored(restoredSources: List<MangaSource>, restoredBooks: List<MangaBook>): Int {
        val originalSources = sources()
        val originalBooks = books()
        val extraSources = restoredSources.filter { candidate -> originalSources.none { it.id == candidate.id } }.distinctBy { it.id }
        val extraBooks = restoredBooks.filter { candidate -> originalBooks.none { it.id == candidate.id } }.distinctBy { it.id }
        fun sourceJson(items: List<MangaSource>) = JSONArray().also { a -> items.forEach { a.put(it.json()) } }.toString()
        fun bookJson(items: List<MangaBook>) = JSONArray().also { a -> items.forEach { a.put(it.json()) } }.toString()
        try {
            atomicJson(sourcesFile, sourceJson(originalSources + extraSources))
            decoderSpecs = null
            atomicJson(libraryFile, bookJson(originalBooks + extraBooks))
            imageOwnersCache = null
        } catch (failure: Exception) {
            runCatching { atomicJson(sourcesFile, sourceJson(originalSources)) }.exceptionOrNull()?.let(failure::addSuppressed)
            decoderSpecs = null
            runCatching { atomicJson(libraryFile, bookJson(originalBooks)) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return extraBooks.size
    }
    private fun read(file: File): JSONArray {
        if (!file.isFile) return JSONArray()
        return try { JSONArray(file.readText()) } catch (failure: Exception) { throw IllegalStateException("漫画索引无法读取，已保留原文件：${file.name}", failure) }
    }

    fun localImage(reference: String): File {
        val file = File(URIPath(reference)).canonicalFile
        require(file.path.startsWith(localDir.canonicalPath + File.separator) && file.isFile) { "本地漫画图片不存在或不在漫画目录中" }
        return file
    }
    @Synchronized internal fun decoderSpec(sourceId: String): MangaDecoder.Spec? {
        if (decoderAdapterRevision != adapters.revision) { decoderSpecs = null; decoderAdapterRevision = adapters.revision }
        val specs = decoderSpecs ?: sources().map(adapters::effective).also { configuredSourceIds = it.map { source -> source.id }.toSet() }.mapNotNull { source ->
            MangaDecoder.identify(MangaDecoder.rule(source.legacy))?.let { source.id to it }
        }.toMap().also { decoderSpecs = it }
        return specs[sourceId]
    }
    internal fun imageName(sourceId: String, url: String, cover: Boolean = false, context: MangaImageContext? = null) =
        MangaDecoder.cacheName(sourceId, if (url.startsWith("http")) mangaHttpUrl(url) else url, decoderSpec(sourceId), cover, context)

    @Synchronized private fun sourceRemoved(sourceId: String): Boolean {
        decoderSpec(sourceId)
        return sourceId !in configuredSourceIds
    }

    private fun imageNames(sourceId: String, url: String, context: MangaImageContext? = null): List<String> =
        (listOf(imageName(sourceId, url, context = context), imageName(sourceId, url), mangaHash("$sourceId|$url")) +
            if (sourceRemoved(sourceId)) MangaDecoder.supported.flatMap {
                listOf(MangaDecoder.cacheName(sourceId, if (url.startsWith("http")) mangaHttpUrl(url) else url, it, context = context), MangaDecoder.cacheName(sourceId, if (url.startsWith("http")) mangaHttpUrl(url) else url, it))
            } else emptyList()).distinct()

    fun cachedImage(sourceId: String, url: String, cover: Boolean = false, context: MangaImageContext? = null): File {
        // 解密版本进入缓存键，避免把旧版有效但尚未还原的拼图当成完成品。
        // 原始缓存保留在原路径；不触碰其他源的已下载图片。
        val name = imageName(sourceId, url, cover, context)
        val primary = File(downloadDir, name).takeIf { it.isFile && it.length() > 0 } ?: File(imageDir, name)
        if (primary.isFile && !sourceRemoved(sourceId)) return primary
        if (context == null && !cover && url.startsWith("http")) {
            val owners = imageOwners(sourceId, url)
            if (owners.size == 1) {
                val scopedNames = (listOf(MangaDecoder.cacheName(sourceId, mangaHttpUrl(url), decoderSpec(sourceId), context = owners.single())) +
                    if (sourceRemoved(sourceId)) MangaDecoder.supported.map { MangaDecoder.cacheName(sourceId, mangaHttpUrl(url), it, context = owners.single()) } else emptyList())
                for (dir in listOf(downloadDir, imageDir)) for (scoped in scopedNames) File(dir, scoped).let { if (it.isFile && it.length() > 0) return it }
            }
        }
        if (context != null && !cover) {
            // Migrate only an unambiguous old page. Never share a URL-only cache across chapters.
            val legacy = cachedImage(sourceId, url, cover)
            if (legacy.isFile) {
                val owners = imageOwners(sourceId, url)
                if (owners.size == 1 && owners.single() == context) {
                    val migrated = File(if (legacy.parentFile == downloadDir) downloadDir else imageDir, name)
                    synchronized(this) { if (!migrated.isFile) { checkImage(legacy); legacy.copyTo(migrated, overwrite = true) } }
                    return migrated
                }
            }
        }
        if (!sourceRemoved(sourceId)) return primary
        // 删除源仍允许读取已下载内容；只对已删除源查找已知解密版本，不能跨活跃规则复用旧拼图。
        for (dir in listOf(downloadDir, imageDir)) for (spec in MangaDecoder.supported) {
            val candidate = MangaDecoder.cacheName(sourceId, mangaHttpUrl(url), spec, cover, context)
            File(dir, candidate).let { if (it.isFile && it.length() > 0) return it }
        }
        return primary
    }

    fun hasCompleteDownload(book: MangaBook, chapter: MangaChapter): Boolean =
        chapter.url in book.cachedChapters && chapter.pages.isNotEmpty() && chapter.pages.indices.all { page ->
            cachedImage(book.sourceId, chapter.pages[page], context = MangaImageContext.of(book, chapter, page)).let {
                it.parentFile == downloadDir && it.isFile && it.length() > 0
            }
        }

    @Synchronized fun protectImage(sourceId: String, url: String, context: MangaImageContext? = null) {
        if (url.startsWith("file:")) return
        val file = cachedImage(sourceId, url, context = context)
        require(file.isFile && file.length() > 0) { "图片尚未下载" }
        val target = File(downloadDir, file.name)
        if (file != target && !target.isFile) {
            val temp = File(downloadDir, file.name + ".part")
            try {
                file.copyTo(temp, overwrite = true)
                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
    }

    /** 旧版本主动下载的图片先迁移到受保护目录，不能被临时缓存清理误删。 */
    @Synchronized fun migrateDownloads() {
        books().filterNot { it.local }.forEach { book ->
            book.chapters.filter { it.url in book.cachedChapters }.forEach { chapter -> chapter.pages.forEachIndexed { page, ref ->
                val context = MangaImageContext.of(book, chapter, page)
                if (!ref.startsWith("file:") && cachedImage(book.sourceId, ref, context = context).isFile) protectImage(book.sourceId, ref, context)
            } }
        }
    }

    @Synchronized fun deleteDownloads(id: String): Long {
        val book = books().firstOrNull { it.id == id && !it.local } ?: return 0
        val others = books().filter { it.id != id }.flatMap { other ->
            other.chapters.flatMap { chapter -> chapter.pages.flatMapIndexed { page, ref -> imageNames(other.sourceId, ref, MangaImageContext.of(other, chapter, page)) } }
        }.toSet()
        var freed = 0L
        book.chapters.forEach { chapter -> chapter.pages.forEachIndexed { page, ref ->
            imageNames(book.sourceId, ref, MangaImageContext.of(book, chapter, page)).filterNot { it in others }.forEach { name ->
                listOf(File(downloadDir, name), File(imageDir, name)).forEach { file ->
                    val size = file.length(); if (file.delete()) freed += size
                }
            }
        } }
        patch(id) { it.copy(cachedChapters = emptySet(), onlineOnly = true) }
        return freed
    }

    /** 记录图片访问时间，供缓存淘汰使用。 */
    fun touchImage(file: File) {
        imageAccess[file.name] = System.currentTimeMillis()
        // 从磁盘读回已有图片时，用最后修改时间作为初始访问时间。
        if (file.lastModified() > 0) file.setLastModified(System.currentTimeMillis())
    }

    // ---------------- 缓存管理 ----------------

    /** 在线图片缓存占用（只统计共享图片目录）。 */
    fun cacheUsage(): MangaCacheUsage {
        migrateDownloads()
        val files = imageDir.listFiles { f -> f.isFile } ?: emptyArray()
        var bytes = 0L
        files.forEach { bytes += it.length() }
        val localBytes = localDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return MangaCacheUsage(onlineBytes = bytes, onlineCount = files.size, localBytes = localBytes,
            downloadedBytes = downloadDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
    }

    /**
     * 按最后访问时间淘汰在线图片缓存。
     * 之前缓存只写不删，用户看几十部漫画后私有目录会无限膨胀且无处清理。
     */
    @Synchronized
    fun clearImageCache(): Long {
        migrateDownloads()
        val files = imageDir.listFiles { f -> f.isFile } ?: return 0
        var freed = 0L
        files.forEach { freed += it.length(); it.delete() }
        imageAccess.clear()
        reconcileDownloads()
        return freed
    }

    /**
     * 把在线图片缓存裁剪到 [maxBytes] 以内，按最后修改时间从旧到新删除。
     * @return 释放的字节数
     */
    @Synchronized
    fun trimImageCache(maxBytes: Long): Long {
        migrateDownloads()
        val files = imageDir.listFiles { f -> f.isFile } ?: return 0
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        var freed = 0L
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) { total -= size; freed += size; imageAccess.remove(file.name) }
        }
        reconcileDownloads()
        return freed
    }

    /** 清理图片后不能继续把缺页章节标成已下载。 */
    private fun reconcileDownloads() {
        val next = books().map { book ->
            if (book.local) book else {
                val complete = book.chapters.filter { chapter ->
                    chapter.url in book.cachedChapters && chapter.pages.isNotEmpty() && chapter.pages.withIndex().all { (page, ref) ->
                        cachedImage(book.sourceId, ref, context = MangaImageContext.of(book, chapter, page)).let { it.isFile && it.length() > 0 }
                    }
                }.map { it.url }.toSet()
                book.copy(cachedChapters = complete, onlineOnly = book.onlineOnly || complete.size != book.chapters.size)
            }
        }
        atomicJson(libraryFile, JSONArray().also { a -> next.forEach { a.put(it.json()) } }.toString())
    }

    suspend fun import(context: Context, uris: List<Uri>): List<MangaBook> {
        require(uris.isNotEmpty() && uris.size <= 5000) { "请选择漫画压缩包或图片，最多 5000 项" }
        val imageUris = ArrayList<Uri>()
        val imported = ArrayList<MangaBook>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            val name = fileName(context, uri)
            if (name.substringAfterLast('.', "").lowercase() in setOf("cbz", "zip")) {
                val id = UUID.randomUUID().toString()
                val folder = File(localDir, id).apply { mkdirs() }
                try {
                    var total = 0L
                    var entryCount = 0
                    val entries = ArrayList<Pair<String, String>>()
                    context.contentResolver.openInputStream(uri)?.use { raw -> ZipInputStream(raw.buffered()).use { zip ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            require(++entryCount <= 10000) { "压缩包条目超过 10000 项上限" }
                            if (!entry.isDirectory && isImage(entry.name)) {
                                require(entries.size < 5000) { "漫画超过 5000 张图片上限" }
                                val file = File(folder, "${entries.size}.${entry.name.substringAfterLast('.').lowercase()}")
                                file.outputStream().use { out ->
                                    val buffer = ByteArray(8192)
                                    var size = 0L
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = zip.read(buffer)
                                        if (read < 0) break
                                        size += read; total += read
                                        require(size <= 24L * 1024 * 1024 && total <= 512L * 1024 * 1024) { "图片或压缩包解压后超过大小上限" }
                                        out.write(buffer, 0, read)
                                    }
                                }
                                checkImage(file)
                                entries.add(entry.name to file.toURI().toString())
                            } else {
                                // ZipInputStream.closeEntry 会继续解压；丢弃的条目也必须计入上限。
                                val buffer = ByteArray(8192)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    require(total <= 512L * 1024 * 1024) { "压缩包解压后超过 512 MB 上限" }
                                }
                            }
                            zip.closeEntry()
                        }
                    } } ?: error("无法读取漫画文件")
                    require(entries.isNotEmpty()) { "压缩包内没有支持的漫画图片" }
                    val chapters = organizeLocalChapters(id, entries)
                    val book = MangaBook(id = id, title = name.substringBeforeLast('.'), cover = chapters.first().pages.first(),
                        chapters = chapters, cachedChapters = chapters.map { it.url }.toSet())
                    upsert(book); imported.add(book)
                } catch (failure: Throwable) { folder.deleteRecursively(); throw failure }
            } else {
                require(isImage(name)) { "暂不支持 $name，请选择 CBZ、ZIP 或图片（不支持 CBR / RAR）" }
                imageUris.add(uri)
            }
        }
        if (imageUris.isNotEmpty()) {
            val id = UUID.randomUUID().toString()
            val folder = File(localDir, id).apply { mkdirs() }
            try {
                var total = 0L
                val pages = imageUris.sortedBy { naturalKey(fileName(context, it)) }.mapIndexed { index, uri ->
                    currentCoroutineContext().ensureActive()
                    val file = File(folder, "$index.${fileName(context, uri).substringAfterLast('.').lowercase()}")
                    context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                        val buffer = ByteArray(8192); var size = 0L
                        while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; size += n; total += n
                            require(size <= 24L * 1024 * 1024 && total <= 512L * 1024 * 1024) { "导入图片超过大小上限" }; out.write(buffer, 0, n) }
                    } } ?: error("无法读取图片")
                    checkImage(file); file.toURI().toString()
                }
                val chapter = MangaChapter("图片集", "local:$id", pages)
                val book = MangaBook(id = id, title = fileName(context, imageUris.first()).substringBeforeLast('.') + " · 图片集", cover = pages.first(), chapters = listOf(chapter), cachedChapters = setOf(chapter.url))
                upsert(book); imported.add(book)
            } catch (failure: Throwable) { folder.deleteRecursively(); throw failure }
        }
        return imported
    }
    companion object {
        internal fun organizeLocalChapters(id: String, entries: List<Pair<String, String>>): List<MangaChapter> =
            entries.sortedBy { naturalKey(it.first) }.groupBy { it.first.replace('\\', '/').substringBeforeLast('/', "").ifBlank { "全卷" } }
                .map { (folder, images) -> MangaChapter(folder, "local:$id:${mangaHash(folder).take(16)}", images.map { it.second }) }

        fun fileName(context: Context, uri: Uri): String = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        fun isImage(name: String) = name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
        fun naturalKey(name: String) = Regex("\\d+").replace(name.lowercase()) { it.value.padStart(16, '0') }
        fun checkImage(file: File) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            require(options.outWidth in 1..100000 && options.outHeight in 1..300000) { "不是有效或受支持的图片：${file.name}" }
        }
        fun checkReadableImage(file: File) {
            checkImage(file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 768) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            require(bitmap != null) { "图片文件存在但无法解码，请重新获取图片" }
            bitmap.recycle()
        }
        private fun URIPath(reference: String) = java.net.URI(reference).let { require(it.scheme == "file"); it.path }
    }
}

/** 漫画缓存占用统计。 */
data class MangaCacheUsage(val onlineBytes: Long, val onlineCount: Int, val localBytes: Long, val downloadedBytes: Long = 0) {
    val totalBytes: Long get() = onlineBytes + localBytes + downloadedBytes
    fun readable(bytes: Long = onlineBytes): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
