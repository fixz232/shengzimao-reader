package com.shengzimao.reader.mobile.data

/** Read a bounded chapter without allocating a whole downloaded novel. Offsets are UTF-16 characters. */
object DownloadedChapterText {
    fun read(store: LibraryStore, book: Book, chapter: OnlineChapter): String? {
        val toc = book.onlineChapters
        val index = toc.indexOfFirst { it.url == chapter.url }
        val local = book.chapters.getOrNull(index) ?: return null
        // A partial download can have a full manifest, so verify the title before using its index.
        if (local.title.trim() != chapter.title.trim()) return null
        val end = book.chapters.getOrNull(index + 1)?.position ?: book.length
        val length = end - local.position
        if (local.position < 0 || length !in 1..2_000_000) return null
        return store.textFile(book).bufferedReader(Charsets.UTF_8).use { input ->
            var remaining = local.position.toLong()
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped == 0L) { if (input.read() < 0) return null; remaining-- } else remaining -= skipped
            }
            val buffer = CharArray(length); var read = 0
            while (read < length) { val count = input.read(buffer, read, length - read); if (count < 0) break; read += count }
            if (read == length) String(buffer) else null
        }
    }
}
