package com.shengzimao.reader.mobile.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.UUID
import java.net.URL
import android.os.Build
import dalvik.system.DexClassLoader

/**
 * SoNovel online engine host for Android.
 * The engine ships as pre-built dex files under assets/engine and is loaded in memory,
 * so nothing here references engine classes at build time.
 */
class SonovelEngine(private val context: Context, private val rulesOverride: File? = null) {

    private val engineDir: File = File(context.filesDir, "sonovel").apply { mkdirs() }
    private val rulesFile: File get() = rulesOverride ?: File(engineDir, "rules/main.json")
    private val apiClass: Class<*>
    val available: Boolean
    /** 最近一次加载失败的原因，供界面提示。 */
    var loadError: String? = null
        private set
    var loadFailure: Throwable? = null
        private set

    /** 引擎自检信息（上游配置加载等），空字符串表示无异常。 */
    var diagnostic: String = ""
        private set

    init {
        var loaded: Class<*>? = null
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        val previousConfig = System.getProperty("config.file")
        try {
            releaseAssets()
            val dexDir = File(engineDir, "dex")
            // assets 中的 dex 不含 classpath 资源。引擎的 Hutool 配置读取必须能找到
            // application.properties；同时隔离应用经 R8 改名后的 Kotlin/OkHttp 类。
            val resources = object : ClassLoader(ClassLoader.getSystemClassLoader().parent) {
                override fun findResource(name: String): URL? {
                    if (name == "application.properties") return File(engineDir, name).toURI().toURL()
                    val root = File(engineDir, "resources").canonicalFile
                    val resource = File(root, name).canonicalFile
                    return resource.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }?.toURI()?.toURL()
                }
                override fun findResources(name: String): java.util.Enumeration<URL> =
                    java.util.Collections.enumeration(listOfNotNull(findResource(name)))
            }
            val apiLoader = InMemoryDexLoader.create(
                listOf("classes.dex", "classes2.dex", "wrapper.dex"),
                resources,
                dexDir,
            )
            thread.contextClassLoader = apiLoader
            // 这是进程内 Java 配置属性，不是 Android 系统属性。首次初始化后上游会缓存配置。
            System.setProperty("config.file", File(engineDir, "config.ini").absolutePath)
            val api = Class.forName("org.shengzimao.reader.AndroidEngine", true, apiLoader)
            installJsBridge(api, apiLoader, dexDir)
            api.getMethod("configure", String::class.java, String::class.java)
                .invoke(null, engineDir.absolutePath, rulesFile.absolutePath)
            // 立即做一次本地 probe：书源规则若加载失败，当场把原因写进 loadError，
            // 否则用户会在搜索时只看到一句"任务未完成"。
            val early = runCatching {
                api.getMethod("dispatch", String::class.java)
                    .invoke(null, """{"action":"probe"}""") as? String
            }.getOrNull()
            if (early == null) {
                throw IllegalStateException("引擎自检无响应")
            } else {
                val json = org.json.JSONObject(early)
                if (!json.optBoolean("ok")) {
                    loadError = json.optString("error", "引擎自检失败")
                    throw IllegalStateException("书源规则加载失败：$loadError")
                }
            }
            val report = runCatching { api.getMethod("diagnostics").invoke(null) as? String }.getOrNull()
            if (!report.isNullOrBlank()) {
                // 上游配置加载失败不致命（我们用自己的 AppConfig），但要在界面上留痕。
                Log.w(TAG, "engine diagnostics: $report")
                diagnostic = report
            }
            loaded = api
        } catch (error: Throwable) {
            loadFailure = error
            Log.e(TAG, "engine load failed", error)
            var cause = error
            repeat(16) { cause = cause.cause?.takeIf { it !== cause } ?: cause }
            loadError = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.name
        } finally {
            thread.contextClassLoader = previousLoader
            if (previousConfig == null) System.clearProperty("config.file") else System.setProperty("config.file", previousConfig)
        }
        apiClass = loaded ?: Any::class.java
        available = loaded != null
    }

    /** Rule scripts using @js: run on Rhino, loaded through its own class loader. */
    private fun installJsBridge(api: Class<*>, apiLoader: ClassLoader, dexDir: File) {
        val bridgeInterface = api.declaredClasses.firstOrNull { it.simpleName == "JsBridge" }
            ?: throw IllegalStateException("engine has no JsBridge interface")
        // Rhino 与 JsBridgeImpl 只依赖 java.* 与 org.mozilla.*：用独立根加载器，
        // 避免与 App 的 kotlin-stdlib 在子类空间里出现第二份类型。
        val jsLoader = InMemoryDexLoader.create(listOf("classes.dex"), ClassLoader.getSystemClassLoader().parent, File(dexDir, "rhino"))
        val run = Class.forName("com.shengzimao.reader.engine.JsBridgeImpl", true, jsLoader)
            .getMethod("run", String::class.java, String::class.java)
        val proxy = Proxy.newProxyInstance(bridgeInterface.classLoader, arrayOf(bridgeInterface)) { _, method, args ->
            when (method.name) {
                "call" -> {
                    val script = args?.getOrNull(0) as? String ?: ""
                    val text = args?.getOrNull(1) as? String ?: ""
                    runCatching { run.invoke(null, script, text) as? String }.getOrNull() ?: text
                }
                "toString" -> "SzmJsBridge"
                "hashCode" -> System.identityHashCode(method)
                "equals" -> false
                else -> null
            }
        }
        Class.forName("org.shengzimao.reader.JavaScriptBridge", true, apiLoader)
            .getDeclaredMethod("install", bridgeInterface)
            .apply { isAccessible = true }
            .invoke(null, proxy)
    }

    private fun workDir(): File = File(engineDir, "work").apply { mkdirs() }

    /** 引擎初始化失败时的诊断信息（空表示正常），用于界面提示。 */
    fun diagnostics(): String = diagnostic

    private fun releaseAssets() {
        val stampFile = File(engineDir, "engine.stamp")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        val stamp = "v12-$version"
        val dexDir = File(engineDir, "dex").apply { mkdirs() }
        val rhinoDir = File(dexDir, "rhino").apply { mkdirs() }
        val required = listOf(
            File(dexDir, "classes.dex"),
            File(dexDir, "classes2.dex"),
            File(dexDir, "wrapper.dex"),
            File(rhinoDir, "classes.dex"),
            File(engineDir, "application.properties"),
            File(engineDir, "config.ini"),
            File(engineDir, "rules/main.json"),
        )
        // 记录每个 dex 的大小，避免半截文件被当成有效缓存后引擎永久不可用。
        val manifest = required.joinToString("|") { "${it.name}:${it.length()}" }
        if (stampFile.isFile && stampFile.readText() == "$stamp|$manifest" && required.all { it.length() > 0 }) return
        copyAsset("engine/dex/classes.dex", File(dexDir, "classes.dex"))
        copyAsset("engine/dex/classes2.dex", File(dexDir, "classes2.dex"))
        copyAsset("engine/dex/wrapper.dex", File(dexDir, "wrapper.dex"))
        copyAsset("engine/rhino/dex/classes.dex", File(rhinoDir, "classes.dex"))
        // 上游 EnvUtils 在类初始化时读工作目录下的 .env（缺失时默认 prod，但为稳妥起见写一个）；
        // 注意 aapt 打包会忽略点开头的文件，所以 .env 必须本地生成而不是从 assets 拷贝。
        File(engineDir, ".env").writeText("ENV=prod\n", Charsets.UTF_8)
        copyAsset("engine/config.ini", File(engineDir, "config.ini"))
        copyAsset("engine/application.properties", File(engineDir, "application.properties"))
        val ini = File(engineDir, "config.ini")
        ini.writeText(ini.readText().replace("active-rules=main.json", "active-rules=${rulesFile.absolutePath}"))
        val rules = File(engineDir, "rules").apply { mkdirs() }
        context.assets.list("engine/rules")?.forEach { name ->
            if (name.endsWith(".json") || name.endsWith(".json5")) copyAsset("engine/rules/$name", File(rules, name))
        }
        stampFile.writeText("$stamp|" + required.joinToString("|") { "${it.name}:${it.length()}" })
    }

    private fun copyAsset(path: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(path).use { input -> target.writeBytes(input.readBytes()) }
    }


    private fun call(request: JSONObject): JSONObject {
        if (!available) throw IllegalStateException("内置在线引擎未能加载" + (loadError?.let { "：$it" } ?: ""))
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            thread.contextClassLoader = apiClass.classLoader
            val output = apiClass.getMethod("dispatch", String::class.java).invoke(null, request.toString()) as String
            return JSONObject(output).also { result ->
                if (!result.optBoolean("ok")) throw OnlineEngineException(result.optString("error", "在线任务失败"), result.optString("stack"))
            }
        } finally {
            thread.contextClassLoader = previous
        }
    }

    fun probe(): JSONObject {
        val result = call(JSONObject().put("action", "probe"))
        if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "probe failed"))
        return result.optJSONObject("data") ?: JSONObject()
    }

    fun search(keyword: String): JSONArray {
        val result = call(JSONObject().put("action", "search").put("keyword", keyword))
        if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "search failed"))
        return result.optJSONArray("data") ?: JSONArray()
    }

    fun details(url: String): JSONObject {
        val result = call(JSONObject().put("action", "details").put("url", url))
        if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "details failed"))
        return result.getJSONObject("data")
    }

    fun update(url: String, start: Int, chapters: JSONArray): JSONObject {
        val result = call(JSONObject().put("action", "update").put("url", url).put("start", start).put("chapters", chapters))
        if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "update failed"))
        return result.getJSONObject("data")
    }

    fun download(url: String, format: String): DownloadResult {
        val job = File(engineDir, "jobs/${UUID.randomUUID()}").apply { mkdirs() }
        val thread = Thread.currentThread()
        val previousLoader = thread.contextClassLoader
        thread.contextClassLoader = apiClass.classLoader
        try {
            // 新任务的正文与 metadata 必须落在同一个独立目录，便于校验和清理。
            apiClass.getMethod("configure", String::class.java, String::class.java)
                .invoke(null, job.absolutePath, rulesFile.absolutePath)
            val result = call(JSONObject().put("action", "download").put("url", url).put("format", "txt"))
            if (!result.optBoolean("ok")) throw IllegalStateException(result.optString("error", "download failed"))
            val files = result.optJSONArray("data") ?: JSONArray()
            if (files.length() == 0) throw IllegalStateException("engine produced no file")
            val text = File(files.getString(0))
            if (!text.isFile) throw IllegalStateException("downloaded file missing: ${text.name}")
            val metadata = File(job, "download-metadata.json").takeIf { it.isFile }
            val output = if (format == "epub") {
                val details = metadata?.let { Updates.validateDetails(JSONObject(it.readText())) }
                    ?: throw IllegalStateException("EPUB 转换缺少书籍目录。")
                val body = text.readText(Charsets.UTF_8)
                val epub = File(job, "book.epub")
                DocumentExporter.exportEpub(details.book.bookName, details.book.author, body,
                    Updates.mapOnlineChapters(body, details.toc), epub.outputStream())
                epub
            } else text
            return DownloadResult(output, metadata, job)
        } catch (error: Throwable) {
            job.deleteRecursively()
            throw error
        } finally {
            try {
                if (available) apiClass.getMethod("configure", String::class.java, String::class.java)
                    .invoke(null, engineDir.absolutePath, rulesFile.absolutePath)
            } finally { thread.contextClassLoader = previousLoader }
        }
    }

    data class DownloadResult(val text: File, val metadata: File?, val job: File) {
        fun cleanup() {
            runCatching { job.deleteRecursively() }
        }
    }

    companion object {
        private const val TAG = "SzmEngine"
    }
}

class OnlineEngineException(message: String, val engineStack: String) : IllegalStateException(message)

/** Loads several dex files into one class space. */
object InMemoryDexLoader {
    fun create(order: List<String>, parent: ClassLoader?, dir: File): ClassLoader {
        val files = order.map { File(dir, it) }
        require(files.isNotEmpty() && files.all { it.isFile && it.length() > 0 }) { "engine dex files missing" }
        if (Build.VERSION.SDK_INT == 26 && files.size > 1) {
            return DexClassLoader(files.joinToString(File.pathSeparator) { it.absolutePath }, dir.absolutePath, null, parent)
        }
        val buffers = files.map { ByteBuffer.wrap(it.readBytes()) }
        return if (buffers.size == 1) dalvik.system.InMemoryDexClassLoader(buffers.single(), parent)
            else dalvik.system.InMemoryDexClassLoader(buffers.toTypedArray(), parent)
    }
}
