package com.shengzimao.reader.mobile.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文档导出：把当前书本文本另存为 TXT 或 EPUB，写入调用方提供的输出流（SAF）。
 * 与桌面版一致：EPUB 每章一个 XHTML，附带 nav.xhtml 目录。
 */
object DocumentExporter {

    fun exportTxt(text: String, out: java.io.OutputStream) {
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun exportEpub(title: String, author: String, text: String, chapters: List<Chapter>, out: java.io.OutputStream) {
        val safeTitle = escape(title.ifBlank { "未命名书籍" })
        val safeAuthor = escape(author)
        val sections = buildSections(text, chapters)
        val bookId = "urn:uuid:" + java.util.UUID.randomUUID()
        ZipOutputStream(out.buffered()).use { zip ->
            // mimetype 必须是第一个条目且不压缩。
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
            zip.setLevel(9)
            put(zip, "META-INF/container.xml", CONTAINER)
            val manifest = StringBuilder()
            val spine = StringBuilder()
            val navItems = StringBuilder()
            sections.forEachIndexed { index, section ->
                val name = "OPS/chapter${index + 1}.xhtml"
                manifest.append("<item id=\"c${index + 1}\" href=\"chapter${index + 1}.xhtml\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"c${index + 1}\"/>")
                navItems.append("<li><a href=\"chapter${index + 1}.xhtml\">${escape(section.title)}</a></li>")
                put(zip, name, xhtml(section.title, section.body, safeTitle))
            }
            manifest.append("<item id=\"nav\" href=\"nav.xhtml\" properties=\"nav\" media-type=\"application/xhtml+xml\"/>")
            put(
                zip,
                "OPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${safeTitle}</title></head>
<body><nav epub:type="toc"><h1>目录</h1><ol>$navItems</ol></nav></body></html>""",
            )
            put(
                zip,
                "OPS/book.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="book-id">$bookId</dc:identifier>
<dc:title>$safeTitle</dc:title>
<dc:creator>$safeAuthor</dc:creator>
<dc:language>zh-CN</dc:language>
<meta property="dcterms:modified">${java.time.Instant.now().toString().substring(0, 19)}Z</meta>
</metadata>
<manifest>$manifest</manifest>
<spine>$spine</spine>
</package>""",
            )
        }
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun xhtml(title: String, body: String, bookTitle: String): String {
        val paragraphs = body.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) "<p>&#160;</p>" else "<p>${escape(line)}</p>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>${escape(title)}</title><meta charset="utf-8"/></head>
<body><h1>${escape(title)}</h1>
$paragraphs
</body></html>"""
    }

    /** 按章节切分正文；没有章节时按约 20 万字一段切块，避免单个 XHTML 过大。 */
    fun buildSections(text: String, chapters: List<Chapter>): List<Section> {
        if (chapters.isEmpty()) {
            val chunks = ArrayList<Section>()
            var start = 0
            var index = 1
            while (start < text.length) {
                var end = minOf(text.length, start + 200_000)
                if (end < text.length) {
                    val newline = text.indexOf('\n', end)
                    end = if (newline >= 0) newline + 1 else text.length
                }
                chunks.add(Section("正文 ${index++}", text.substring(start, end)))
                start = end
            }
            return chunks.ifEmpty { listOf(Section("正文", text)) }
        }
        val sections = ArrayList<Section>(chapters.size)
        for ((index, chapter) in chapters.withIndex()) {
            val start = chapter.position.coerceIn(0, text.length)
            val end = if (index + 1 < chapters.size) chapters[index + 1].position.coerceIn(start, text.length) else text.length
            var body = text.substring(start, end)
            val firstLine = body.substringBefore('\n').trim()
            if (firstLine == chapter.title.trim() && body.contains('\n')) body = body.substringAfter('\n')
            sections.add(Section(chapter.title.ifBlank { "第 ${index + 1} 章" }, body))
        }
        return sections
    }

    data class Section(val title: String, val body: String)

    fun bytesOfEpub(title: String, author: String, text: String, chapters: List<Chapter>): ByteArray =
        ByteArrayOutputStream().also { exportEpub(title, author, text, chapters, it) }.toByteArray()

    private val ESCAPES = mapOf('&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '"' to "&quot;", '\'' to "&apos;")

    fun escape(value: String): String = buildString(value.length) {
        for (char in value) append(ESCAPES[char] ?: char)
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
}
