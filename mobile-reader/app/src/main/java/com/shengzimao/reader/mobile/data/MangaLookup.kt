package com.shengzimao.reader.mobile.data

/** Search the work name without a site's edition/alias suffix; never choose a replacement automatically. */
internal fun mangaLookupQuery(title: String): String {
    val trimmed = title.trim()
    val base = trimmed.substringBefore('（').substringBefore('(').trim()
    return base.ifBlank { trimmed }.take(120)
}
