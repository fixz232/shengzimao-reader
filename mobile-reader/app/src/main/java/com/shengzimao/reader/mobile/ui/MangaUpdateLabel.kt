package com.shengzimao.reader.mobile.ui

import androidx.compose.runtime.Composable
import com.shengzimao.reader.mobile.data.MangaBook

@Composable
internal fun MangaUpdateLabel(book: MangaBook) {
    if (!book.tracking && book.newChapters <= 0) return
    ShelfCaption(if (book.newChapters > 0) "新增 ${book.newChapters} 章" else "追更中", highlighted = true)
}
