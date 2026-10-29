package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.FontChoice
import com.shengzimao.reader.mobile.data.ReaderSettings
import com.shengzimao.reader.mobile.data.ReaderTheme
import com.shengzimao.reader.mobile.data.ReadingPreferences
import com.shengzimao.reader.mobile.data.TurnMode
import org.junit.Assert.*
import org.junit.Test

class NovelIndentTest {
    @Test fun oldBookSnapshotDoesNotDisableGlobalIndentation() {
        val saved = ReadingPreferences.encode(ReaderSettings(fontSize = 26, indentParagraphs = false,
            theme = ReaderTheme.NIGHT, turnMode = TurnMode.HORIZONTAL, font = FontChoice.SYSTEM_MONO))
        val effective = ReadingPreferences.novelForBook(saved, ReaderSettings(indentParagraphs = true))
        assertTrue(effective.indentParagraphs)
        assertEquals(26, effective.fontSize)
        assertEquals(ReaderTheme.NIGHT, effective.theme)
        assertEquals(TurnMode.HORIZONTAL, effective.turnMode)
        assertEquals(FontChoice.SYSTEM_MONO, effective.font)
    }

    @Test fun globalIndentCanBeDisabledAndEnabledWithSameDownloadedBook() {
        for (savedIndent in listOf(false, true)) {
            val saved = ReadingPreferences.encode(ReaderSettings(indentParagraphs = savedIndent))
            for (globalIndent in listOf(true, false, true)) {
                val effective = ReadingPreferences.novelForBook(saved, ReaderSettings(indentParagraphs = globalIndent))
                assertEquals(globalIndent, effective.indentParagraphs)
            }
        }
    }

    @Test fun absentOrInvalidBookPreferencesInheritGlobalIndent() {
        for (raw in listOf("", "invalid", "{}", "{\"fontSize\":24}")) {
            for (globalIndent in listOf(false, true)) {
                assertEquals(globalIndent, ReadingPreferences.novelForBook(raw,
                    ReaderSettings(indentParagraphs = globalIndent)).indentParagraphs)
            }
        }
    }

    @Test fun settingsBackupRetainsDisabledIndentation() {
        val saved = ReadingPreferences.encode(ReaderSettings(indentParagraphs = false))
        assertFalse(ReadingPreferences.novel(saved, ReaderSettings(indentParagraphs = true)).indentParagraphs)
    }
}
