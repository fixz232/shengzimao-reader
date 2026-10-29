package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.junit.Assert.*
import org.junit.Test

class NovelChapterNavigationTest {
    private val book = Book(title = "测试", chapters = (0..4).map { Chapter("章节$it", it * 100) }, length = 500, newChapterCount = 2)
    @Test fun localChapterUsesCharacterPosition() {
        val moved = NovelChapterNavigation.moveTo(book, 3, now = 88)
        assertEquals(300, moved.position)
        assertEquals(88L, moved.lastReadAt)
        assertEquals(1, moved.newChapterCount)
    }
    @Test fun onlineChapterUsesChapterIndexAndResetsParagraphOffsets() {
        val moved = NovelChapterNavigation.moveTo(book.copy(onlineOnly = true, onlineOffset = 9, onlineCharacterOffset = 25, onlineParagraphFraction = .5f), 3, now = 99)
        assertEquals(3, moved.position)
        assertEquals(0, moved.onlineOffset)
        assertEquals(-1, moved.onlineCharacterOffset)
        assertEquals(0f, moved.onlineParagraphFraction)
    }
    @Test fun changedDirectoryMatchesExplicitTitleBeforeIndex() {
        assertEquals(400, NovelChapterNavigation.moveTo(book, 1, "章节4", now = 1).position)
    }
    @Test fun missingChapterDoesNotOpenDifferentContent() {
        assertTrue(runCatching { NovelChapterNavigation.moveTo(book, 0, "缺失章节", 1) }.isFailure)
        assertTrue(runCatching { NovelChapterNavigation.moveTo(book, 10, now = 1) }.isFailure)
    }
    @Test fun unreadEntryUsesUpdatedDirectoryAndHandlesEmptyOrOldCounts() {
        assertEquals(3, NovelChapterNavigation.firstUnread(book))
        assertEquals(0, NovelChapterNavigation.firstUnread(book.copy(newChapterCount = 90)))
        assertNull(NovelChapterNavigation.firstUnread(book.copy(chapters = emptyList())))
        assertNull(NovelChapterNavigation.firstUnread(book.copy(newChapterCount = 0)))
    }
}
