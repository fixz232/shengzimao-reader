package com.shengzimao.reader.mobile.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/** 打开文档的抽象：本地路径与 SAF Uri 走同一套读取接口。 */
interface SourceDocument {
    val displayName: String
    val extension: String
    fun openStream(): InputStream
    val size: Long
}

class FileDocument(private val file: File) : SourceDocument {
    override val displayName: String get() = file.name
    override val extension: String get() = file.extension.lowercase()
    override val size: Long get() = file.length()
    override fun openStream(): InputStream = file.inputStream()
    override fun toString(): String = file.absolutePath
}

class UriDocument(private val context: Context, val uri: Uri) : SourceDocument {
    private val meta = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            Pair(name, size)
        }
    }.getOrNull()

    override val displayName: String
        get() = meta?.first?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文档"

    override val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase().ifEmpty {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
            when {
                type.contains("epub") -> "epub"
                type.contains("pdf") -> "pdf"
                type.startsWith("text/") -> "txt"
                else -> ""
            }
        }

    override val size: Long get() = meta?.second ?: -1L

    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("无法读取文件：$displayName")

    fun openAsset(): AssetFileDescriptor? = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()

    override fun toString(): String = uri.toString()

    companion object {
        fun from(context: Context, uri: Uri): SourceDocument =
            if (uri.scheme == "file") FileDocument(File(uri.path ?: "")) else UriDocument(context, uri)
    }
}

data class ImportResult(
    val book: Book,
    val text: String,
    val duplicate: Boolean = false,
)

/** 文档解析：目前支持 TXT / EPUB，其它格式给出明确提示。 */
object DocumentImporter {
    val SUPPORTED_EXTENSIONS = setOf("txt", "text", "epub")
    const val MIME_TYPES = "text/plain,application/epub+zip,application/octet-stream"

    fun readBytes(document: SourceDocument, limit: Long = 512L * 1024 * 1024): ByteArray {
        if (document.size > limit) throw IllegalStateException("文件超过 ${limit / 1024 / 1024} MB 上限。")
        val stream = document.openStream()
        return stream.use { input ->
            val hint = if (document.size in 1..limit) document.size.coerceAtMost(1L shl 22).toInt() else 1 shl 16
            val out = java.io.ByteArrayOutputStream(hint)
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > limit) throw IllegalStateException("文件超过 ${limit / 1024 / 1024} MB 上限。")
            }
            out.toByteArray()
        }
    }

    fun parse(document: SourceDocument, encodedBytes: ByteArray? = null): ParsedBook {
        val extension = document.extension
        val fallbackTitle = ChapterParser.titleFromFileName(document.displayName)
        val fallbackAuthor = ChapterParser.authorFromFileName(document.displayName)
        when (extension) {
            "epub" -> {
                val bytes = encodedBytes ?: readBytes(document)
                val parsed = EpubParser.parse(bytes, fallbackTitle)
                return parsed.copy(title = parsed.title.ifBlank { fallbackTitle }, author = parsed.author.ifBlank { fallbackAuthor })
            }
            "txt", "text", "" -> {
                val bytes = encodedBytes ?: readBytes(document, TextCodec.MAX_TEXT_BYTES.toLong())
                if (EpubParser.isEpub(bytes) && bytes.size > 4 && looksLikeEpub(bytes)) {
                    val parsed = EpubParser.parse(bytes, fallbackTitle)
                    return parsed.copy(title = parsed.title.ifBlank { fallbackTitle })
                }
                val decoded = TextCodec.decode(bytes)
                val text = TextCodec.normalize(decoded.text)
                if (text.isBlank()) throw IllegalStateException("文档没有可读取文字。")
                return ParsedBook(title = fallbackTitle, author = fallbackAuthor, text = text)
            }
            else -> throw IllegalStateException("暂不支持 .$extension 格式，请使用 TXT 或 EPUB。")
        }
    }

    /** 真正的 EPUB 一定有 mimetype 与 container.xml；仅扩展名是 .txt 的 zip 不按 EPUB 处理。 */
    private fun looksLikeEpub(bytes: ByteArray): Boolean {
        if (bytes.size < 30) return false
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        return head.startsWith("PK") && (head.contains("mimetype") || head.contains("META-INF"))
    }
}
