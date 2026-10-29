package com.shengzimao.reader.mobile.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Versioned data packages, interpreted by the existing rule engine rather than loaded as native code. */
data class MangaAdapterPack(
    val id: String, val name: String, val version: Int, val updateUrl: String,
    val definition: String, val installedAt: Long = System.currentTimeMillis(),
) {
    val fingerprint: String get() = mangaHash(definition)
    internal val adapters: List<JSONObject> by lazy { JSONObject(definition).getJSONArray("adapters").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }
    } }
    private val byHost: Map<String, List<JSONObject>> by lazy {
        adapters.flatMap { rule -> rule.getJSONArray("hosts").strings().map { it.lowercase() to rule } }
            .groupBy({ it.first }, { it.second })
    }
    internal fun rulesFor(host: String) = byHost[host].orEmpty()

    companion object {
        const val ENGINE_VERSION = 1
        const val MAX_BYTES = 2 * 1024 * 1024
        const val KIND = "szm.manga-adapters"
        private val identifiers = Regex("[A-Za-z0-9._-]{1,80}")
        private val patchKeys = setOf("headers", "search", "detail", "reader", "legacy", "discover",
            "legacyEncryptionBaseUrl", "compatibilityNote")

        fun parse(text: String): MangaAdapterPack {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "站点适配包不能超过 2 MB" }
            val j = JSONObject(text.trim().removePrefix("\uFEFF"))
            require(j.optString("kind") == KIND && j.optInt("schemaVersion") == 1) { "不是受支持的站点适配包" }
            require(j.optInt("minEngineVersion", 1) in 1..ENGINE_VERSION) { "此适配包需要更新应用引擎" }
            val id = j.getString("id")
            require(identifiers.matches(id)) { "适配包 ID 无效" }
            val name = j.getString("name").trim()
            require(name.length in 1..80 && j.getInt("version") > 0) { "适配包名称或版本无效" }
            val update = j.optString("updateUrl").trim()
            if (update.isNotEmpty()) require(mangaHttpUrl(update).toHttpUrl().isHttps) { "适配包更新地址必须使用 HTTPS" }
            val adapters = j.getJSONArray("adapters")
            require(adapters.length() in 1..100) { "每个适配包需要 1–100 项适配规则" }
            val ids = hashSetOf<String>()
            for (i in 0 until adapters.length()) {
                val rule = adapters.getJSONObject(i)
                require(identifiers.matches(rule.getString("id")) && ids.add(rule.getString("id"))) { "适配规则 ID 无效或重复" }
                val hosts = hosts(rule, "hosts")
                require(hosts.isNotEmpty()) { "适配规则必须限定站点 hosts" }
                val resources = hosts + hosts(rule, "imageHosts") + hosts(rule, "resourceHosts")
                rule.optJSONArray("sourceIds")?.strings()?.forEach { require(identifiers.matches(it)) { "适配源 ID 无效" } }
                rule.optJSONObject("patch")?.let { patch ->
                    require(patch.keys().asSequence().all { it in patchKeys }) { "适配包不能修改源 ID、名称或启停状态" }
                    patch.optJSONObject("headers")?.let(::validateHeaders)
                    listOf("search", "detail", "reader").forEach { key ->
                        require(!patch.has(key) || patch.optJSONObject(key) != null) { "适配包的 $key 必须为规则对象" }
                    }
                    // The legacy runtime already enforces execution and request budgets.
                    require(patch.toString().length <= 256 * 1024) { "单项适配规则过大" }
                }
                rule.optJSONObject("imageHeaders")?.let(::validateHeaders)
                listOf("requestRoutes", "imageMirrors").forEach { key ->
                    val routes = rule.optJSONArray(key) ?: JSONArray()
                    require(routes.length() <= 12) { "单项线路数量超过上限" }
                    for (r in 0 until routes.length()) {
                        val route = routes.getJSONObject(r)
                        require(route.getString("fromHost").lowercase() in resources) { "线路原域名不在声明范围内" }
                        val target = mangaHttpUrl(route.getString("toBaseUrl")).toHttpUrl()
                        require(target.host in resources && target.query == null && target.fragment == null) { "线路目标必须在声明范围内且不含查询或片段" }
                        val prefix = route.optString("pathPrefix", "/")
                        require(prefix.startsWith("/") && !prefix.contains("..")) { "线路路径前缀无效" }
                    }
                }
            }
            return MangaAdapterPack(id, name, j.getInt("version"), update, j.toString())
        }

        internal fun hosts(j: JSONObject, key: String): Set<String> {
            val values = j.optJSONArray(key)?.strings().orEmpty()
            require(values.size <= 32) { "适配域名数量超过上限" }
            return values.map { raw ->
                val host = raw.lowercase()
                val url = "https://$host/".toHttpUrl()
                require(host == url.host && url.encodedPath == "/" && url.port == 443 && url.query == null &&
                    url.username.isEmpty() && url.password.isEmpty() && !host.contains('*')) { "适配域名必须为精确域名，不含通配符、端口或路径" }
                host
            }.toSet()
        }

        private fun validateHeaders(j: JSONObject) {
            require(j.length() <= 16) { "请求头数量超过上限" }
            j.keys().forEach { key ->
                require(!key.equals("Cookie", true) && !key.equals("Authorization", true)) { "适配包不能携带登录凭据" }
                MangaRequestHeaders.validate(key, j.getString(key))
            }
        }
    }
}

data class MangaAdapterInstallation(val pack: MangaAdapterPack, val enabled: Boolean = true,
    val previous: MangaAdapterPack? = null, val origin: String = "")

/** One atomic document keeps each package and its previous version together, including after a restart. */
class MangaAdapterRegistry(private val file: File) {
    private val effectiveCache = java.util.concurrent.ConcurrentHashMap<String, MangaSource>()
    @Volatile private var installed: List<MangaAdapterInstallation> = emptyList()
    @Volatile var loadError: String = ""; private set
    @Volatile var revision: Long = 0; private set

    init {
        if (file.isFile) try {
            val text = file.readText(Charsets.UTF_8)
            require(text.toByteArray().size <= 8 * 1024 * 1024) { "适配包存储超过大小上限" }
            val a = JSONArray(text)
            val restored = (0 until a.length()).map { index -> a.getJSONObject(index).let { j ->
                MangaAdapterInstallation(MangaAdapterPack.parse(j.getJSONObject("pack").toString()), j.optBoolean("enabled", true),
                    j.optJSONObject("previous")?.let { MangaAdapterPack.parse(it.toString()) }, j.optString("origin"))
            } }
            require(restored.size <= 32 && restored.map { it.pack.id }.distinct().size == restored.size) { "适配包存储条目无效" }
            installed = restored
        } catch (failure: Exception) { loadError = "站点适配包未加载：${failure.message}；原文件已保留" }
    }

    fun entries(): List<MangaAdapterInstallation> = installed.toList()

    @Synchronized fun install(pack: MangaAdapterPack, originals: List<MangaSource>, origin: String = "") {
        check(loadError.isEmpty()) { loadError }
        val old = installed.firstOrNull { it.pack.id == pack.id }
        if (old?.pack?.definition == pack.definition) {
            if (origin.isNotBlank() && origin != old.origin)
                persist(installed.map { if (it.pack.id == pack.id) it.copy(origin = origin) else it })
            return
        }
        require(old == null || pack.version > old.pack.version) { "更新版本必须高于已安装版本；恢复旧版请使用回退" }
        require(installed.size < 32 || old != null) { "最多安装 32 个适配包" }
        val entry = MangaAdapterInstallation(pack, old?.enabled ?: true, old?.pack, origin.ifBlank { old?.origin.orEmpty() })
        val next = if (old == null) installed + entry else installed.map { if (it.pack.id == pack.id) entry else it }
        // Validate every affected stored source before changing the active document.
        originals.forEach { effective(it, next) }
        persist(next)
    }

    @Synchronized fun toggle(id: String, originals: List<MangaSource>) {
        val next = installed.map { if (it.pack.id == id) it.copy(enabled = !it.enabled) else it }
        originals.forEach { effective(it, next) }; persist(next)
    }

    @Synchronized fun rollback(id: String, originals: List<MangaSource>) {
        val item = installed.firstOrNull { it.pack.id == id } ?: error("适配包不存在")
        val previous = item.previous ?: error("没有可回退的版本")
        val next = installed.map { if (it.pack.id == id) it.copy(pack = previous, previous = item.pack) else it }
        originals.forEach { effective(it, next) }; persist(next)
    }

    @Synchronized fun remove(id: String) = persist(installed.filterNot { it.pack.id == id })

    private fun persist(next: List<MangaAdapterInstallation>) {
        check(loadError.isEmpty()) { loadError }
        val text = JSONArray().also { a -> next.forEach { item -> a.put(JSONObject()
            .put("pack", JSONObject(item.pack.definition)).put("enabled", item.enabled).put("origin", item.origin)
            .put("previous", item.previous?.let { JSONObject(it.definition) })) } }.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "已安装适配包及回退数据合计超过 8 MB" }
        atomicJson(file, text)
        installed = next; revision++; effectiveCache.clear()
    }

    fun effective(source: MangaSource): MangaSource {
        if (effectiveCache.size > 2048) effectiveCache.clear()
        val key = "$revision|${source.id}|${source.enabled}|${source.ruleVersion}"
        return effectiveCache.getOrPut(key) { effective(source, installed) }
    }

    private fun matching(source: MangaSource, entries: List<MangaAdapterInstallation> = installed): List<Pair<MangaAdapterPack, JSONObject>> =
        entries.filter { it.enabled }.flatMap { item -> item.pack.rulesFor(source.baseUrl.toHttpUrl().host).mapNotNull { adapter ->
            val ids = adapter.optJSONArray("sourceIds")?.strings().orEmpty()
            if (ids.isEmpty() || source.id in ids) item.pack to adapter else null
        } }

    private fun effective(source: MangaSource, entries: List<MangaAdapterInstallation>): MangaSource {
        val matches = matching(source, entries)
        if (matches.isEmpty()) return source
        val j = source.json()
        matches.forEach { (_, rule) -> rule.optJSONObject("patch")?.let { merge(j, it) } }
        j.put("siteAdapterRevision", matches.joinToString("|") { (pack, rule) -> "${pack.id}:${pack.version}:${rule.getString("id")}:${pack.fingerprint}" })
        return MangaSource.parse(j)
    }

    fun overridesReader(source: MangaSource) = matching(source).any { (_, rule) -> rule.optBoolean("overrideReader") }

    fun requestUrl(source: MangaSource, url: String): String = matching(source).fold(url) { current, (_, rule) ->
        route(current, rule.optJSONArray("requestRoutes")).firstOrNull() ?: current
    }

    fun imageCandidates(source: MangaSource, url: String): List<String> =
        (listOf(url) + matching(source).flatMap { (_, rule) -> route(url, rule.optJSONArray("imageMirrors")) }).distinct().take(13)

    fun imageHeaders(source: MangaSource, chapterUrl: String, imageUrl: String): Map<String, String> = buildMap {
        matching(source).forEach { (_, rule) ->
            val host = imageUrl.toHttpUrl().host
            if (host in MangaAdapterPack.hosts(rule, "hosts") + MangaAdapterPack.hosts(rule, "imageHosts")) {
                rule.optJSONObject("imageHeaders")?.let { headers -> headers.keys().forEach { key ->
                    val value = headers.getString(key).replace("{chapterUrl}", chapterUrl)
                        .replace("{sourceUrl}", source.baseUrl).replace("{imageUrl}", imageUrl)
                    MangaRequestHeaders.validate(key, value); put(key, value)
                } }
            }
        }
    }

    private fun route(url: String, routes: JSONArray?): List<String> {
        if (routes == null) return emptyList()
        val from = url.toHttpUrl()
        return (0 until routes.length()).mapNotNull { index ->
            val r = routes.getJSONObject(index)
            val prefix = r.optString("pathPrefix", "/")
            if (from.host != r.getString("fromHost").lowercase() || !from.encodedPath.startsWith(prefix)) null else {
                val to = r.getString("toBaseUrl").toHttpUrl()
                to.newBuilder().encodedPath(to.encodedPath.trimEnd('/') + "/" + from.encodedPath.removePrefix(prefix).trimStart('/'))
                    .encodedQuery(from.encodedQuery).encodedFragment(from.encodedFragment).build().toString()
            }
        }
    }

    private fun merge(target: JSONObject, patch: JSONObject) {
        patch.keys().forEach { key ->
            val value = patch.get(key)
            if (value === JSONObject.NULL) target.remove(key)
            else if (value is JSONObject && target.optJSONObject(key) != null) merge(target.getJSONObject(key), value)
            else target.put(key, when (value) {
                is JSONObject -> JSONObject(value.toString())
                is JSONArray -> JSONArray(value.toString())
                else -> value
            })
        }
    }
}
