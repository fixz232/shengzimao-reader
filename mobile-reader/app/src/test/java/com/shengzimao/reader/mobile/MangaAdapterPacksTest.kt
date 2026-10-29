package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MangaAdapterPacksTest {
    private fun bundledPack(): MangaAdapterPack = MangaAdapterPack.parse(listOf(
        java.io.File("src/main/assets/manga-adapters/public-compatibility.json"),
        java.io.File("app/src/main/assets/manga-adapters/public-compatibility.json"),
    ).first { it.isFile }.readText(Charsets.UTF_8))

    @Test fun bundledSearchPatchUsesTheWholeNumericIdWithoutCombinedFieldWhitespace() {
        val rule = bundledPack().adapters.first().getJSONObject("patch").getJSONObject("legacy").getString("ruleSearchNoteUrl")
        for (item in listOf("""{"comic_id":39117,"id":0}""", """{"id":73452}""")) {
            val link = LegacyRuleRuntime().evaluate(rule, item, "https://v4api.zaimanhua.com", objectInput = true).single()
            assertTrue(link.contains("/detail/" + JSONObject(item).let { it.optInt("comic_id").takeIf { id -> id != 0 } ?: it.getInt("id") } + "?"))
            assertFalse(link.contains(' '))
        }
    }

    @Test fun bundledTencentPatchExtractsOnlyPublicDataAndBoundedNonceArithmetic() {
        val rule = bundledPack().adapters[1].getJSONObject("patch").getJSONObject("legacy").getString("ruleBookContent")
        fun chapter(readable: Boolean, nonce: String = "\"0a\" + (+eval(\"1+1\")).toString() + \"b\"", property: String = "\"non\"+\"ce\"", position: Int = 2): String {
            val json = """{"chapter":{"canRead":$readable},"picture":[{"url":"https://manhua.acimg.cn/original.jpg"}]}"""
            val encoded = StringBuilder(java.util.Base64.getEncoder().encodeToString(json.toByteArray())).apply { insert(0, "a"); insert(position, "b") }
            return "<script>document.mustNeverRun(); window[\"nonce\"] = '' + 'old-seed';</script><script>window[$property] = $nonce; var DATA = '$encoded';</script>"
        }
        assertEquals(listOf("https://manhua.acimg.cn/original.jpg"), LegacyRuleRuntime().evaluate(rule, chapter(true), "https://m.ac.qq.com/chapter/1", true))
        assertEquals(listOf("https://manhua.acimg.cn/original.jpg"), LegacyRuleRuntime().evaluate(rule,
            chapter(true, "\"0a\" + (+eval(\"parseInt(7/3)\")).toString() + \"b\""), "https://m.ac.qq.com/chapter/1", true))
        for (property in listOf("\"no\"+\"nce\"", "\"n\"+\"once\"", "\"nonce\""))
            for (expression in listOf("1 + 1", "0<=3?2:1", "!!0 + 2"))
                assertEquals(listOf("https://manhua.acimg.cn/original.jpg"), LegacyRuleRuntime().evaluate(rule,
                    chapter(true, "\"0a\" + (+eval(\"$expression\")).toString() + \"b\"", property), "https://m.ac.qq.com/chapter/1", true))
        for ((expression, value) in listOf("Math.round(.5) + ~~1.8" to 2, "1 * (!window.Array) + 1" to 1,
            "1 - !!document.children" to 0, "'123'.substring(2)" to 3, "5&5" to 5,
            "!!document.getElementsByTagName('html')" to 1, "!!1+!1+!!2+!!2+1" to 4))
            assertEquals(listOf("https://manhua.acimg.cn/original.jpg"), LegacyRuleRuntime().evaluate(rule,
                chapter(true, "\"0a\" + (+eval(\"$expression\")).toString() + \"b\"", position = value), "https://m.ac.qq.com/chapter/1", true))
        val failure = runCatching { LegacyRuleRuntime().evaluate(rule, chapter(false), "https://m.ac.qq.com/chapter/1", true) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("登录、购买或验证"))
        assertTrue(runCatching { LegacyRuleRuntime().evaluate(rule, chapter(true, "document.cookie"), "https://m.ac.qq.com/chapter/1", true) }.isFailure)
        assertTrue(runCatching { LegacyRuleRuntime().evaluate(rule,
            chapter(true, "\"0a\" + (+eval(\"java.get('secret')\")).toString() + \"b\""), "https://m.ac.qq.com/chapter/1", true) }.isFailure)
        assertTrue(runCatching { LegacyRuleRuntime().evaluate(rule,
            chapter(true, "\"0a\" + (+eval(\"document.getElementsByTagName('script')\")).toString() + \"b\""), "https://m.ac.qq.com/chapter/1", true) }.isFailure)
    }

    @Test fun bundledZaimanhuaPatchReportsTheOriginalAccessStateWithoutInventingPages() {
        val rule = bundledPack().adapters.first().getJSONObject("patch").getJSONObject("legacy").getString("ruleBookContent")
        val runtime = LegacyRuleRuntime()
        assertEquals(listOf("https://images.example/original.jpg"), runtime.evaluate(rule,
            """{"errno":0,"data":{"data":{"canRead":true,"page_url":["https://images.example/original.jpg"]}}}""", "https://v4api.zaimanhua.com/chapter", true))
        val failure = runCatching { runtime.evaluate(rule,
            """{"errno":0,"data":{"data":{"canRead":false,"page_url":[]}}}""", "https://v4api.zaimanhua.com/chapter", true) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("canRead=false"))
    }

    @Test fun bundledTencentDesktopHeaderIsLimitedToChapterRequests() = withRegistry { registry, _ ->
        val original = MangaSource.parse(source("m.ac.qq.com").json().put("id", "legado-1563cfeab6af3ba37380")
            .put("headers", JSONObject().put("User-Agent", "mobile-fixture"))
            .put("legacy", JSONObject().put("bookSourceUrl", "https://m.ac.qq.com")
                .put("ruleSearchUrl", "/search?q=searchKey").put("ruleSearchList", "a")
                .put("ruleSearchName", "text").put("ruleSearchNoteUrl", "href")
                .put("ruleChapterList", "a").put("ruleChapterName", "text").put("ruleContentUrl", "href")))
        registry.install(bundledPack(), listOf(original))
        val active = registry.effective(original)
        var chapterAgent = ""
        val engine = LegacyMangaEngine({ url, source, _, headers ->
            assertEquals("mobile-fixture", source.headers["User-Agent"])
            when {
                url.contains("/search") -> { assertFalse(headers.containsKey("User-Agent")); "<a href='/book'>原创</a>" }
                url.endsWith("/book") -> { assertFalse(headers.containsKey("User-Agent")); "<a href='/chapter'>原创一话</a>" }
                else -> { chapterAgent = headers["User-Agent"].orEmpty(); "<html>source-structure-unavailable</html>" }
            }
        }) { _, _, _ -> }
        val book = engine.details(active, engine.search(active, "原创", 1).single())
        assertEquals("Mozilla/5.0", book.chapters.single().requestHeaders["User-Agent"])
        assertTrue(runCatching { engine.pages(active, book.chapters.single()) }.isFailure)
        assertEquals("Mozilla/5.0", chapterAgent)
    }

    private fun source(host: String = "example.org") = MangaSource.parse(JSONObject(MangaSource.template)
        .put("id", "sample").put("baseUrl", "https://$host").put("enabled", false))

    private fun pack(version: Int = 1, host: String = "example.org", imageHost: String = "cdn.example.org",
        customize: (JSONObject) -> Unit = {}): MangaAdapterPack {
        val adapter = JSONObject().put("id", "sample-fix").put("hosts", JSONArray(listOf(host)))
            .put("imageHosts", JSONArray(listOf(imageHost)))
            .put("patch", JSONObject().put("reader", JSONObject().put("pages", ".new img")))
            .put("imageHeaders", JSONObject().put("Referer", "{chapterUrl}"))
        customize(adapter)
        return MangaAdapterPack.parse(JSONObject().put("kind", MangaAdapterPack.KIND).put("schemaVersion", 1)
            .put("id", "sample-pack").put("name", "样本站点适配").put("version", version)
            .put("updateUrl", "https://example.org/adapter.json").put("adapters", JSONArray().put(adapter)).toString())
    }

    private fun withRegistry(block: (MangaAdapterRegistry, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("adapter-test-").toFile()
        try { block(MangaAdapterRegistry(java.io.File(root, "packs.json")), root) }
        finally { root.deleteRecursively() }
    }

    @Test fun updateIsScopedAndKeepsOriginalAndDisabledState() = withRegistry { registry, _ ->
        val original = source()
        registry.install(pack(), listOf(original))
        val active = registry.effective(original)
        assertEquals(".new img", active.rules.getJSONObject("reader").getString("pages"))
        assertFalse(active.enabled)
        assertEquals(original.id, active.id)
        assertEquals(".reader img", original.rules.getJSONObject("reader").getString("pages"))
        assertNotEquals(original.ruleVersion, active.ruleVersion)
        assertEquals(source("other.org"), registry.effective(source("other.org")))
        assertEquals(active, registry.effective(active))
    }

    @Test fun updateRollbackDisableRemoveAndRestartRestoreExactly() = withRegistry { registry, root ->
        val source = source()
        val one = pack()
        val two = pack(2) { it.getJSONObject("patch").getJSONObject("reader").put("pages", ".v2 img") }
        registry.install(one, listOf(source)); registry.install(two, listOf(source))
        var reloaded = MangaAdapterRegistry(java.io.File(root, "packs.json"))
        assertEquals(".v2 img", reloaded.effective(source).rules.getJSONObject("reader").getString("pages"))
        reloaded.rollback(one.id, listOf(source))
        reloaded = MangaAdapterRegistry(java.io.File(root, "packs.json"))
        assertEquals(".new img", reloaded.effective(source).rules.getJSONObject("reader").getString("pages"))
        reloaded.toggle(one.id, listOf(source)); assertEquals(source, reloaded.effective(source))
        reloaded.remove(one.id); assertTrue(reloaded.entries().isEmpty())
        assertEquals(source, reloaded.effective(source))
    }

    @Test fun invalidUpdateDoesNotReplaceWorkingDocument() = withRegistry { registry, root ->
        registry.install(pack(), listOf(source()))
        val before = java.io.File(root, "packs.json").readText()
        val invalid = pack(2) { it.getJSONObject("patch").put("reader", JSONObject().put("pages", "")) }
        assertThrows(IllegalArgumentException::class.java) { registry.install(invalid, listOf(source())) }
        assertEquals(before, java.io.File(root, "packs.json").readText())
        assertEquals(1, registry.entries().single().pack.version)
    }

    @Test fun updateRejectsSameVersionChangedContentsAndDowngrade() = withRegistry { registry, _ ->
        registry.install(pack(2), listOf(source()))
        val revision = registry.revision
        registry.install(pack(2), listOf(source()))
        assertEquals(revision, registry.revision)
        assertThrows(IllegalArgumentException::class.java) { registry.install(pack(), listOf(source())) }
        val tampered = pack(2) { it.getJSONObject("patch").getJSONObject("reader").put("pages", ".tampered") }
        assertThrows(IllegalArgumentException::class.java) { registry.install(tampered, listOf(source())) }
    }

    @Test fun importingUnchangedContentsFromALinkRemembersTheMaintenanceOrigin() = withRegistry { registry, root ->
        val packageWithoutUrl = MangaAdapterPack.parse(JSONObject(pack().definition).apply { remove("updateUrl") }.toString())
        registry.install(packageWithoutUrl, listOf(source()), "应用内置兼容包")
        registry.install(packageWithoutUrl, listOf(source()), "https://example.org/current.json")
        val saved = MangaAdapterRegistry(java.io.File(root, "packs.json")).entries().single()
        assertEquals("https://example.org/current.json", saved.origin)
        assertEquals(packageWithoutUrl.definition, saved.pack.definition)
        assertNull(saved.previous)
    }

    @Test fun exactHostAndOptionalSourceIdPreventUnrelatedPatches() = withRegistry { registry, _ ->
        registry.install(pack() { it.put("sourceIds", JSONArray(listOf("other-source"))) }, listOf(source()))
        assertEquals(source(), registry.effective(source()))
        assertEquals(source("evil-example.org"), registry.effective(source("evil-example.org")))
    }

    @Test fun completedDownloadsKeepTheirStoredRuleContextAcrossAnAdapterUpdate() = withRegistry { _, root ->
        val original = source()
        val store = MangaStore(java.io.File(root, "comics")); store.saveSources(listOf(original))
        val chapter = MangaChapter("原创一话", "https://example.org/chapter", pages = listOf("https://cdn.example.org/1.jpg"), imageRuleVersion = original.ruleVersion)
        val book = MangaBook("original-fixture", "原创作品", original.id, original.name, "https://example.org/book",
            chapters = listOf(chapter), cachedChapters = setOf(chapter.url))
        store.upsert(book)
        val temporary = store.cachedImage(original.id, chapter.pages.single(), context = MangaImageContext.of(book, chapter, 0))
        temporary.writeBytes(byteArrayOf(1))
        assertFalse(store.hasCompleteDownload(book, chapter))
        val downloaded = java.io.File(store.downloadDir, temporary.name)
        temporary.copyTo(downloaded)
        assertTrue(store.hasCompleteDownload(book, chapter))
        store.adapters.install(pack(), store.sources())
        assertNotEquals(original.ruleVersion, store.adapters.effective(original).ruleVersion)
        assertTrue(store.hasCompleteDownload(book, chapter))
        assertFalse(store.hasCompleteDownload(book.copy(cachedChapters = emptySet()), chapter))
        downloaded.delete()
        assertFalse(store.hasCompleteDownload(book, chapter))
    }

    @Test fun declaredMirrorsPreservePathQueryAndNeverInventHosts() = withRegistry { registry, _ ->
        registry.install(pack(imageHost = "img.example.org") { rule ->
            rule.put("imageHosts", JSONArray(listOf("cdn.example.org", "img.example.org")))
            rule.put("imageMirrors", JSONArray().put(JSONObject().put("fromHost", "cdn.example.org")
                .put("pathPrefix", "/comic/").put("toBaseUrl", "https://img.example.org/public/")))
        }, listOf(source()))
        assertEquals(listOf("https://cdn.example.org/comic/1.jpg?sign=keep", "https://img.example.org/public/1.jpg?sign=keep"),
            registry.imageCandidates(source(), "https://cdn.example.org/comic/1.jpg?sign=keep"))
        assertEquals(listOf("https://other.org/comic/1.jpg"), registry.imageCandidates(source(), "https://other.org/comic/1.jpg"))
        assertEquals(emptyMap<String, String>(), registry.imageHeaders(source(), "https://example.org/chapter", "https://other.org/image"))
        assertEquals(mapOf("Referer" to "https://example.org/chapter"), registry.imageHeaders(source(), "https://example.org/chapter", "https://img.example.org/image"))
    }

    @Test fun rejectsGlobalScopeCredentialHeadersUndeclaredRoutesAndNewerEngines() {
        assertThrows(IllegalArgumentException::class.java) { pack(host = "*.example.org") }
        assertThrows(IllegalArgumentException::class.java) { pack() { it.getJSONObject("patch").put("enabled", true) } }
        assertThrows(IllegalArgumentException::class.java) { pack() { it.put("imageHeaders", JSONObject().put("Cookie", "token")) } }
        assertThrows(IllegalArgumentException::class.java) { pack() { it.put("imageMirrors", JSONArray().put(JSONObject()
            .put("fromHost", "cdn.example.org").put("toBaseUrl", "https://unrelated.org"))) } }
        val definition = JSONObject(pack().definition).put("minEngineVersion", 2).toString()
        assertThrows(IllegalArgumentException::class.java) { MangaAdapterPack.parse(definition) }
    }

    @Test fun corruptStorageIsPreservedAndNeverSilentlyOverwritten() {
        val root = Files.createTempDirectory("adapter-corrupt-").toFile()
        try {
            val file = java.io.File(root, "packs.json").apply { writeText("broken-json") }
            val registry = MangaAdapterRegistry(file)
            assertTrue(registry.loadError.isNotBlank())
            assertEquals(source(), registry.effective(source()))
            assertThrows(IllegalStateException::class.java) { registry.install(pack(), listOf(source())) }
            assertEquals("broken-json", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidStorageEntriesNeverBecomeActive() = withRegistry { _, root ->
        val entry = JSONObject().put("pack", JSONObject(pack().definition))
        val duplicate = JSONArray().put(entry).put(JSONObject(entry.toString())).toString()
        val file = java.io.File(root, "duplicate-packs.json").apply { writeText(duplicate) }
        val registry = MangaAdapterRegistry(file)
        assertTrue(registry.loadError.isNotBlank())
        assertTrue(registry.entries().isEmpty())
        assertEquals(source(), registry.effective(source()))
        assertEquals(duplicate, file.readText())
    }

    @Test fun twoLayeredPacksCannotMutateEachOthersStoredRules() = withRegistry { registry, _ ->
        val first = pack()
        val secondJson = JSONObject(pack().definition).put("id", "second-pack")
        secondJson.getJSONArray("adapters").getJSONObject(0).getJSONObject("patch").getJSONObject("reader").put("pages", ".second img")
        registry.install(first, listOf(source()))
        registry.install(MangaAdapterPack.parse(secondJson.toString()), listOf(source()))
        assertEquals(".second img", registry.effective(source()).rules.getJSONObject("reader").getString("pages"))
        registry.remove("second-pack")
        assertEquals(".new img", registry.effective(source()).rules.getJSONObject("reader").getString("pages"))
        assertEquals(first.definition, registry.entries().single().pack.definition)
    }

    @Test fun productionDownloaderUsesPackHeadersAndReallyDecodesDownloadedBytes() = runBlocking {
        val root = Files.createTempDirectory("adapter-image-").toFile()
        val image = java.awt.image.BufferedImage(160, 220, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, (x * 17171 + y * 65657) and 0xffffff)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val allowed = exchange.requestHeaders.getFirst("Referer") == "https://example.org/allowed"
            exchange.responseHeaders.add("Content-Type", if (allowed) "image/png" else "text/html")
            val payload = if (allowed) bytes else "access denied".toByteArray()
            exchange.sendResponseHeaders(if (allowed) 200 else 403, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val source = MangaSource.parse(source("127.0.0.1").json().put("baseUrl", origin).put("enabled", true))
            val store = MangaStore(root); store.saveSources(listOf(source))
            var decoded = 0
            val backend = object : MangaImageRuntime {
                override fun checkCached(file: java.io.File) { verify(file) }
                override fun verify(file: java.io.File) {
                    val bitmap = javax.imageio.ImageIO.read(file) ?: error("invalid downloaded image")
                    assertEquals(160, bitmap.width); assertEquals(220, bitmap.height); decoded++
                }
                override fun decode(file: java.io.File, reference: String, spec: MangaDecoder.Spec, cover: Boolean) = error("plain fixture must not use a source decoder")
            }
            val engine = MangaEngine(store, imageRuntime = backend)
            assertTrue(runCatching { engine.imageFile("$origin/image.png", source.id) }.isFailure)
            store.adapters.install(pack(host = "127.0.0.1", imageHost = "127.0.0.1") {
                it.put("imageHeaders", JSONObject().put("Referer", "https://example.org/allowed"))
            }, store.sources())
            val file = engine.imageFile("$origin/image.png", source.id,
                context = MangaImageContext("book", "chapter", 0, engine.ruleVersion(source)))
            assertTrue(file.isFile); assertArrayEquals(bytes, file.readBytes()); assertEquals(1, decoded)
            store.adapters.install(pack(2, host = "127.0.0.1", imageHost = "127.0.0.1") {
                it.remove("imageHeaders")
                it.getJSONObject("patch").put("headers", JSONObject().put("Referer", "https://example.org/allowed"))
            }, store.sources())
            val chapter = MangaChapter("原创", "$origin/chapter")
            val viaPatchedSourceHeader = engine.imageFile("$origin/second.png", source.id,
                headers = engine.imageHeaders(source, chapter, "$origin/second.png"))
            assertArrayEquals(bytes, viaPatchedSourceHeader.readBytes()); assertEquals(2, decoded)
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun productionSearchBrowseDirectoryAndPagesImmediatelyUseImportedPatch() = runBlocking {
        val root = Files.createTempDirectory("adapter-engine-").toFile()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/search", "/rank" -> "<a class='hit' href='/book'><span class='title'>原创测试</span></a>"
                "/book" -> "<a class='chapter' href='/chapter'>第一话</a>"
                else -> "<img class='page' src='/image.png'>"
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val original = source("127.0.0.1").copy(baseUrl = "http://127.0.0.1:${server.address.port}").let {
                MangaSource.parse(it.json().put("baseUrl", it.baseUrl).put("enabled", true))
            }
            val store = MangaStore(root); store.saveSources(listOf(original))
            val engine = MangaEngine(store)
            assertTrue(engine.search(original, "原创", 1).isEmpty())
            store.adapters.install(pack(host = "127.0.0.1", imageHost = "127.0.0.1") {
                it.put("overrideReader", true)
                it.put("patch", JSONObject().put("search", JSONObject().put("url", "/search?q={query}")
                    .put("items", "a.hit").put("title", ".title").put("link", "@href"))
                    .put("detail", JSONObject().put("chapters", "a.chapter").put("chapterUrl", "@href"))
                    .put("reader", JSONObject().put("pages", "img.page").put("image", "@src"))
                    .put("discover", JSONObject().put("items", "a.hit").put("title", ".title").put("link", "@href")
                        .put("groups", JSONArray().put(JSONObject().put("name", "原创榜单")
                            .put("entries", JSONArray().put(JSONObject().put("name", "最新").put("url", "/rank")))))))
            }, store.sources())
            val found = engine.search(original, "原创", 1).single()
            val discover = MangaDiscover.from(store.adapters.effective(original))!!
            val browsed = engine.browse(original, discover, discover.groups.single().entries.single()).single()
            assertEquals(found.id, browsed.id)
            assertEquals(found.url, browsed.url)
            assertEquals(found.title, browsed.title)
            val book = engine.details(original, found)
            assertEquals(1, book.chapters.size)
            assertTrue(engine.pages(original, book.chapters.single()).single().endsWith("/image.png"))
            store.adapters.remove("sample-pack")
            assertTrue(engine.search(original, "原创", 1).isEmpty())
        } finally { server.stop(0); root.deleteRecursively() }
    }
}
