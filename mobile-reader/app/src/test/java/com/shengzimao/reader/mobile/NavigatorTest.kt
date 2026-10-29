package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.ui.*
import org.junit.Assert.*
import org.junit.Test

class NavigatorTest {
    @Test fun tomatoDestinationSurvivesReadingAndSwitchingMainTabs() {
        val nav = Navigator()
        nav.openTomato()
        nav.openReader("tomato-book")
        nav.onBack()
        assertEquals(Screen.Online, nav.screen)
        assertTrue(nav.discoverTomato)
        assertFalse(nav.discoverManga)
        nav.selectTab(MainTab.MINE)
        nav.selectTab(MainTab.DISCOVER)
        assertTrue(nav.discoverTomato)
        assertEquals(Screen.Online, nav.screen)
    }

    @Test fun savedNavigationRestoresTomatoDestination() {
        val nav = Navigator()
        nav.openTomato()
        nav.openReader("tomato-book")
        val restored = Navigator.restore(nav.snapshot())
        assertEquals(nav.snapshot(), restored.snapshot())
        assertTrue(restored.discoverTomato)
        restored.onBack()
        assertEquals(MainTab.DISCOVER, restored.selectedTab)
        assertEquals(Screen.Online, restored.screen)
        assertTrue(restored.discoverTomato)
    }

    @Test fun aggregateAndComicDestinationsClearTomatoMode() {
        val nav = Navigator()
        nav.openTomato(); nav.openDiscover()
        assertFalse(nav.discoverTomato)
        nav.openTomato(); nav.openMangaOnline()
        assertFalse(nav.discoverTomato)
        assertTrue(nav.discoverManga)
        nav.openTomato()
        assertFalse(nav.discoverManga)
        nav.openOnline()
        assertFalse(nav.discoverTomato)
        assertFalse(nav.discoverManga)
    }

    @Test fun olderSavedStateWithoutTomatoModeStillRestoresAggregateSearch() {
        val nav = Navigator()
        nav.openOnline()
        val restored = Navigator.restore(nav.snapshot().take(17))
        assertEquals(Screen.Online, restored.screen)
        assertEquals(MainTab.DISCOVER, restored.selectedTab)
        assertFalse(restored.discoverTomato)
        assertFalse(restored.discoverManga)
    }

    @Test fun readingReturnsToItsSearchAndSection() {
        val nav = Navigator()
        nav.openMangaOnline()
        nav.openComic("manga:123", 17)
        assertTrue(nav.onBack())
        assertEquals(Screen.MangaOnline, nav.screen)
        assertEquals(MainTab.DISCOVER, nav.selectedTab)
        assertTrue(nav.discoverManga)
    }

    @Test fun changingMainTabRetainsDiscoverAndShelfSections() {
        val nav = Navigator()
        nav.openComics(); nav.openMangaOnline()
        nav.selectTab(MainTab.MINE); nav.selectTab(MainTab.DISCOVER)
        assertEquals(Screen.MangaOnline, nav.screen)
        nav.selectTab(MainTab.LIBRARY)
        assertEquals(LibrarySection.COMIC, nav.librarySection)
    }

    @Test fun readingFromToolHidesToolUntilReturnAndKeepsHistory() {
        val nav = Navigator()
        nav.openMine(); nav.openTool(ToolPage.SOURCES); nav.openTool(ToolPage.UPDATES)
        nav.openReader("book")
        assertNull(nav.toolPage)
        nav.openSettings()
        assertEquals(ToolPage.NOVEL, nav.toolPage)
        nav.onBack()
        assertNull(nav.toolPage)
        assertEquals(Screen.Reading("book"), nav.screen)
        nav.onBack()
        assertEquals(ToolPage.UPDATES, nav.toolPage)
        nav.onBack()
        assertEquals(ToolPage.SOURCES, nav.toolPage)
        nav.onBack()
        assertNull(nav.toolPage)
        assertEquals(MainTab.MINE, nav.selectedTab)
    }

    @Test fun savedNavigationRestoresReaderToolAndTabHistory() {
        val nav = Navigator()
        nav.openComics(); nav.openMangaOnline(); nav.openMine()
        nav.openTool(ToolPage.DOWNLOADS); nav.openTool(ToolPage.UPDATES)
        nav.openComic("book:with:colon", 51)
        nav.openTool(ToolPage.COMIC)
        val restored = Navigator.restore(nav.snapshot())
        assertEquals(nav.snapshot(), restored.snapshot())
        restored.onBack()
        assertNull(restored.toolPage)
        assertEquals(Screen.ComicReading("book:with:colon", 51), restored.screen)
        restored.onBack()
        assertEquals(ToolPage.UPDATES, restored.toolPage)
        restored.onBack()
        assertEquals(ToolPage.DOWNLOADS, restored.toolPage)
        restored.onBack(); restored.onBack()
        assertEquals(MainTab.DISCOVER, restored.selectedTab)
        assertTrue(restored.discoverManga)
    }

    @Test fun malformedOrOldSavedStateFallsBackSafely() {
        assertEquals(Screen.Library, Navigator.restore(emptyList()).screen)
        assertEquals(MainTab.LIBRARY, Navigator.restore(listOf("1", "removed-tab")).selectedTab)
        assertEquals(Screen.Library, Navigator.restore(listOf("99")).screen)
    }

    @Test fun repeatedTabAndToolClicksDoNotDuplicateBackStack() {
        val nav = Navigator()
        nav.openOnline(); nav.selectTab(MainTab.DISCOVER)
        nav.openTool(ToolPage.DOWNLOADS); nav.openTool(ToolPage.DOWNLOADS)
        nav.onBack(); assertNull(nav.toolPage)
        nav.onBack(); assertEquals(MainTab.LIBRARY, nav.selectedTab)
        assertFalse(nav.onBack())
    }
}
