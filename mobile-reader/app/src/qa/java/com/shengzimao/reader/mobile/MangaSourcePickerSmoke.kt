package com.shengzimao.reader.mobile

import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.shengzimao.reader.mobile.data.Consent
import com.shengzimao.reader.mobile.data.Library
import com.shengzimao.reader.mobile.data.MangaSource
import com.shengzimao.reader.mobile.data.MangaStore
import com.shengzimao.reader.mobile.data.LegacyMangaEngine
import com.shengzimao.reader.mobile.data.LegacyRuleRuntime
import com.shengzimao.reader.mobile.ui.MainScaffold
import com.shengzimao.reader.mobile.ui.Navigator
import com.shengzimao.reader.mobile.ui.ReaderViewModel
import com.shengzimao.reader.mobile.ui.theme.ShengZiMaoTheme
import org.json.JSONObject
import java.io.File

/** Exercise the actual dropdown and navigation with touch events, in an isolated library. */
fun mangaSourcePickerSmoke(test: Instrumentation, activity: ComponentActivity, record: (String) -> Unit) {
    val sandbox = File(test.targetContext.cacheDir, "source-picker-${System.nanoTime()}").apply { mkdirs() }
    val context = object : ContextWrapper(test.targetContext) { override fun getFilesDir() = sandbox }
    val container = AppContainer(context)
    container.store.save(Library(consentAccepted = true, consentVersion = Consent.VERSION))
    val sources = (0..2).map { index ->
        MangaSource.importText(JSONObject()
            .put("id", "picker-$index").put("name", if (index == 2) "长名称漫源".repeat(12) else "触摸测试漫源${index + 1}")
            .put("baseUrl", "https://source-$index.example/")
            .put("search", JSONObject().put("url", "/search?q={query}").put("items", ".book").put("title", "a").put("link", "a@href"))
            .put("detail", JSONObject().put("chapters", ".chapter"))
            .put("reader", JSONObject().put("pages", "img@src")).toString()).single()
    }
    MangaStore(File(sandbox, "comics")).saveSources(sources)
    val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    val navigator = Navigator()
    lateinit var model: ReaderViewModel
    test.runOnMainSync {
        model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(container) as T
        })[ReaderViewModel::class.java]
        navigator.openMangaOnline()
        activity.setContent { ShengZiMaoTheme(false) { MainScaffold(model, navigator) } }
    }
    fun node(label: String, prefix: Boolean = false): AccessibilityNodeInfo? {
        val roots = listOfNotNull(qaRoot(test)) + test.uiAutomation.windows.mapNotNull { it.root }
        return roots.firstNotNullOfOrNull { root -> pagingNode(root) {
            it.isVisibleToUser && (if (prefix) it.text?.toString()?.startsWith(label) == true
            else it.text?.toString() == label || it.contentDescription?.toString() == label)
        } }
    }
    fun touch(label: String, long: Boolean = false, prefix: Boolean = false) {
        pagingWait("source picker $label") { node(label, prefix) != null }
        val bounds = Rect().also { node(label, prefix)!!.getBoundsInScreen(it) }
        val started = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            try { check(test.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        Thread.sleep(if (long) ViewConfiguration.getLongPressTimeout() + 250L else 60L)
        send(MotionEvent.ACTION_UP)
        Thread.sleep(200)
    }
    fun openMenu() = touch("搜索范围：", prefix = true)
    fun capture(name: String) {
        val bitmap = test.uiAutomation.takeScreenshot() ?: error("source picker screenshot unavailable")
        try {
            File(test.targetContext.cacheDir, "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    fun assertDetails(source: MangaSource, selected: String) {
        pagingWait("details ${source.id}") {
            model.manga.state.value.browseSourceId == source.id && node("返回在线搜漫") != null
        }
        check(model.manga.state.value.selectedSource == selected) { "opening details changed the search source" }
        check(node("搜索范围：", true) == null) { "dropdown remained visible over details" }
    }
    try {
        pagingWait("source picker loaded") { model.manga.state.value.sources.size == sources.size && node("搜索范围：", true) != null }
        test.runOnMainSync { model.manga.selectSource(sources.first().id) }
        for (source in sources) {
            openMenu()
            if (source == sources.first()) capture("source-picker-menu")
            touch(source.name, long = true)
            assertDetails(source, sources.first().id)
            if (source == sources.first()) capture("source-picker-details")
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            pagingWait("system back from details") { model.manga.state.value.browseSourceId.isEmpty() && node("搜索范围：", true) != null }
        }
        record("MANGA_SOURCE_LONG_PRESS_OK real touch opens correct details for all three sources, including long name; no source switch; system back works")
        openMenu()
        touch("清空")
        touch(sources[1].name)
        touch("完成（1）")
        pagingWait("single click switches source") { model.manga.state.value.selectedSource == sources[1].id }
        check(model.manga.state.value.browseSourceId.isEmpty())
        record("MANGA_SOURCE_SINGLE_TAP_OK switches search scope without opening details")
        openMenu()
        touch(sources[0].name)
        touch("完成（2）")
        pagingWait("multiple source selection") {
            model.manga.state.value.selectedSourceIds == setOf(sources[0].id, sources[1].id)
        }
        check(model.manga.state.value.selectedSource == com.shengzimao.reader.mobile.ui.MangaController.SELECTED_SOURCES)
        record("MANGA_SOURCE_MULTI_SELECT_OK checkbox choices apply together and preserve independent details action")
        openMenu()
        touch("查看${sources[0].name}详情")
        assertDetails(sources[0], com.shengzimao.reader.mobile.ui.MangaController.SELECTED_SOURCES)
        touch("返回在线搜漫")
        pagingWait("toolbar back from details") { model.manga.state.value.browseSourceId.isEmpty() && node("搜索范围：", true) != null }
        openMenu()
        touch("全部可用源（${sources.size}）")
        pagingWait("aggregate scope") { model.manga.state.value.selectedSource == com.shengzimao.reader.mobile.ui.MangaController.ALL_SOURCES }
        record("MANGA_SOURCE_DETAILS_BUTTON_OK correct source, toolbar back, aggregate scope restored")
    } finally {
        test.runOnMainSync { activity.setContent { Text("source picker finished") }; owner.viewModelStore.clear() }
        sandbox.deleteRecursively()
    }
}

/** Android's JSONObject differs from the JVM implementation; test old map syntax on ART too. */
fun mangaSourceRulesSmoke(record: (String) -> Unit) {
    fun source(search: String) = MangaSource.importText(JSONObject()
        .put("bookSourceName", "请求兼容隔离测试").put("bookSourceUrl", "https://source.example/")
        .put("ruleSearchUrl", search).put("ruleSearchList", ".book").put("ruleSearchName", "a@text")
        .put("ruleSearchNoteUrl", "a@href").put("ruleChapterList", ".chapters a")
        .put("ruleChapterName", "text").put("ruleContentUrl", "href").put("ruleBookContent", "img@src")
        .put("ruleChapterUrlNext", "@js:''").toString()).single()
    val response = "<div class='book'><a href='/comic/fixture'>原创漫画</a></div>"
    val mapSource = source("/search?q=searchKey|char=gbk@Header:{Cookie=articlevisited=1}")
    val mapEngine = LegacyMangaEngine({ url, _, body, headers ->
        check(url == "https://source.example/search?q=%C4%A7%D6%E4") { "GBK URL mismatch: $url" }
        check(body == null && headers["Cookie"] == "articlevisited=1") { "Android old map header failed" }
        response
    }, { _, _, _ -> })
    check(mapEngine.search(mapSource, "魔咒", 1).size == 1)
    record("MANGA_LEGACY_MAP_CHARSET_OK Android JSONObject fallback, Cookie map, GBK keyword")
    val jsonSource = source("/api/query@{\"variables\":{\"keyword\":\"searchKey\"},\"query\":\"query { authors { id,name } }\"}")
    val jsonEngine = LegacyMangaEngine({ url, _, body, headers ->
        check(url == "https://source.example/api/query")
        check(JSONObject(body!!).getJSONObject("variables").getString("keyword") == "魔咒 \"A\" & B")
        check(JSONObject(body).getString("query") == "query { authors { id,name } }")
        check(headers["Content-Type"] == "application/json; charset=utf-8")
        response
    }, { _, _, _ -> })
    check(jsonEngine.search(jsonSource, "魔咒 \"A\" & B", 1).size == 1)
    record("MANGA_LEGACY_JSON_POST_OK Unicode, quotes, GraphQL braces, content type")
    val dynamicSource = source("""{{"https://source.example/search?q="+encodeURIComponent(searchKey)+"&offset="+12*(searchPage-1)+"@Header:"+JSON.stringify({Cookie:"webp=1",region:"0",webp:"1"})}}""")
    val dynamicEngine = LegacyMangaEngine({ url, _, _, headers ->
        check(url == "https://source.example/search?q=%E9%AD%94%E5%92%92&offset=24")
        check(headers["Cookie"] == "webp=1" && headers["region"] == "0" && headers["webp"] == "1")
        response
    }, { _, _, _ -> })
    check(dynamicEngine.search(dynamicSource, "魔咒", 3).size == 1)
    val scriptSource = source("""@js:'https://source.example/search?q=test'@Header:{"Cookie":"public=1"}""")
    check(LegacyMangaEngine({ _, _, _, headers -> check(headers["Cookie"] == "public=1"); response }, { _, _, _ -> })
        .search(scriptSource, "test", 1).size == 1)
    record("MANGA_LEGACY_DYNAMIC_HEADERS_OK JS variables, returned headers, suffix headers preserved")
    var requests = 0
    val runtime = LegacyRuleRuntime { _, body, headers ->
        requests++
        check(body == "id=1" && headers["platform"] == "1")
        """{"data":{"pic":["https://image.example/1.png","https://image.example/2.png"]}}"""
    }
    check(runtime.evaluate("@js:JSON.parse(java.post('/pics','id=1',{platform:'1'}).body()).data.pic", "", "https://source.example", true).size == 2)
    check(requests == 1)
    record("MANGA_LEGACY_POST_BODY_OK bounded JS facade returns native text body")
}
