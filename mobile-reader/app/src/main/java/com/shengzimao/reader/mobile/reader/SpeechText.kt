package com.shengzimao.reader.mobile.reader

/**
 * 一个可朗读的句子。
 *
 * 与旧实现的区别：旧版按「非空行」切分，中文小说里一个自然段常被软换行拆成多行，
 * 结果朗读在每个软换行处停顿，听起来一顿一顿。现在按**句末标点**切分，
 * 一个自然段里的几句话会连续读出，听感才是连贯的。
 *
 * [segment] 是这句话属于「提交给引擎的第几个原始段落」（用于把引擎回调映射回页内段落）；
 * [sentenceIndex] 是该段内的第几句（用于高亮推进）。
 */
internal data class SpeechChunk(
    val text: String,
    val segment: Int,
    val sentenceIndex: Int,
)

/**
 * 把段落切成适合 TTS 朗读的句子。
 *
 * 规则：
 * 1. 以中文句末标点（。！？；…）和英文句末标点（.!?）为界；
 * 2. 紧跟的右引号 / 右括号归属于上一句（避免「他说。」被拆开）；
 * 3. 过短的句子（不足 [minChars]）与下一句合并，避免「好。」单独占一次朗读；
 * 4. 过长的句子（超过 [maxChars]）在逗号处再切，仍超长则硬切。
 *
 * 这样既保留语义停顿，又不会把长句塞成一次 3000 字的朗读。
 */
internal object SpeechText {
    /** 句末标点：遇到它们就准备断句。 */
    private const val TERMINATORS = "。！？；…!?;"

    /** 允许紧跟句末标点的收尾符号，归到上一句。 */
    private const val TRAILERS = "”’」』）)】》\"'"

    /** 句子内部的次级停顿，用于超长句再切。 */
    private const val SOFT_BREAKS = "，、,：:"

    /**
     * @param segments 页面上的段落文本（已去掉缩进与换行）
     * @param minChars 低于这个字数的句子与下一句合并
     * @param maxChars 单次提交给引擎的上限，超过则在次级停顿处切
     */
    fun chunks(
        segments: List<String>,
        minChars: Int = 8,
        maxChars: Int = 220,
    ): List<SpeechChunk> {
        val out = ArrayList<SpeechChunk>()
        segments.forEachIndexed { segmentIndex, raw ->
            val normalized = raw
                .replace('\u3000', ' ')
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.isEmpty()) return@forEachIndexed

            val sentences = splitSentences(normalized, minChars, maxChars)
            sentences.forEachIndexed { sentenceIndex, sentence ->
                out += SpeechChunk(sentence, segmentIndex, sentenceIndex)
            }
        }
        return out
    }

    /** 单段文本切句，独立出来方便单测。 */
    fun splitSentences(text: String, minChars: Int = 8, maxChars: Int = 220): List<String> {
        if (text.isBlank()) return emptyList()
        val raw = ArrayList<String>()
        var cursor = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch in TERMINATORS) {
                var end = i + 1
                // 连续标点（「？！」「……」）一起归入本句。
                while (end < text.length && text[end] in TERMINATORS) end++
                // 收尾引号 / 括号也归入本句。
                while (end < text.length && text[end] in TRAILERS) end++
                val piece = text.substring(cursor, end).trim()
                if (piece.isNotEmpty()) raw += piece
                cursor = end
                i = end
                continue
            }
            i++
        }
        if (cursor < text.length) {
            val tail = text.substring(cursor).trim()
            if (tail.isNotEmpty()) raw += tail
        }

        // 合并过短的句子，拆分过长的句子。
        val merged = ArrayList<String>()
        var pending = StringBuilder()
        for (piece in raw) {
            if (pending.isEmpty()) pending.append(piece) else pending.append(piece)
            if (pending.length >= minChars) {
                merged += pending.toString()
                pending = StringBuilder()
            }
        }
        if (pending.isNotEmpty()) {
            // 结尾残余过短，就并入上一句，避免单独一句「好。」
            if (merged.isNotEmpty() && pending.length < minChars) {
                merged[merged.lastIndex] = merged.last() + pending
            } else {
                merged += pending.toString()
            }
        }

        val result = ArrayList<String>()
        for (piece in merged) {
            if (piece.length <= maxChars) {
                result += piece
                continue
            }
            // 超长句：优先在次级停顿处切；找不到再硬切。
            var start = 0
            while (start < piece.length) {
                val hardEnd = minOf(start + maxChars, piece.length)
                if (hardEnd >= piece.length) {
                    result += piece.substring(start).trim()
                    break
                }
                var cut = -1
                for (k in hardEnd downTo start + minChars) {
                    if (piece[k - 1] in SOFT_BREAKS) { cut = k; break }
                }
                if (cut <= start) cut = hardEnd
                // 避免在代理对中间切断。
                if (cut < piece.length && piece[cut - 1].isHighSurrogate() && piece[cut].isLowSurrogate()) cut--
                val sub = piece.substring(start, cut).trim()
                if (sub.isNotEmpty()) result += sub
                start = cut
            }
        }
        return result
    }
}
