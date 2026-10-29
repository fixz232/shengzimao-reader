package com.shengzimao.reader.mobile.data

/** Rules are literal, bounded, and never execute user code or regular expressions. */
object ContentCleanup {
    data class Rule(val find: String, val replacement: String?, val removeLine: Boolean)
    data class Span(val displayStart: Int, val displayEnd: Int, val rawStart: Int, val rawEnd: Int)
    data class Result(val text: String, val spans: List<Span>) {
        fun rawOffset(offset: Int): Int {
            val s = spans.firstOrNull { offset <= it.displayEnd } ?: return spans.lastOrNull()?.rawEnd ?: offset
            return s.rawStart + ((offset - s.displayStart).coerceAtLeast(0).toLong() * (s.rawEnd - s.rawStart) /
                (s.displayEnd - s.displayStart).coerceAtLeast(1)).toInt()
        }
        fun displayOffset(offset: Int): Int {
            val s = spans.firstOrNull { offset <= it.rawEnd } ?: return text.length
            return (s.displayStart + ((offset - s.rawStart).coerceAtLeast(0).toLong() * (s.displayEnd - s.displayStart) /
                (s.rawEnd - s.rawStart).coerceAtLeast(1)).toInt()).coerceIn(0, text.length)
        }
    }
    fun parse(raw: String): List<Rule> {
        require(raw.length <= 12_000) { "净化规则最多 12000 字" }
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        require(lines.size <= 50) { "最多添加 50 条净化规则" }
        return lines.map { line ->
            require(line.length <= 300) { "单条规则最多 300 字" }
            if (line.startsWith('-')) {
                val find = line.drop(1).trim(); require(find.isNotBlank()) { "删除段落规则需要填写匹配文字" }
                Rule(find, null, true)
            } else {
                val pair = line.split("=>", limit = 2)
                require(pair.size == 2 && pair[0].isNotBlank()) { "替换格式：原文字=>新文字；删除段落：-包含文字" }
                Rule(pair[0], pair[1], false)
            }
        }
    }
    fun apply(raw: String, rules: List<Rule>): Result {
        if (rules.isEmpty()) return Result(raw, listOf(Span(0, raw.length, 0, raw.length)))
        val output = StringBuilder(); val spans = ArrayList<Span>()
        Regex("[^\r\n]+").findAll(raw).forEach { line ->
            val original = line.value
            if (rules.any { it.removeLine && original.contains(it.find) }) return@forEach
            var text = original
            rules.filterNot { it.removeLine }.forEach { rule ->
                val replacement = rule.replacement.orEmpty()
                if (replacement.length > rule.find.length) {
                    var count = 0L; var from = 0
                    while (true) { val at = text.indexOf(rule.find, from); if (at < 0) break; count++; from = at + rule.find.length }
                    require(text.length + count * (replacement.length - rule.find.length) <= 2_000_000L) { "净化后的章节过大，请缩短替换内容" }
                }
                text = text.replace(rule.find, replacement)
            }
            if (text.isBlank()) return@forEach
            require(output.length + text.length <= 2_000_000) { "净化后的章节过大，请缩短替换内容" }
            if (output.isNotEmpty()) output.append('\n')
            val start = output.length; output.append(text)
            spans += Span(start, output.length, line.range.first, line.range.last + 1)
        }
        return Result(output.toString(), spans)
    }
}

object TextPositionAnchor {
    data class Match(val offset: Int, val exact: Boolean)
    fun match(previous: String, next: String, offset: Int): Match {
        val position = offset.coerceIn(0, previous.length)
        if (previous.isEmpty() || next.isEmpty()) return Match(0, false)
        val left = (position - 20).coerceAtLeast(0)
        val needle = previous.substring(left, (position + 48).coerceAtMost(previous.length))
        if (needle.length >= 8) {
            val found = next.indexOf(needle)
            if (found >= 0 && next.indexOf(needle, found + 1) < 0) return Match((found + position - left).coerceAtMost(next.length), true)
        }
        // Punctuation/whitespace often differs between providers; retain a source-index map.
        val target = StringBuilder(); val indices = IntArray(next.length); var count = 0
        next.forEachIndexed { i, c -> if (c.isLetterOrDigit()) { target.append(c.lowercaseChar()); indices[count++] = i } }
        val snippet = previous.substring(position, (position + 100).coerceAtMost(previous.length))
            .filter { it.isLetterOrDigit() }.lowercase().take(32)
        if (snippet.length >= 12) {
            val found = target.indexOf(snippet)
            if (found >= 0 && target.indexOf(snippet, found + 1) < 0) return Match(indices[found], true)
        }
        return Match((position.toLong() * next.length / previous.length).toInt().coerceIn(0, next.length), false)
    }
}

/** All inputs are measured locally. Background work yields to low-memory and metered states. */
object AdaptivePrefetch {
    fun count(unmetered: Boolean, lowMemory: Boolean, freeMb: Long, readIntervalMs: Long,
              loadMs: Long, max: Int, wifiOnly: Boolean = true): Int {
        if (max <= 0 || lowMemory || freeMb < 24 || (wifiOnly && !unmetered)) return 0
        if (!unmetered || freeMb < 48) return 1.coerceAtMost(max)
        val lead = ((loadMs.coerceAtLeast(500) * 2 / readIntervalMs.coerceAtLeast(800)) + 1).toInt()
        return lead.coerceIn(1, if (freeMb < 96) minOf(2, max) else max)
    }
}

class OnlineSessionCache(private val maxChars: Int = minOf(4_000_000L, Runtime.getRuntime().maxMemory() / 32).toInt()) {
    private val entries = LinkedHashMap<Pair<String, String>, String>(16, .75f, true)
    private var chars = 0
    @Synchronized fun get(book: String, chapter: String): String? = entries[book to chapter]
    @Synchronized fun put(book: String, chapter: String, text: String) {
        if (text.length > maxChars) return
        chars -= entries.remove(book to chapter)?.length ?: 0
        entries[book to chapter] = text; chars += text.length
        while (chars > maxChars && entries.isNotEmpty()) { val first = entries.entries.first(); chars -= first.value.length; entries.remove(first.key) }
    }
    @Synchronized fun snapshot(book: String): Map<String, String> = entries.filterKeys { it.first == book }.mapKeys { it.key.second }
}

object ComicPageAnalysis {
    /** Conservative symmetric trim: every sampled row must be near white; keep a safety margin. */
    fun cropPercent(width: Int, height: Int, pixels: IntArray): Int {
        if (width < 32 || height < 16 || pixels.size < width * height) return 0
        fun whiteColumn(x: Int): Boolean = (0 until height).all { y ->
            val p = pixels[y * width + x]
            (p ushr 24) < 16 || ((p shr 16 and 255) >= 246 && (p shr 8 and 255) >= 246 && (p and 255) >= 246)
        }
        var left = 0; var right = 0
        while (left < width * 15 / 100 && whiteColumn(left)) left++
        while (right < width * 15 / 100 && whiteColumn(width - right - 1)) right++
        return (minOf(left, right) * 100 / width - 1).coerceIn(0, 14)
    }
    fun spreads(count: Int, doublePage: Boolean, smart: Boolean, widePages: Set<Int>): List<List<Int>> {
        val result = ArrayList<List<Int>>(); var page = 0
        while (page < count) {
            val single = !doublePage || (smart && (page == 0 || page in widePages || page + 1 in widePages))
            val end = minOf(count, page + if (single) 1 else 2)
            result += (page until end).toList(); page = end
        }
        return result
    }
}
