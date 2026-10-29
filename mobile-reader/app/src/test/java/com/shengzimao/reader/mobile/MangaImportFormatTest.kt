package com.shengzimao.reader.mobile

import com.shengzimao.reader.mobile.data.MangaSource
import org.junit.Assert.*
import org.junit.Test

class MangaImportFormatTest {
    private fun failsWith(raw: String, expected: String) {
        val failure = runCatching { MangaSource.importText(raw) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure is IllegalArgumentException && failure.message.orEmpty().contains(expected))
    }
    @Test fun veneraScriptRequiresItsPluginRuntime() = failsWith("class Example extends ComicSource {}", "Venera")
    @Test fun veneraIndexIsNotACollectionOfNativeSourceRules() = failsWith("""[{"key":"example","fileName":"example.js","name":"Example","url":"https://example.invalid/example.js"}]""", "源仓库索引")
    @Test fun mihonExtensionIndexIsNotAJsonRuleSet() = failsWith("""[{"pkg":"example.extension","apk":"example.apk","name":"Example","sources":[]}]""", "Mihon")
    @Test fun mihonRepositoryMetadataRequiresExtensionHost() = failsWith("""{"meta":{"name":"Example","signingKey":"example"}}""", "扩展仓库元信息")
    @Test fun binaryArchiveIsNotMisreportedAsBrokenJson() = failsWith("PK\u0003\u0004example", "二进制")
    @Test fun nativeRulesStillImportWithoutExecutingPluginText() {
        val source = MangaSource.importText(MangaSource.template.replace("我的漫画源", "extends ComicSource"))
        assertEquals(1, source.size)
        assertEquals("extends ComicSource", source.single().name)
    }
}
