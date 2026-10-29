package com.shengzimao.reader.mobile.ui

import androidx.compose.runtime.Composable
import com.shengzimao.reader.mobile.data.Book

@Composable
internal fun BookUpdateLabel(book: Book) {
    if (!book.tracking && book.newChapterCount <= 0) return
    ShelfCaption(if (book.newChapterCount > 0) "新增 ${book.newChapterCount} 章" else "追更中", highlighted = true)
}
