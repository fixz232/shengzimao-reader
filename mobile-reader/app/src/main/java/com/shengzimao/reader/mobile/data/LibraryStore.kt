package com.shengzimao.reader.mobile.data

import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 书库持久化：library.json（原子写），正文以 books/<id>.txt 存放。
 * 字段命名与桌面版保持一致的语义，方便以后做 `.szmbak` 备份互通。
 */
class LibraryStore(private val root: File) {
    private val file = File(root, "library.json")
    private val backup = File(root, "library.json.bak")
    val booksDir: File = File(root, "books")
    val coversDir: File = File(root, "covers")
    private var lastBackupAt = 0L

    init {
        booksDir.mkdirs()
        coversDir.mkdirs()
    }

    @Synchronized
    fun load(): Library {
        val library = when {
            file.exists() -> parse(file)
            backup.exists() -> parse(backup)
            else -> Library()
        }
        return library
    }

    private fun parse(source: File): Library = try {
        LibraryJson.decode(source.readText(Charsets.UTF_8))
    } catch (_: Throwable) {
        if (source == file && backup.exists()) parse(backup) else Library()
    }

    @Synchronized
    fun save(library: Library) {
        val temp = File(root, "library.json.tmp")
        val json = LibraryJson.encode(library)
        temp.writeText(json, Charsets.UTF_8)
        // 备份按 5 分钟节流：阅读计时每 20 秒写一次，不必每次都复制整份索引。
        val now = System.currentTimeMillis()
        if (file.exists() && now - lastBackupAt > BACKUP_INTERVAL_MS) {
            runCatching { file.copyTo(backup, overwrite = true) }
                .onSuccess { lastBackupAt = now }
        }
        // 原子替换：先写临时文件再 rename，避免删除与重命名之间进程被杀导致索引丢失。
        runCatching {
            java.nio.file.Files.move(
                temp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            runCatching {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure {
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
        }
    }

    fun textFile(book: Book): File = File(booksDir, book.file)

    fun exists(book: Book): Boolean = book.file.isNotEmpty() && textFile(book).isFile

    fun readText(book: Book): String = textFile(book).readText(Charsets.UTF_8)

    fun removeText(book: Book) {
        runCatching { textFile(book).delete() }
        coverFile(book)?.let { runCatching { it.delete() } }
    }

    fun coverFile(book: Book): File? =
        if (book.file.isNotEmpty()) File(coversDir, book.file.removeSuffix(".txt") + ".img") else null

    fun saveCover(bookId: String, bytes: ByteArray) {
        runCatching { File(coversDir, "$bookId.img").writeBytes(bytes) }
    }

    /** 写入正文并返回文件名；先写临时文件再改名，失败不留半成品。 */
    fun writeText(bookId: String, text: String): String {
        // 文件名由 id 直接拼接，必须挡住 `../` 之类的越界写法。
        require(SAFE_ID.matches(bookId)) { "非法的书籍标识：$bookId" }
        val name = "$bookId.txt"
        val target = File(booksDir, name)
        val temp = File(booksDir, "$name.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temp.delete()
        }
        return name
    }

    fun clearAll() {
        booksDir.listFiles()?.forEach { runCatching { it.delete() } }
        coversDir.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { file.delete() }
        runCatching { backup.delete() }
    }

    companion object {
        private const val BACKUP_INTERVAL_MS = 5 * 60 * 1000L
        private val SAFE_ID = Regex("^[a-zA-Z0-9-]{8,64}$")

        fun newId(): String = UUID.randomUUID().toString()

        fun digest(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
