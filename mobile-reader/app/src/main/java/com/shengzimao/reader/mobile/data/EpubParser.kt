package com.shengzimao.reader.mobile.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class ParsedBook(
    val title: String,
    val author: String,
    val text: String,
    val cover: ByteArray? = null,
    val intro: String = "",
)

/**
 * EPUB 导入：走 SAX（不支持 DTD 外部实体解析），逐页提取文字。
 * 不渲染 CSS、不执行脚本，图片按导入设置决定保留替代文字还是丢弃。
 */
object EpubParser {
    private const val MAX_ENTRY = 16 * 1024 * 1024
    private const val MAX_TOTAL = 128 * 1024 * 1024
    private const val MAX_COVER = 1_400_000

    enum class ImageMode { ALT, PLACEHOLDER, SKIP }

    fun isEpub(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        return true
    }

    fun parse(bytes: ByteArray, fallbackTitle: String, imageMode: ImageMode = ImageMode.ALT): ParsedBook {
        val entries = readEntries(bytes)
        val container = entries["META-INF/container.xml"]?.let { String(it, Charsets.UTF_8) }
            ?: throw IllegalStateException("EPUB 缺少 container.xml，可能已损坏。")
        val packagePath = Regex("<rootfile[^>]*full-path\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(container)?.groupValues?.get(1)
            ?: throw IllegalStateException("EPUB 缺少 package 路径。")
        if (packagePath.contains("..")) throw IllegalStateException("EPUB 正文路径非法。")
        val opfText = entries[normalizePath(packagePath)]?.let { String(it, Charsets.UTF_8) }
            ?: throw IllegalStateException("EPUB 缺少 package 文档。")
        val opf = OpfHandler()
        parseXml(opfText) { opf }
        val base = packagePath.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        for (itemRef in opf.spine) {
            val item = opf.manifest[itemRef] ?: continue
            val target = joinPath(base, item.href.split('#').first())
            if (target.startsWith("..") || target.contains("/../")) throw IllegalStateException("EPUB 正文路径非法。")
            val data = entries[target] ?: continue
            val text = htmlText(String(data, Charsets.UTF_8), imageMode)
            if (text.isNotBlank()) parts.add(text)
        }
        val text = parts.joinToString("\n\n")
        if (text.isBlank()) throw IllegalStateException("EPUB 没有可读取正文（可能有 DRM 或只有图片）。")
        var cover: ByteArray? = null
        val coverItem = opf.manifest[opf.coverId] ?: opf.manifest.values.firstOrNull { it.properties.contains("cover-image") }
        if (coverItem != null && coverItem.mediaType.matches(Regex("image/(png|jpeg|jpg|webp)"))) {
            val target = joinPath(base, coverItem.href.split('#').first())
            val data = entries[target]
            if (data != null && data.size <= MAX_COVER) cover = data
        }
        return ParsedBook(
            title = opf.title.ifBlank { fallbackTitle },
            author = opf.author,
            text = text,
            cover = cover,
            intro = opf.description,
        )
    }

    fun htmlText(html: String, imageMode: ImageMode = ImageMode.ALT): String {
        val handler = HtmlHandler(imageMode)
        val cleaned = html
            .replace(Regex("<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace("&nbsp;", " ")
        // 包一层根节点，保证片段也能被解析；解析失败时退化为去标签纯文本。
        return runCatching {
            parseXml("<reader-root>$cleaned</reader-root>") { handler }
            handler.build()
        }.getOrElse { fallbackStrip(cleaned) }
    }

    private fun fallbackStrip(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("[\\t ]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun parseXml(text: String, handlerFactory: () -> DefaultHandler) {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
        }
        val parser = factory.newSAXParser()
        val source = InputSource(text.reader())
        source.encoding = "UTF-8"
        parser.parse(source, handlerFactory())
    }

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        var expanded = 0
        ZipInputStream(bytes.inputStream()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                if (count > 50_000) throw IllegalStateException("EPUB 条目过多。")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val name = normalizePath(entry.name)
                if (!isTextEntry(name)) {
                    // 二进制条目（封面等）按扩展名也读进来：manifest 里的 cover-image 需要它。
                    val isImage = Regex(".*\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE).matches(name)
                    if (name.startsWith("META-INF/") || name.endsWith(".opf") || isImage) {
                        val data = readLimited(zip, if (isImage) MAX_COVER else MAX_ENTRY)
                        expanded += data.size
                        if (expanded > MAX_TOTAL) throw IllegalStateException("EPUB 解压后的正文超过安全上限。")
                        entries[name] = data
                    }
                    zip.closeEntry()
                    continue
                }
                val data = readLimited(zip, MAX_ENTRY)
                expanded += data.size
                if (expanded > MAX_TOTAL) throw IllegalStateException("EPUB 解压后的正文超过安全上限。")
                entries[name] = data
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun isTextEntry(name: String): Boolean =
        name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xml") || name.endsWith(".opf")

    private fun readLimited(zip: ZipInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 15)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw IllegalStateException("EPUB 单个条目解压后超过上限。")
        }
        return out.toByteArray()
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/').removePrefix("/")

    private fun joinPath(base: String, href: String): String {
        val safe = href.replace('\\', '/')
        val segments = ArrayList<String>()
        if (base.isNotEmpty()) base.split('/').filter { it.isNotEmpty() }.forEach { segments.add(it) }
        for (part in safe.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1) else segments.add("..")
                else -> segments.add(part)
            }
        }
        return segments.joinToString("/")
    }

    private class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
    )

    private class OpfHandler : DefaultHandler() {
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var title = ""
        var author = ""
        var description = ""
        var coverId = ""
        private val text = StringBuilder()
        private var inMetadata = false
        private var capture: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = (localName ?: qName ?: "").substringAfter(':').lowercase()
            val attrs = attributes ?: return
            when (name) {
                "metadata" -> inMetadata = true
                "item" -> {
                    val id = attrs.getValue("id") ?: ""
                    if (id.isNotEmpty()) manifest[id] = ManifestItem(id, attrs.getValue("href") ?: "", attrs.getValue("media-type") ?: "", attrs.getValue("properties") ?: "")
                }
                "itemref" -> attrs.getValue("idref")?.let {
                    val linear = attrs.getValue("linear")
                    if (linear == null || linear == "yes") spine.add(it)
                }
                "meta" -> {
                    if (attrs.getValue("name") == "cover") coverId = attrs.getValue("content") ?: ""
                }
                "title", "creator", "description" -> if (inMetadata) {
                    capture = name
                    text.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capture != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (localName ?: qName ?: "").substringAfter(':').lowercase()
            when (name) {
                "metadata" -> inMetadata = false
                "title" -> if (capture == "title") { title = text.toString().trim(); capture = null }
                "creator" -> if (capture == "creator") { if (author.isBlank()) author = text.toString().trim(); capture = null }
                "description" -> if (capture == "description") { description = text.toString().trim().take(4000); capture = null }
            }
            if (name == "title" || name == "creator" || name == "description") capture = null
        }
    }

    private class HtmlHandler(private val imageMode: ImageMode) : DefaultHandler() {
        private val result = StringBuilder()
        private val hidden = ArrayList<Boolean>()
        private val breaks = setOf("p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "section", "tr", "blockquote")

        private fun hiddenNow(): Boolean = hidden.lastOrNull() == true

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val name = (localName ?: qName ?: "").substringAfter(':').lowercase()
            val parentHidden = hiddenNow()
            val selfHidden = parentHidden || name in setOf("script", "style", "head")
            hidden.add(selfHidden)
            if (selfHidden) return
            if (name in breaks) result.append('\n')
            if (name == "img" && imageMode != ImageMode.SKIP) {
                val alt = attributes?.getValue("alt")?.trim().orEmpty()
                if (imageMode == ImageMode.PLACEHOLDER) result.append("[图片]")
                else if (alt.isNotEmpty()) result.append("[图片：").append(alt).append(']')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!hiddenNow()) result.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (localName ?: qName ?: "").substringAfter(':').lowercase()
            val wasHidden = hidden.removeLastOrNull() ?: false
            if (!wasHidden && name in breaks) result.append('\n')
        }

        fun build(): String = result.toString()
            .replace(Regex("[\\t ]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }
}

private fun MutableList<Boolean>.removeLastOrNull(): Boolean? = if (isEmpty()) null else removeAt(size - 1)
