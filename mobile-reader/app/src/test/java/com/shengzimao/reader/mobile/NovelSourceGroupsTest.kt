package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class NovelSourceGroupsTest {
    private fun rule(name: String = "原创测试源", url: String = "https://example.invalid/"): JSONObject = JSONObject()
        .put("name", name).put("url", url)
        .put("search", JSONObject().put("url", "${url}search?q=%s").put("method", "get")
            .put("result", ".result").put("bookName", "a"))
        .put("toc", JSONObject().put("item", ".chapters a"))
        .put("chapter", JSONObject().put("content", "#content"))

    private fun inStore(block: (NovelSourceGroups, File, String) -> Unit) {
        val dir = Files.createTempDirectory("novel-source-groups").toFile()
        val builtins = JSONArray().put(rule("内置源")).toString()
        try { block(NovelSourceGroups(dir) { builtins }, dir, builtins) }
        finally { dir.deleteRecursively() }
    }

    @Test fun defaultIsBuiltinAndOtherGroupsAreEmpty() = inStore { store, _, _ ->
        assertEquals(1, store.selected())
        assertEquals("内置源", store.entries(1).single().name)
        (2..5).forEach { assertTrue(store.entries(it).isEmpty()) }
    }

    @Test fun searchScopesAreIndependentPersistedAndDoNotRewriteRules() = inStore { store, dir, builtins ->
        store.import(2, JSONArray().put(rule()).put(rule("另一个", "https://another.invalid/")).toString())
        val before = store.rulesFile(2).readText()
        store.selectSources(1, emptySet())
        store.selectSources(2, setOf("https://another.invalid/"))
        val restored = NovelSourceGroups(dir) { builtins }
        assertEquals(emptySet<String>(), restored.selectedSources(1))
        assertEquals(setOf("https://another.invalid/"), restored.selectedSources(2))
        assertNull(restored.selectedSources(3))
        assertEquals(before, store.rulesFile(2).readText())
        assertEquals(builtins, store.rules(1).toString())
        store.selectSources(2, null)
        assertNull(restored.selectedSources(2))
    }

    @Test fun unknownScopeCannotReplaceExistingChoice() = inStore { store, _, _ ->
        store.import(2, rule().toString())
        store.selectSources(2, emptySet())
        assertTrue(runCatching { store.selectSources(2, setOf("https://unknown.invalid/")) }.isFailure)
        assertEquals(emptySet<String>(), store.selectedSources(2))
    }

    @Test fun singleSourceScopeActuallyRestrictsSearchAndSurvivesReload() = inStore { store, dir, builtins ->
        store.import(2, JSONArray().put(rule()).put(rule("另一个", "https://another.invalid/")).toString())
        store.selectSources(2, setOf("https://another.invalid/"))
        assertEquals(listOf("另一个"), store.searchEntries(2).map { it.name })
        assertEquals(store.searchEntries(2), NovelSourceGroups(dir) { builtins }.searchEntries(2))
        assertEquals(listOf("内置源"), store.searchEntries(1).map { it.name })
    }

    @Test fun allSourceScopeIncludesOnlySearchableSourcesInCurrentGroup() = inStore { store, _, _ ->
        store.import(2, JSONArray().put(rule()).put(rule("另一个", "https://another.invalid/"))
            .put(rule("禁用", "https://disabled.invalid/").put("disabled", true))
            .put(rule("只读", "https://read.invalid/").also { it.remove("search") })
            .put(rule("停搜", "https://paused.invalid/").also { it.getJSONObject("search").put("disabled", true) }).toString())
        store.import(3, rule("其他分组", "https://other-group.invalid/").toString())
        store.selectSources(2, setOf("https://another.invalid/"))
        assertEquals(listOf("原创测试源", "另一个"), store.searchEntries(2, null).map { it.name })
        store.selectSources(2, null)
        assertEquals(listOf("原创测试源", "另一个"), store.searchEntries(2).map { it.name })
        assertTrue(store.searchEntries(2, emptySet()).isEmpty())
        assertTrue(store.searchEntries(2, setOf("https://other-group.invalid/")).isEmpty())
        assertTrue(store.searchEntries(2, setOf("https://disabled.invalid/")).isEmpty())
    }

    @Test fun selectedScopeSurvivesDomainCaseAndTrailingSlashRuleUpdate() = inStore { store, _, _ ->
        store.import(2, rule().toString())
        store.selectSources(2, setOf("https://example.invalid/"))
        store.import(2, rule("更新", "https://EXAMPLE.invalid").toString())
        assertEquals(setOf("https://EXAMPLE.invalid"), store.selectedSources(2))
    }

    @Test fun builtinGroupCannotBeImportedOrOverwritten() = inStore { store, _, builtins ->
        assertTrue(runCatching { store.import(1, rule().toString()) }.isFailure)
        assertEquals(builtins, store.rules(1).toString())
        assertTrue(runCatching { store.rulesFile(1) }.isFailure)
    }

    @Test fun importsRemainIsolatedIncludingMatchingHosts() = inStore { store, _, _ ->
        (2..5).forEach { group ->
            val report = store.import(group, rule("用户组 $group").toString())
            assertEquals(1, report.added)
            assertEquals(1, report.total)
        }
        assertEquals("内置源", store.entries(1).single().name)
        (2..5).forEach { assertEquals("用户组 $it", store.entries(it).single().name) }
    }

    @Test fun selectionAndImportedRulesSurviveReload() = inStore { store, dir, builtins ->
        store.import(4, rule().toString())
        store.select(4)
        val restored = NovelSourceGroups(dir) { builtins }
        assertEquals(4, restored.selected())
        assertEquals(store.entries(4), restored.entries(4))
        assertFalse(File(dir, "group-1.json").exists())
    }

    @Test fun sameSiteUpdatesWithinGroupAndPreservesOtherSites() = inStore { store, _, _ ->
        store.import(2, JSONArray().put(rule()).put(rule("另一个源", "https://another.invalid/")).toString())
        val report = store.import(2, rule("更新后", "https://EXAMPLE.invalid").toString())
        assertEquals(0, report.added)
        assertEquals(1, report.updated)
        assertEquals(2, report.total)
        assertEquals(listOf("更新后", "另一个源"), store.entries(2).map { it.name })
    }

    @Test fun arraysAndRulesWrapperAreSupported() = inStore { store, _, _ ->
        val array = JSONArray().put(rule()).put(rule("第二个源", "https://two.invalid"))
        store.import(2, JSONObject().put("rules", array).toString())
        store.import(3, array.toString())
        assertEquals(2, store.entries(2).size)
        assertEquals(store.entries(2), store.entries(3))
    }

    @Test fun invalidEntriesAreReportedAndValidEntriesAreNotLost() = inStore { store, _, _ ->
        val report = store.import(2, JSONArray().put(rule()).put(JSONObject().put("name", "缺少规则")).put(23).toString())
        assertEquals(1, report.added)
        assertEquals(2, report.skipped)
        assertEquals(1, store.entries(2).size)
    }

    @Test fun repeatedEntriesAreDeduplicated() = inStore { store, _, _ ->
        val report = store.import(2, JSONArray().put(rule("旧名称")).put(rule("新名称")).toString())
        assertEquals(1, report.added)
        assertEquals(1, report.skipped)
        assertEquals("新名称", store.entries(2).single().name)
    }

    @Test fun failedImportLeavesExistingBytesUntouched() = inStore { store, _, _ ->
        store.import(2, rule().toString())
        val original = store.rulesFile(2).readBytes()
        listOf("<html>错误页</html>", "[]", "{}", "[invalid]", rule().toString() + " trailing",
            JSONObject().put("bookSourceUrl", "https://legado.invalid").toString()).forEach { input ->
            assertTrue(input, runCatching { store.import(2, input) }.isFailure)
            assertArrayEquals(original, store.rulesFile(2).readBytes())
        }
    }

    @Test fun invalidSchemesAndIncompleteRulesAreRejected() = inStore { store, _, _ ->
        listOf("file:///tmp/rules", "javascript:alert(1)", "https://user:pass@example.invalid/", "https://example.invalid/?q=x").forEach { url ->
            assertTrue(url, runCatching { store.import(2, rule(url = url).toString()) }.isFailure)
        }
        assertTrue(runCatching { store.import(2, rule().also { it.remove("chapter") }.toString()) }.isFailure)
        assertTrue(runCatching { store.import(2, rule().also { it.remove("toc") }.toString()) }.isFailure)
    }

    @Test fun disabledAndReadOnlySourcesDoNotBecomeSearchable() = inStore { store, _, _ ->
        store.import(2, rule().put("disabled", true).toString())
        store.import(3, rule().also { it.remove("search") }.toString())
        store.import(4, rule().also { it.getJSONObject("search").put("disabled", true) }.toString())
        (2..4).forEach { assertFalse(store.entries(it).single().searchable) }
    }

    @Test fun missingOptionalChapterFieldsReceiveSafeEngineDefaults() = inStore { store, _, _ ->
        store.import(2, rule().toString())
        val chapter = store.rules(2).getJSONObject(0).getJSONObject("chapter")
        assertEquals("", chapter.getString("filterTxt"))
        assertEquals("", chapter.getString("filterTag"))
        assertEquals("(?i)<br\\s*/?>|\\r?\\n", chapter.getString("paragraphTag"))
        assertEquals(listOf("一", "二", "三"), "一<br />二\n三".split(Regex(chapter.getString("paragraphTag"))))
        assertTrue(runCatching { store.import(3, rule().also { it.getJSONObject("chapter").put("filterTxt", "[") }.toString()) }.isFailure)
    }

    @Test fun bomAndBoundedInputAreSupported() = inStore { store, _, _ ->
        store.import(2, "\uFEFF" + rule().toString())
        assertEquals(1, store.entries(2).size)
        assertEquals("书源", NovelSourceGroups.readImport(ByteArrayInputStream("书源".toByteArray())))
        assertTrue(runCatching { NovelSourceGroups.readImport(ByteArrayInputStream(ByteArray(NovelSourceGroups.MAX_BYTES + 1))) }.isFailure)
    }

    @Test fun importAndSelectionRejectOutOfRangeGroups() = inStore { store, _, _ ->
        listOf(-1, 0, 6, Int.MAX_VALUE).forEach { group ->
            assertTrue(runCatching { store.select(group) }.isFailure)
            assertTrue(runCatching { store.entries(group) }.isFailure)
            assertTrue(runCatching { store.import(group, rule().toString()) }.isFailure)
        }
        assertEquals(1, store.selected())
    }

    @Test fun groupMetadataSurvivesResultsLibraryAndBackupRoundTrips() = inStore { _, dir, _ ->
        val result = OnlineResult(bookName = "原创作品", url = "https://example.invalid/book", sourceGroup = 5)
        assertEquals(5, OnlineResult.fromJson(result.toJson()).sourceGroup)
        assertEquals(1, OnlineResult.fromJson(JSONObject()).sourceGroup)
        val book = Book(id = "group-book-001", title = result.bookName, onlineUrl = result.url, onlineOnly = true, onlineSourceGroup = 5)
        val library = Library(books = listOf(book))
        assertEquals(5, LibraryJson.decode(LibraryJson.encode(library)).books.single().onlineSourceGroup)
        val stream = ByteArrayOutputStream()
        Backup.export(LibraryStore(File(dir, "source")), library, null, stream)
        val restored = LibraryStore(File(dir, "restored"))
        Backup.import(restored, Library(), stream.toByteArray().inputStream())
        assertEquals(5, restored.load().books.single().onlineSourceGroup)
    }

    @Test fun matchingUrlsHaveSeparateCachesAndBuiltinKeysStayCompatible() = inStore { _, dir, _ ->
        val cache = OnlineReadingCache(File(dir, "cache"))
        val url = "https://example.invalid/book"
        val chapter = "$url/1"
        assertEquals(url, NovelSourceGroups.identity(url, 1))
        (1..5).forEach { group -> cache.write(NovelSourceGroups.identity(url, group), chapter, "分组 $group 的正文") }
        (1..5).forEach { group -> assertEquals("分组 $group 的正文", cache.read(NovelSourceGroups.identity(url, group), chapter)) }
        assertEquals(5, (1..5).map { NovelSourceGroups.identity(url, it) }.toSet().size)
    }
}
