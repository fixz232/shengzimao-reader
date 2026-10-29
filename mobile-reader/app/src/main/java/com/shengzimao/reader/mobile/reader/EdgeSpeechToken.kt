package com.shengzimao.reader.mobile.reader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Read Aloud v2 request token. Algorithm adapted from rany2/edge-tts (LGPL-3.0),
 * commit bd44c1d1aaebdc1db7f47456ee12431b42ec7cac, src/edge_tts/drm.py.
 * Original license: assets/notices/EDGE-TTS-LICENSE.txt. This is not a user API key.
 */
internal object EdgeSpeechToken {
    const val CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val VERSION = "2-143.0.3650.75"
    private const val MASK = 0xffffffffL
    private val initial = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL, 0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
    )
    private val initialBlock = longArrayOf(
        0xfd9fbea2L, 0x91243ce7L, 0xbe499094L, 0xcd2e1e0aL, 0x6c006f49L, 0x04bd35abL, 0xfeb0a7e4L, 0x03effbd2L,
    )
    private val schedule = longArrayOf(
        0xeb24bf9eL, 0x1558691fL, 0xa0c09ee2L, 0x0cfa32baL, 0xa622ee8cL, 0xd1dd9491L, 0xc0dd25a0L, 0xf9e70ab6L,
        0xf0c31573L, 0x7058ce4eL, 0x7188e606L, 0x00e5ab33L, 0xd258b060L, 0x72766564L,
    )
    private val roundConstants = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L, 0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L, 0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL, 0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L, 0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L, 0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L, 0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L, 0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L, 0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    )
    private val coefficients = arrayOf(
        longArrayOf(0xe82e91b1L, 0xb32700d0L),
        longArrayOf(0x44de24c6L, 0x88dff51dL),
        longArrayOf(0x3beb65bcL, 0x29514038L),
        longArrayOf(0xc89c5933L, 0xb328cbdfL),
        longArrayOf(0x8d1ec211L, 0xd405b4d5L),
        longArrayOf(0x314ef5d5L, 0x26e55f3cL),
        longArrayOf(0xe92491b1L, 0x607801c6L),
        longArrayOf(0xf0a5b9d5L, 0x82b2c1fbL),
        longArrayOf(0x06064cf3L, 0xfa51a3daL),
        longArrayOf(0xda79fa60L, 0x207cc464L),
        longArrayOf(0xbed20560L, 0x3c3c74f3L),
        longArrayOf(0x89f9c9f8L, 0xd2d14b61L),
        longArrayOf(0x35fa38c6L, 0xbb04a2c1L),
        longArrayOf(0xcb49e3c6L, 0xc5626167L),
    )

    private fun rotate16(x: Long) = ((x shl 16) or (x ushr 16)) and MASK
    private fun rotate(x: Long, n: Int) = ((x ushr n) or (x shl (32 - n))) and MASK
    private fun sigma0(x: Long) = rotate(x, 7) xor rotate(x, 18) xor (x ushr 3)
    private fun sigma1(x: Long) = rotate(x, 17) xor rotate(x, 19) xor (x ushr 10)

    private fun expand(words: LongArray, from: Int = 16) {
        for (i in from until 64) words[i] = (sigma1(words[i - 2]) + words[i - 7] +
            sigma0(words[i - 15]) + words[i - 16]) and MASK
    }

    private fun rounds(state: LongArray, words: LongArray, from: Int = 0): LongArray {
        var (a, b, c, d, e) = state
        var f = state[5]; var g = state[6]; var h = state[7]
        for (i in from until 64) {
            val constant = if (from == 14 && i == 14) 0xc9dc06a7L else roundConstants[i]
            val one = h + (rotate(e, 6) xor rotate(e, 11) xor rotate(e, 25)) +
                (g xor (e and (f xor g))) + constant + words[i]
            val two = (rotate(a, 2) xor rotate(a, 13) xor rotate(a, 22)) + ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = (d + one) and MASK
            d = c; c = b; b = a; a = (one + two) and MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun initialState(data: ByteArray): LongArray {
        val words = LongArray(64)
        words[14] = data.take(3).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 255) }
        words[15] = ByteBuffer.wrap(data, 3, 4).int.toLong() and MASK
        for (i in 16 until 30) {
            var value = schedule[i - 16]
            if (i - 2 >= 14) value += sigma1(words[i - 2]) xor (if (i == 16) 0x000b92c0L else 0L)
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) value += sigma0(words[i - 15]) xor (if (i == 29) 0x059c0b80L else 0L)
            words[i] = value and MASK
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            0xc9dc06a7L - roundConstants[14]) and MASK
        expand(words, 31)
        val working = rounds(initialBlock, words, 14)
        return LongArray(8) { (initial[it] + working[it]) and MASK }
    }

    private fun digest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val padding = Math.floorMod(55 - length, 64)
        val padded = ByteBuffer.allocate(data.size - 7 + 1 + padding + 8)
            .put(data, 7, data.size - 7).put(0x80.toByte()).put(ByteArray(padding)).putLong(length.toLong() * 8).array()
        var state = initialState(data)
        for (offset in padded.indices step 64) {
            val buffer = ByteBuffer.wrap(padded, offset, 64)
            val words = LongArray(64) { if (it < 16) buffer.int.toLong() and MASK else 0L }
            expand(words)
            val working = rounds(state, words)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
        }
        return ByteBuffer.allocate(32).also { b -> state.forEach { b.putInt(it.toInt()) } }.array()
    }

    private fun cs64(data: ByteArray, digest: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val a = (keys.int.toLong() and MASK) or 1L
        val b = (keys.int.toLong() and MASK) or 1L
        val factors = longArrayOf((a * 0x82893933L) and MASK, (b * 0x82893933L) and MASK)
        val terms = coefficients.mapIndexed { i, item -> (item[0] * factors[i % 2]) and MASK }.toMutableList()
        val offsets = coefficients.map { it[1] }.toMutableList()
        val padded = ByteBuffer.allocate(((data.size + 1 + 7) / 8) * 8).order(ByteOrder.LITTLE_ENDIAN)
            .put(0).put(data).apply { position(0) }
        var index = 0
        while (padded.remaining() >= 4) {
            val word = padded.int.toLong() and MASK
            var term = word * (if (index % 2 == 0) a else b)
            var offset = rotate16(word) * (if (index % 2 == 0) 0xef0569fbL else 0xc31713dbL)
            if (index == 0) { term += 0xf2f90b1aL * factors[0]; offset += 0x0b1a0000L }
            terms += term and MASK; offsets += offset and MASK; index++
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        for (i in terms.indices step 2) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rotate16((offsets[i] + x) and MASK)
            var q = (t * 0x76f50f34L) and MASK
            swapAcc += (rotate16(q) * 0xc3efea97L + q) and MASK
            t = rotate16((offsets[i + 1] + y) and MASK); q = (t * 0x1881ce3cL) and MASK
            swapLast = (rotate16(q) * 0x35bd1ec9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK
            state = (state * a + x) and MASK
            state = (rotate16(state) * 0xcf98b111L) and MASK
            state = (rotate16(state) * 0x87085b9fL) and MASK
            state = (rotate16(state) * 0x12ceb96dL) and MASK
            state = (rotate16(state) * 0x257e1d83L) and MASK
            acc += state
            t = (state * b + y) and MASK
            t = (rotate16(t) * 0xa27416f5L) and MASK
            t = (rotate16(t) * 0xd38396ffL) and MASK
            state = (rotate16((rotate16(t) * 0x7c932b89L) and MASK) * 0xbfa49f69L) and MASK
            acc = (acc + state) and MASK
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((swapLast xor state).toInt()).putInt((swapAcc xor acc).toInt()).array()
    }

    fun generate(unixSeconds: Long): String {
        val windowsSeconds = unixSeconds + 11644473600L
        val ticks = Math.floorDiv(windowsSeconds, 300L) * 300L * 10000000L
        val data = (ticks.toString() + CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
        val hash = digest(data)
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) }
        return hex(cs64(data, hash)) + "-" + hex(hash)
    }
}
